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
 * Performance guards for the log timeline (#141). Baselines measured 2026-08-05
 * against {@link TelemetrySeeder.Scale#GUARD}: 40 010 log records over 10 weekly
 * partitions, where a full scan of {@code log_record} costs ~5 050 blocks.
 *
 * <p><b>What makes this different from every other guard in the package.</b> The
 * list queries cost O(page) — {@code V11} gave them an ordered walk that stops once
 * 100 rows are in hand, and {@link LogQueryPerformanceTest} guards that it still
 * stops. An aggregate cannot stop early. The timeline reads its window, so its cost
 * is O(matching rows) and the only things standing between it and the whole table
 * are partition pruning and {@code V14}'s covering index. Both are asserted here,
 * because losing either turns the chart into a table scan on the most-visited screen
 * in the product and nothing else in the suite would notice.
 *
 * <p><b>Every shape is guarded, not just the default.</b> #126's lesson, restated by
 * {@code LogQueryPerformanceTest}: a guard is only as honest as the parameters it
 * passes. The timeline's cost varies far more by <em>filter</em> than the list's does
 * — a project-filtered chart is a range scan of the covering index while the global
 * one scans all of it — so a guard measuring only the unfiltered 14-day view would
 * stay green through a regression in the shape most installs actually use. Measured
 * here on 2026-08-05, against the 412 blocks the log list beside it costs:
 *
 * <pre>
 *   1h                       159    index-only throughout
 *   14d (the default)        663    index-only throughout
 *   14d, one project         312    index-only throughout
 *   14d, one environment     663    index-only throughout
 *   All time, one project    579    one partition sequentially scanned
 *   All time               2 685    five partitions sequentially scanned
 * </pre>
 *
 * <p><b>Why All time has its own ceiling, and why no guard here forbids a sequential
 * scan.</b> At 40 000 records a weekly partition holds a few thousand rows, and
 * reading one end to end genuinely beats walking an index over it — so the unbounded
 * chart mixes index-only scans of the big recent partitions with sequential scans of
 * the small old ones, and that is the planner being right rather than a regression.
 * The consequence is that the guard tier cannot assert the property {@code V14} was
 * actually bought for. <b>That the timeline is index-only is a benchmark-tier claim</b>
 * — {@code LogTimelineIndexProbe} measures it at 2 000 000 records, where the same
 * shapes go 130 805 to 21 244 blocks (14d) and 250 106 to 40 191 (All time). This is
 * the same limit {@code LogQueryPerformanceTest} records for body-substring search:
 * a guard whose verdict flips with dataset size is worse than none.
 *
 * <p>What the two ceilings can still catch is the regression that matters — losing
 * the covering index entirely. Before {@code V14} the same All-time query cost 5 066
 * blocks and the 14-day one 3 135, so both ceilings fail if it is dropped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogTimelinePerformanceTest {

	private static final int WINDOW_DAYS = 14;

	/**
	 * Bounded windows, whose worst measured shape is the 663-block 14-day default.
	 * Half the ~5 050 a full scan costs rather than the standard 10x, which would be
	 * 6 630 — above the scan cost, therefore unable to fail. 3.8x over the measurement
	 * and well under the 3 135 the same query cost before {@code V14}.
	 */
	private static final long MAX_TIMELINE_BLOCKS = 2_500;

	/**
	 * "All time" separately, because at guard scale it is not the same kind of query:
	 * five of ten partitions are small enough that a sequential scan is the right plan
	 * (see the class javadoc), so it measures 2 685 where every bounded shape is
	 * index-only. The ceiling sits above that and below both the 5 049 a full scan
	 * costs and the 5 066 this query cost without the covering index — narrow, and
	 * still able to fail for the one reason it is here to catch.
	 */
	private static final long MAX_ALL_TIME_BLOCKS = 4_000;

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
	 * No shape reads an index other than the covering one.
	 *
	 * <p>Naming the index is what makes this able to fail. "Some index was used"
	 * stays true when the planner falls back to walking {@code idx_log_ts_id} and
	 * fetching {@code level} from the heap for every row it counts — which is the
	 * 130 805-block plan {@code V14} exists to replace, is ordered, and is sort-free.
	 *
	 * <p>Read the claim precisely: this says <em>no other index</em>, not <em>no
	 * sequential scan</em>. {@link QueryGuard#assertWalksIndex} is containment over
	 * the indexes touched, and at guard scale the All-time chart legitimately reads
	 * five small partitions end to end without touching any index at all. Asserting
	 * the absence of sequential scans is what the class javadoc explains this tier
	 * cannot do.
	 */
	@Test
	void everyTimelineShapeWalksNoOtherIndex() {
		Instant since = windowStart();
		for (Shape shape : uiShapes(since)) {
			PlanFacts facts = timeline(shape).explain(jdbc);

			QueryGuard.assertWalksIndex(jdbc, facts, "log_record", TIMELINE_INDEX, "log timeline — " + shape.name());
		}
	}

	/** And costs a bounded amount while doing it, including at the widest window the picker offers. */
	@Test
	void everyTimelineShapeStaysUnderItsCeiling() {
		Instant since = windowStart();
		for (Shape shape : uiShapes(since)) {
			long ceiling = shape.from() == null ? MAX_ALL_TIME_BLOCKS : MAX_TIMELINE_BLOCKS;
			PlanFacts facts = timeline(shape).explain(jdbc);

			QueryGuard.assertUnderCeiling(facts, ceiling, "log timeline — " + shape.name());
		}
		QueryGuard.assertCeilingCanFail(jdbc, MAX_TIMELINE_BLOCKS, "log_record");
		QueryGuard.assertCeilingCanFail(jdbc, MAX_ALL_TIME_BLOCKS, "log_record");
	}

	/**
	 * A bounded chart must read only the partitions its window covers — the other
	 * half of what keeps this off the whole table.
	 *
	 * <p>Unlike {@link LogQueryPerformanceTest#timeBoundedQueryPrunesToItsWindow} this
	 * needs no unindexable predicate to be meaningful. That guard had to defeat the
	 * ordered walk's early exit, which passes identically with the bound removed; an
	 * aggregate has no early exit, so the window is the only thing deciding how many
	 * partitions it reads.
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
	 * The chart costs more than the page it sits above — that is inherent, it reads a
	 * window where the list reads a page — but it must stay within an order of
	 * magnitude of it, which is what the covering index buys. Before {@code V14} the
	 * ratio was ~320x at benchmark scale.
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
