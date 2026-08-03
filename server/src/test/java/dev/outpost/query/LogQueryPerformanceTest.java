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
import org.junit.jupiter.api.Disabled;
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
 * <p>Body-substring search is deliberately <b>not</b> guarded here. It looked
 * broken at this scale and is not: 40 000 rows is small enough that the planner
 * correctly prefers a scan to {@code idx_log_body_trgm}, and at the retrieval
 * benchmark's 5 000 000 it uses the index for an 11x saving. A guard whose verdict
 * flips with dataset size is worse than none, so that question lives in the
 * benchmark, which has the rows to answer it.
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
	 * picker: 480–725 blocks against ~340. The difference is not rows but
	 * per-partition startup — ten index scans to open instead of five — so it grows
	 * with retention and not with the dataset.
	 *
	 * <p>Not the standard 10x, which would be 7 250 and sit above the 5 043 a full
	 * scan costs, i.e. could not fail. This is the "as high as it can while still
	 * being able to fail" case the calibration rules name: half the full scan, ~3.5x
	 * over the measured plan, and comfortably below the 9 010 the sorting plan cost.
	 */
	private static final long MAX_PAGE_ONE_BLOCKS = 2_500;

	/** A filter that removes 99.9 % of rows should remove most of the work; 4x is the modest floor. */
	private static final int SELECTIVE_FILTER_SAVING = 4;

	/** Page 1 and page N differ by the keyset predicate alone, so a small constant covers the noise. */
	private static final int DEEP_PAGE_TOLERANCE = 2;

	/** Deep enough that a plan reading the table end to end cannot keep up, shallow enough to walk cheaply. */
	private static final int DEEP_PAGE = 3;

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
	 * <p>Two of them expect the <em>global</em> index while carrying a filter, and
	 * that is a finding rather than an oversight. A multi-value filter leaves rows
	 * ordered by {@code (project_id, "timestamp")} rather than by {@code "timestamp"},
	 * so no leading-column index can serve the ordering and Postgres correctly falls
	 * back to walking the global one and filtering. Asserting the global index there
	 * pins that down: if a later migration adds an index that makes the planner sort
	 * instead, this fails.
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
				new Shape("two projects, 14d", seeded.projectIds(), null, since, global),
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
	 * <p>A filter the index cannot serve restores the property: the plan has to
	 * traverse its whole window to know there is nothing further, so the window is
	 * what decides how many partitions it reads — five of ten here, and all ten if
	 * the bound stops pruning. It uses the attribute-equality filter for that, which
	 * is unindexable for #132's separate reasons; the two are orthogonal, and if
	 * #132 is ever fixed this needs a different unindexable predicate rather than a
	 * quiet pass.
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
	 * {@code attributes->>? = ?} cannot use {@code idx_log_attributes}: the key is a
	 * bind parameter, and {@code jsonb_ops} indexes containment rather than text
	 * extraction. Adding a filter that matches one row in a thousand therefore costs
	 * exactly as much as not adding it.
	 *
	 * <p><b>#128 made the defect far more visible and this spec less usable, and
	 * whoever picks up #132 needs to know both.</b> The comparator is the same query
	 * without the filter, which used to cost 4 570 blocks because it scanned and
	 * sorted; it now costs ~342 because it walks {@code idx_log_ts_id}. The filtered
	 * query did <em>not</em> follow it down — measured at 3 992 blocks, still a
	 * sequential scan and sort, because the planner will not walk the ordered index
	 * for a predicate it believes is this selective. So the filter now costs 11x the
	 * unfiltered page rather than exactly as much as it, which is a sharper
	 * diagnostic than the equality this guard was written against.
	 *
	 * <p>What it does not survive is the assertion. A
	 * {@value #SELECTIVE_FILTER_SAVING}x saving against 342 is under 90 blocks, and a
	 * correct GIN lookup plus its heap fetches may well not beat that — so this ratio
	 * can now reject a healthy plan, or reward a fixture-specific combined index. It
	 * is left {@code @Disabled} and unchanged rather than retuned to a number nobody
	 * has measured against a working implementation.
	 */
	@Test
	@Disabled("#132 — attributes->>? = ? cannot use the attributes GIN index; ratio needs re-deriving, see javadoc")
	void attributeFilterMakesTheQueryCheaper() {
		Instant since = windowStart();
		assertSelectiveFilterPaysForItself(since,
				boundedLogs(since, null, List.of(seeded.attributeKey() + "=" + seeded.attributeValue())),
				"the attribute-equality filter");
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

	/**
	 * Both queries are time-bounded so partition pruning is held constant and the
	 * only difference between them is the predicate under test.
	 */
	private void assertSelectiveFilterPaysForItself(Instant since, QueryPlans.Built filtered, String what) {
		long unfiltered = boundedLogs(since, null, null).explain(jdbc).logicalIo();
		PlanFacts facts = filtered.explain(jdbc);

		assertThat(facts.logicalIo())
			.as("%s matches ~0.1%% of rows but costs %d blocks against the %d an unfiltered page costs — "
					+ "the predicate is being applied after the read, not by an index%n%s", what, facts.logicalIo(),
					unfiltered, facts.plan())
			.isLessThan(unfiltered / SELECTIVE_FILTER_SAVING);
	}

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
