package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import dev.outpost.support.PlanFacts;
import dev.outpost.support.TelemetrySeeder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Performance guards for the log stream. Baselines measured 2026-08-03 against
 * {@link TelemetrySeeder.Scale#GUARD}: 40 010 log records over 10 weekly
 * partitions, where a full scan of {@code log_record} costs 5 043 blocks.
 *
 * <p>The shape of these guards is set by what #128 turned out to be. {@code
 * log_record} had no index on the {@code ("timestamp", id)} ordering every log
 * list is paged by, so page 1 of the global stream read all ten partitions and
 * sorted them — 9 010 blocks to return 100 rows, more than the 5 043 reading the
 * table costs. {@code V11} adds that index and a project-leading one; the guards
 * below assert the plan shape those buy, at every request shape the UI can
 * actually produce.
 *
 * <p><b>The matrix is the guard, not page 1.</b> #126's lesson was that a guard is
 * only as honest as the parameters it passes: {@code issueListSortIsIndexSupported}
 * stayed green throughout a real bug because it asked about a request nobody
 * makes. The log equivalent would be to measure the unfiltered, unbounded stream —
 * reachable through the range picker's "All time", but not the default — and call
 * it the log page. The UI defaults to 14 days ({@code ui/src/app/core/filters.ts})
 * and offers repeated project and environment filters (ADR 0009), so
 * {@link #everyLogListShapeWalksItsOwnIndex} names the index that must serve each
 * of those combinations, and {@link #everyLogListShapeStaysIndexedAtDepth} walks
 * a real cursor to page 3 <em>under each shape's own filters</em>.
 *
 * <p>Body-substring search is deliberately <b>not</b> guarded here, and {@code V11}
 * strengthened that argument. It looked broken at this scale and is not: 40 000 rows
 * is small enough that the planner correctly prefers a scan to {@code
 * idx_log_body_trgm} (#129). Since the ordering index exists there are two near-cost
 * plans for it at benchmark scale — the trigram bitmap scan, and an ordered walk of
 * {@code idx_log_ts_id} filtering with {@code ILIKE} — and the planner has been
 * observed choosing differently on consecutive runs over the same dataset. A guard
 * whose verdict flips with dataset size is worse than none; one that flips between
 * runs is worse still.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogQueryPerformanceTest {

	private static final int WINDOW_DAYS = 14;

	/** Healthy is ~89 blocks for the indexed trace lookup; 790 is the standard 10x, well under a full scan. */
	private static final long MAX_TRACE_LOOKUP_BLOCKS = 790;

	/**
	 * The unfiltered stream's ceiling, calibrated against <b>All time</b> rather
	 * than the 14-day default, because that is the expensive end of the range
	 * picker: 480–725 blocks against ~340. The difference is not rows read — both
	 * execute the same three partitions — but the planner touching all ten it has to
	 * consider before pruning, which {@link PlanFacts} counts because it sums the
	 * {@code Planning} buffers too. That grows with retention, not with the dataset.
	 *
	 * <p>Not the standard 10x, which would be 7 250 and sit above the 5 043 a full
	 * scan costs, i.e. could not fail. This is the "as high as it can while still
	 * being able to fail" case the calibration rules name: half the full scan, ~3.5x
	 * over the measured plan, and comfortably below the 9 010 the sorting plan cost.
	 */
	private static final long MAX_PAGE_ONE_BLOCKS = 2_500;

	/**
	 * A 0.1 %-selective attribute equality served by {@code idx_log_attributes}:
	 * 404–410 blocks, measured 2026-09-15 over three runs. Not the standard 10x, which
	 * at ~4 100 would pass the 3 992-block plan the unindexable {@code ->>} predicate
	 * cost (#132) — the plan this guard exists to reject. 1 500 is ~3.7x the healthy
	 * plan and well under that one.
	 */
	private static final long MAX_SELECTIVE_ATTRIBUTE_BLOCKS = 1_500;

	/** Page 1 and page N differ by the keyset predicate alone, so a small constant covers the noise. */
	private static final int DEEP_PAGE_TOLERANCE = 2;

	/** Deep enough that a plan reading the table end to end cannot keep up, shallow enough to walk cheaply. */
	private static final int DEEP_PAGE = 3;

	/** Two of the three seeded environments — a multi-value filter that really excludes rows. */
	private static final List<String> TWO_ENVIRONMENTS = List.of(TelemetrySeeder.ENVIRONMENTS.get(0),
			TelemetrySeeder.ENVIRONMENTS.get(1));

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	TelemetrySeeder.Seeded seeded;

	@BeforeAll
	void seed() {
		seeded = new TelemetrySeeder(jdbc, partitions).seed(TelemetrySeeder.Scale.GUARD);
	}

	/**
	 * One request the UI can produce, paired with the index that has to serve it.
	 *
	 * @param indexes the index families the plan may walk — and nothing else. Naming
	 * them is what makes the assertion able to fail: "some index was used" stays true
	 * when the wrong one is chosen, and Postgres will happily walk the global
	 * ordering index and apply {@code project_id} as a filter, which is ordered,
	 * sort-free, and exactly the plan {@code idx_log_project_ts_id} exists to avoid.
	 * More than one is named only where more than one is genuinely equivalent.
	 */
	private record Shape(String name, List<Long> project, List<String> environment, Instant from,
			List<String> indexes) {

		QueryPlans.Built at(String cursor) {
			return QueryPlans.logs(project, environment, null, null, null, null, null, from, null, cursor);
		}
	}

	/**
	 * The shapes the logs page actually sends, and the index each must walk.
	 *
	 * <p>Several expect the <em>global</em> index while carrying a filter, and that is
	 * a finding rather than an oversight. A multi-value filter leaves rows ordered by
	 * {@code (project_id, "timestamp")} rather than by {@code "timestamp"}, so no
	 * leading-column index can serve the ordering and Postgres correctly falls back to
	 * walking the global one and filtering. Asserting the global index there pins that
	 * down: if a later migration adds an index that makes the planner sort instead,
	 * this fails.
	 *
	 * <p><b>The multi-select case is carried by environments, not projects, and the
	 * reason is a fixture limitation worth stating.</b> {@link TelemetrySeeder.Scale#GUARD}
	 * seeds two projects, so a two-project filter selects <em>every row in the
	 * table</em> — it would assert the multi-select plan against a predicate that
	 * excludes nothing, which is the same "passes for the wrong reason forever" trap
	 * the seeder's single-partition note warns about. Two of the three seeded
	 * environments genuinely excludes a third of the rows, so that is the shape the
	 * claim rests on.
	 *
	 * <p>Project + environment together is served by the pre-existing
	 * {@code idx_log_project_env_ts} with an incremental sort, which {@link
	 * QueryGuard#assertWalksIndex} allows — an incremental sort is bounded by the
	 * group size, not by the table, which is the property under guard. It is the one
	 * shape naming two indexes, because {@code idx_log_project_ts_id} serves it just
	 * as well and the two measured four blocks apart.
	 */
	private List<Shape> uiShapes(Instant since) {
		List<Long> oneProject = List.of(seeded.projectId());
		List<String> oneEnvironment = List.of(seeded.environment());
		List<String> global = List.of("idx_log_ts_id");
		List<String> byProject = List.of("idx_log_project_ts_id");
		List<String> byProjectAndEnv = List.of("idx_log_project_env_ts", "idx_log_project_ts_id");
		return List.of(new Shape("global, 14d (the default)", null, null, since, global),
				new Shape("global, All time", null, null, null, global),
				new Shape("one project, 14d", oneProject, null, since, byProject),
				new Shape("one project, All time", oneProject, null, null, byProject),
				new Shape("one environment, 14d", null, oneEnvironment, since, global),
				new Shape("one environment, All time", null, oneEnvironment, null, global),
				new Shape("two environments, 14d", null, TWO_ENVIRONMENTS, since, global),
				new Shape("one project + one environment, 14d", oneProject, oneEnvironment, since, byProjectAndEnv),
				new Shape("one project + one environment, All time", oneProject, oneEnvironment, null,
						byProjectAndEnv));
	}

	// --------------------------------------------------------------- ordering

	/**
	 * Page 1 of the global stream returns 100 rows and should cost about what
	 * fetching 100 rows costs. Because {@code log_record} is range-partitioned on
	 * the same column the {@code ORDER BY} leads with, the index gives Postgres an
	 * ordered {@code Append}: it walks partitions newest-first and stops once the
	 * page is full, reading three of ten rather than merging all of them.
	 */
	@Test
	void logListPageOneIsIndexSupported() {
		PlanFacts facts = pageOne().explain(jdbc);

		// Names the index rather than only excluding a Sort: #126 recorded that
		// "no Sort ran" stays green with the right indexes dropped, because walking a
		// different index and filtering is also ordered and also sort-free.
		QueryGuard.assertWalksIndex(jdbc, facts, "log_record", List.of("idx_log_ts_id"), "log list page 1");
		QueryGuard.assertUnderCeiling(facts, MAX_PAGE_ONE_BLOCKS, "log list page 1");
		QueryGuard.assertCeilingCanFail(jdbc, MAX_PAGE_ONE_BLOCKS, "log_record");
	}

	/** Every UI-producible shape walks the index built for it, rather than sorting the table. */
	@Test
	void everyLogListShapeWalksItsOwnIndex() {
		Instant since = windowStart();
		for (Shape shape : uiShapes(since)) {
			PlanFacts facts = shape.at(null).explain(jdbc);

			QueryGuard.assertWalksIndex(jdbc, facts, "log_record", shape.indexes(), "log list — " + shape.name());
		}
	}

	/**
	 * The same shapes at depth, each walked to page {@value #DEEP_PAGE} through its
	 * <em>own</em> filters.
	 *
	 * <p>Walking unfiltered and then explaining a filtered query at the cursor it
	 * ended on would measure a request nobody makes: a filtered stream reaches a
	 * different row, over a different span of time, after the same number of pages.
	 */
	@Test
	void everyLogListShapeStaysIndexedAtDepth() {
		Instant since = windowStart();
		for (Shape shape : uiShapes(since)) {
			String cursor = QueryPlans.logCursorAtPage(jdbc, DEEP_PAGE, shape::at);
			PlanFacts facts = shape.at(cursor).explain(jdbc);

			QueryGuard.assertWalksIndex(jdbc, facts, "log_record", shape.indexes(),
					"log list at page " + DEEP_PAGE + " — " + shape.name());
		}
	}

	/**
	 * Whatever page 1 costs, page N must cost the same — that is the whole claim
	 * keyset paging makes.
	 *
	 * <p>The ratio alone is not enough, and this is the flaw it used to have: two
	 * equally bad full scans satisfy it together, which is precisely what happened
	 * while #128 was open. The absolute ceiling is what stops a deep page passing by
	 * being no worse than a broken page 1.
	 */
	@Test
	void deepPageCostsWhatPageOneCostsAndBothStayUnderTheCeiling() {
		long pageOneBlocks = pageOne().explain(jdbc).logicalIo();
		String cursor = QueryPlans.logCursorAtPage(jdbc, DEEP_PAGE);

		PlanFacts deep = QueryPlans.logs(null, null, null, null, null, null, null, null, null, cursor).explain(jdbc);

		assertThat(deep.logicalIo()).as("blocks for a deep log page against the %d page 1 costs%n%s", pageOneBlocks,
				deep.plan()).isLessThanOrEqualTo(DEEP_PAGE_TOLERANCE * pageOneBlocks);
		QueryGuard.assertUnderCeiling(deep, MAX_PAGE_ONE_BLOCKS, "a deep log page");
		QueryGuard.assertCeilingCanFail(jdbc, MAX_PAGE_ONE_BLOCKS, "log_record");
	}

	// ---------------------------------------------------------------- lookups

	@Test
	void traceLookupUsesTheTraceIndex() {
		PlanFacts facts = QueryPlans.logs(null, null, null, seeded.traceId(), null, null, null, null, null, null)
			.explain(jdbc);

		QueryGuard.assertUnderCeiling(facts, MAX_TRACE_LOOKUP_BLOCKS, "logs by trace_id");
		QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, "logs by trace_id");
		QueryGuard.assertNoTempFiles(facts, "logs by trace_id");
		QueryGuard.assertCeilingCanFail(jdbc, MAX_TRACE_LOOKUP_BLOCKS, "log_record");
	}

	/**
	 * A bounded log query must prune to the partitions its window covers.
	 *
	 * <p><b>The query under test is deliberately a filtered one, because {@code V11}
	 * made the unfiltered page unable to test this.</b> An ordered walk stops as soon
	 * as the page is full — three partitions — whether or not the bound prunes the
	 * other seven, so asserting `scanned ⊆ allowed` on the plain 14-day page passes
	 * identically with the bound removed. That is the "ceiling that cannot fail"
	 * mistake in a different costume, and it is what this guard had become.
	 *
	 * <p>A filter the ordered walk cannot satisfy within a page restores the property:
	 * the plan has to traverse its whole window to know there is nothing further, so
	 * the window is what decides how many partitions it reads — five of ten here, and
	 * all ten if the bound stops pruning. It uses a selective attribute equality for
	 * that. Since #132 that predicate is answered by {@code idx_log_attributes} rather
	 * than applied after the read, and it still qualifies: a bitmap lookup cannot stop
	 * early either, so it reads the index of every partition in its window. A
	 * predicate that stops qualifying is one the ordered walk fills a page with.
	 */
	@Test
	void timeBoundedQueryPrunesToItsWindow() {
		Instant since = windowStart();
		PlanFacts facts = boundedLogs(since, null, List.of(seeded.attributeKey() + "=" + seeded.attributeValue()))
			.explain(jdbc);

		QueryGuard.assertPrunesFrom(jdbc, facts, "log_record", since, "a 14-day-bounded log query");
		QueryGuard.assertNoTempFiles(facts, "a 14-day-bounded log query");
	}

	// ---------------------------------------------------------------- filters

	/**
	 * An attribute equality filter is answered by {@code idx_log_attributes}: its text
	 * is expanded into one {@code @>} containment per JSON value it could denote
	 * (ADR-0018), and each is a lookup in the generic {@code jsonb_ops} GIN index. The
	 * second shape adds a second key, so a fixture-specific expression index on
	 * {@code order.id} could not pass in its place.
	 *
	 * <p><b>Why this is not the ratio #132 first asked for.</b> That was "a 0.1 %
	 * filter costs a quarter of the unfiltered page", written when the unfiltered page
	 * scanned and sorted. {@code V11} made that page an ordered walk that stops at 100
	 * rows (~340 blocks), and a bitmap lookup cannot stop early — it reads every match
	 * in the window and sorts them. Measured, the healthy plan costs about what the
	 * unfiltered page does, so the ratio would reject it. What the fix changed is which
	 * index is read and how far the cost fell from the 3 992 blocks the unindexable
	 * predicate cost, and that is what is asserted.
	 *
	 * <p>A <em>common</em> value is deliberately not guarded: the planner walks
	 * {@code idx_log_ts_id} for it, as it should, and its cost is recorded in
	 * {@code docs/performance/measuring-retrieval.md} rather than bounded here.
	 */
	@Test
	void selectiveAttributeEqualityIsServedByTheAttributesIndex() {
		Instant since = windowStart();
		String selective = seeded.attributeKey() + "=" + seeded.attributeValue();
		for (List<String> attr : List.of(List.of(selective),
				List.of(selective, "logger.name=dev.outpost.OrderService"))) {
			String what = "a 14-day log query filtered by " + attr;
			PlanFacts facts = boundedLogs(since, null, attr).explain(jdbc);

			QueryGuard.assertReadsOnlyIndex(jdbc, facts, "log_record", List.of("idx_log_attributes"), what);
			QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, what);
			QueryGuard.assertUnderCeiling(facts, MAX_SELECTIVE_ATTRIBUTE_BLOCKS, what);
			QueryGuard.assertNoTempFiles(facts, what);
		}
		QueryGuard.assertCeilingCanFail(jdbc, MAX_SELECTIVE_ATTRIBUTE_BLOCKS, "log_record");
	}

	/** Presence filtering goes through {@code jsonb_exists}, which the GIN index does serve. */
	@Test
	void attributePresenceFilterStaysWithinTheUnfilteredCost() {
		Instant since = windowStart();
		long unfiltered = boundedLogs(since, null, null).explain(jdbc).logicalIo();
		PlanFacts facts = boundedLogs(since, null, List.of(seeded.attributeKey())).explain(jdbc);

		assertThat(facts.logicalIo())
			.as("blocks for an attribute-presence filter against the %d an unfiltered page costs%n%s", unfiltered,
					facts.plan())
			.isLessThanOrEqualTo(DEEP_PAGE_TOLERANCE * unfiltered);
	}

	// ----------------------------------------------------------------- helpers

	private static Instant windowStart() {
		return Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);
	}

	private QueryPlans.Built pageOne() {
		return QueryPlans.logs(null, null, null, null, null, null, null, null, null, null);
	}

	/**
	 * The bound is passed in rather than recomputed per call: two
	 * {@link #windowStart()} evaluations either side of a Monday would bound the
	 * query by one week and assert pruning against another, and the guard would
	 * flake for a reason that has nothing to do with the query.
	 */
	private QueryPlans.Built boundedLogs(Instant since, String query, List<String> attr) {
		return QueryPlans.logs(null, null, null, null, null, query, attr, since, null, null);
	}

}
