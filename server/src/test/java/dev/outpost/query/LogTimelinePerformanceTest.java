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
 * Performance guards for the log timeline (#141): unlike the list queries' O(page)
 * cost via an early-exiting walk, the timeline reads its whole window (O(matching
 * rows)), relying on partition pruning and the {@code V14} covering index.
 * {@link dev.outpost.TestcontainersConfiguration} pins {@code random_page_cost} to
 * the SSD-representative 1.1; at Postgres's default the covering index can lose to
 * a sequential scan non-deterministically.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogTimelinePerformanceTest {

	private static final int WINDOW_DAYS = 14;

	/**
	 * One ceiling for every shape: roughly 2x the widest measurement and half the
	 * full-scan cost of {@code log_record}, so dropping the covering index still
	 * fails it. Fixed rather than derived from the window's row count, since row
	 * count is stable across reseeds and a derived ceiling would just track noise.
	 */
	private static final long MAX_TIMELINE_BLOCKS = 2_500;

	/** The index {@code V14} adds, and the only one any timeline shape may walk. */
	private static final List<String> TIMELINE_INDEX = List.of("idx_log_timeline");

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
	 * One chart the UI can produce. {@code from} of {@code null} is the range
	 * picker's "All time", resolved here the way the controller resolves it.
	 */
	private record Shape(String name, List<Long> project, List<String> environment, Instant from) {
	}

	/**
	 * The charts the logs page can ask for. The level filter is deliberately absent:
	 * the timeline draws every level as its own series, so narrowing to one is a
	 * cheaper case of the same plan rather than a different shape.
	 */
	private List<Shape> uiShapes(Instant since) {
		List<Long> oneProject = List.of(seeded.projectId());
		List<String> oneEnvironment = List.of(seeded.environment());
		return List.of(new Shape("14d (the default)", null, null, since),
				new Shape("All time", null, null, null),
				new Shape("1h", null, null, Instant.now().minus(1, ChronoUnit.HOURS)),
				new Shape("14d, one project", oneProject, null, since),
				new Shape("All time, one project", oneProject, null, null),
				new Shape("14d, one environment", null, oneEnvironment, since));
	}

	/**
	 * Every chart is served index-only by {@code V14}'s index on every partition
	 * holding data — named explicitly because a fallback plan walking
	 * {@code idx_log_ts_id} and fetching {@code level} from the heap is also "some
	 * index used", also ordered, also sort-free. The empty partition the manager
	 * keeps ahead of the newest row is exempted from the no-sequential-scan check
	 * because any planner reads an empty relation sequentially.
	 */
	@Test
	void everyTimelineShapeIsIndexOnly() {
		Instant since = windowStart();
		for (Shape shape : uiShapes(since)) {
			PlanFacts facts = timeline(shape).explain(jdbc);

			QueryGuard.assertWalksIndex(jdbc, facts, "log_record", TIMELINE_INDEX, "log timeline — " + shape.name());
			QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, "log timeline — " + shape.name());
		}
	}

	/** And costs a bounded amount while doing it, including at the widest window the picker offers. */
	@Test
	void everyTimelineShapeStaysUnderItsCeiling() {
		Instant since = windowStart();
		for (Shape shape : uiShapes(since)) {
			PlanFacts facts = timeline(shape).explain(jdbc);

			QueryGuard.assertUnderCeiling(facts, MAX_TIMELINE_BLOCKS, "log timeline — " + shape.name());
		}
		QueryGuard.assertCeilingCanFail(jdbc, MAX_TIMELINE_BLOCKS, "log_record");
	}

	/**
	 * A bounded chart must read only the partitions its window covers. Unlike
	 * {@link LogQueryPerformanceTest#timeBoundedQueryPrunesToItsWindow} this needs
	 * no unindexable predicate to be meaningful — an aggregate has no early exit
	 * to defeat, so the window alone decides how many partitions it reads.
	 */
	@Test
	void theTimelinePrunesToItsWindow() {
		Instant since = windowStart();

		PlanFacts facts = QueryPlans.logTimeline(null, null, null, null, null, null, null, since, Instant.now())
			.explain(jdbc);

		QueryGuard.assertPrunesFrom(jdbc, facts, "log_record", since, "a 14-day log timeline");
	}

	/**
	 * The grouping has to hash in memory. A timeline spilling to a temp file is
	 * grouping far more than the ≤150 buckets × 6 levels it returns — the signature
	 * of a bucket width that stopped being derived from the window.
	 */
	@Test
	void theAggregateDoesNotSpill() {
		Instant since = windowStart();
		for (Shape shape : uiShapes(since)) {
			PlanFacts facts = timeline(shape).explain(jdbc);

			QueryGuard.assertNoTempFiles(facts, "log timeline — " + shape.name());
		}
	}

	/**
	 * The chart inherently costs more than the page beside it — it reads a window
	 * where the list reads a page — but must stay within an order of magnitude,
	 * which is what the covering index buys.
	 */
	@Test
	void theChartStaysWithinAnOrderOfMagnitudeOfTheListBesideIt() {
		Instant since = windowStart();
		long list = QueryPlans.logs(null, null, null, null, null, null, null, since, null, null)
			.explain(jdbc)
			.logicalIo();
		PlanFacts chart = QueryPlans.logTimeline(null, null, null, null, null, null, null, since, Instant.now())
			.explain(jdbc);

		assertThat(chart.logicalIo())
			.as("blocks for the 14-day timeline against the %d the log list beside it costs%n%s", list, chart.plan())
			.isLessThan(10 * list);
	}

	private QueryPlans.Built timeline(Shape shape) {
		Instant from = shape.from() != null ? shape.from()
				: partitions.earliestPartitionStart(PartitionManager.LOG_RECORD).orElseThrow();
		return QueryPlans.logTimeline(shape.project(), shape.environment(), null, null, null, null, null, from,
				Instant.now());
	}

	private static Instant windowStart() {
		return Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);
	}

}
