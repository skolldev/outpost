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
 * Performance guards for the log stream, at every request shape the UI can
 * produce: {@link #everyLogListShapeWalksItsOwnIndex} names the index that
 * must serve each project/environment filter combination, and
 * {@link #everyLogListShapeStaysIndexedAtDepth} walks a cursor to page 3 under
 * each shape's own filters. Body-substring search is deliberately not guarded:
 * at this dataset size the planner alternates between two near-cost plans for
 * it across runs, so a guard on it would flake independent of any regression.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogQueryPerformanceTest {

	private static final int WINDOW_DAYS = 14;

	/** Healthy is ~89 blocks for the indexed trace lookup. */
	private static final long MAX_TRACE_LOOKUP_BLOCKS = 790;

	/**
	 * Calibrated against "All time" (480-725 blocks), the expensive end of the
	 * range picker, since planning cost grows with partition count regardless of
	 * how many rows are read.
	 */
	private static final long MAX_PAGE_ONE_BLOCKS = 2_500;

	/** A 0.1%-selective attribute equality served by {@code idx_log_attributes} (404-410 blocks). */
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
	 * @param indexes the only indexes the plan may walk; named explicitly because
	 * "some index was used" stays true even if Postgres falls back to a global
	 * index filtered by {@code project_id}.
	 */
	private record Shape(String name, List<Long> project, List<String> environment, Instant from,
			List<String> indexes) {

		QueryPlans.Built at(String cursor) {
			return QueryPlans.logs(project, environment, null, null, null, null, null, from, null, cursor);
		}
	}

	/**
	 * The shapes the logs page can send, and the index each must walk. Several
	 * shapes expect the global index despite carrying a filter, because a
	 * multi-value filter breaks the leading-column ordering no single index can
	 * serve; environments (not projects) carry the multi-select case since
	 * {@link TelemetrySeeder.Scale#GUARD} seeds only two projects, so a two-project
	 * filter would exclude nothing.
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

	/**
	 * Page 1 of the global stream should cost about what fetching 100 rows costs.
	 * Because {@code log_record} is range-partitioned on the {@code ORDER BY}
	 * column, the index gives an ordered {@code Append} that walks partitions
	 * newest-first and stops once the page is full.
	 */
	@Test
	void logListPageOneIsIndexSupported() {
		PlanFacts facts = pageOne().explain(jdbc);

		// Named explicitly: "no Sort ran" stays green even with the wrong index walked.
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
	 * The same shapes at depth, each walked to page {@value #DEEP_PAGE} through
	 * its own filters — walking unfiltered and reusing that cursor would measure a
	 * request nobody makes, since a filtered stream reaches a different row after
	 * the same number of pages.
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
	 * Whatever page 1 costs, page N must cost the same. The ratio alone isn't
	 * enough — two equally bad full scans would satisfy it too — so the absolute
	 * ceiling stops a deep page passing by merely being no worse than a broken page 1.
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
	 * A bounded log query must prune to the partitions its window covers. Tested
	 * against a selective attribute filter rather than the plain page, because an
	 * unfiltered ordered walk stops after filling one page and would pass this
	 * assertion even with pruning broken.
	 */
	@Test
	void timeBoundedQueryPrunesToItsWindow() {
		Instant since = windowStart();
		PlanFacts facts = boundedLogs(since, null, List.of(seeded.attributeKey() + "=" + seeded.attributeValue()))
			.explain(jdbc);

		QueryGuard.assertPrunesFrom(jdbc, facts, "log_record", since, "a 14-day-bounded log query");
		QueryGuard.assertNoTempFiles(facts, "a 14-day-bounded log query");
	}

	/**
	 * An attribute equality filter is answered by {@code idx_log_attributes}
	 * (ADR-0018); the second shape adds a second key so a fixture-specific
	 * expression index on {@code order.id} couldn't pass in its place. A common
	 * (non-selective) value is deliberately not guarded here — the planner
	 * correctly walks {@code idx_log_ts_id} for it instead.
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

	private static Instant windowStart() {
		return Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);
	}

	private QueryPlans.Built pageOne() {
		return QueryPlans.logs(null, null, null, null, null, null, null, null, null, null);
	}

	/**
	 * The bound is passed in rather than recomputed per call. Two
	 * {@link #windowStart()} evaluations could otherwise straddle a boundary and
	 * bound the query by one window while asserting pruning against another,
	 * flaking for a reason unrelated to the query.
	 */
	private QueryPlans.Built boundedLogs(Instant since, String query, List<String> attr) {
		return QueryPlans.logs(null, null, null, null, null, query, attr, since, null, null);
	}

}
