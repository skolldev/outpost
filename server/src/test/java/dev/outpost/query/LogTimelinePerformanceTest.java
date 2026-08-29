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
 * Performance guards for the log timeline (#141). Baselines re-measured 2026-08-29
 * against {@link TelemetrySeeder.Scale#GUARD}: 40 010 log records over 10 weekly
 * partitions, where a full scan of {@code log_record} costs ~5 045 blocks.
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
 * over six reseeds on 2026-08-29, against the ~410 blocks the log list beside it costs.
 * Every one of them is index-only on every populated partition:
 *
 * <pre>
 *   1h                       253 –   281
 *   14d, one project         318 –   363
 *   All time, one project    591 –   645
 *   14d, one environment     747 –   797
 *   14d (the default)        756 –   807
 *   All time               1 140 – 1 203
 * </pre>
 *
 * <p><b>Why those ranges are narrow, and what used to make them wide (#185).</b> This
 * class flaked for months, on unchanged code and a dataset whose row counts move by
 * well under a percent between seeds. The cause was not the data: at Postgres's
 * shipped {@code random_page_cost = 4.0} the two candidate plans for the densest
 * weekly partition price within a percent of each other — ~600 heap blocks read
 * sequentially against ~140 index blocks charged at 4x apiece — and {@code VACUUM
 * ANALYZE}'s random sample was enough to flip the winner. Roughly three runs in eight
 * lost the covering index on that one partition, which doubled the 14-day chart's cost
 * and sent the 1h chart through {@code idx_log_ts_id} and 546 scattered heap blocks.
 * {@link dev.outpost.TestcontainersConfiguration} now pins the SSD-representative 1.1,
 * as {@code docker-compose.yml} does for the database this ships, and the covering
 * index wins every shape by a wide margin. <b>The setting is part of the fixture's
 * meaning</b>: raise it back to 4.0 and these numbers roughly double, non-deterministically.
 *
 * <p><b>What that bought beyond a green build.</b> This class used to record that the
 * timeline being index-only was a benchmark-tier claim only — that at guard scale the
 * All-time chart legitimately mixed index-only scans of the big partitions with
 * sequential scans of the small ones, so the tier could not assert the property
 * {@code V14} was bought for. Under a planner priced for the storage the product runs
 * on, that is no longer true: the only relation any shape reads end to end is the
 * empty partition the manager keeps ahead of the newest row, and
 * {@link #everyTimelineShapeIsIndexOnly} asserts it directly. {@code
 * LogTimelineIndexProbe} still measures the magnitude at 2 000 000 records, where the
 * same shapes go 130 805 to 21 244 blocks (14d) and 250 106 to 40 191 (All time); what
 * is guarded here is the shape, which is the half that survives a change of scale.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogTimelinePerformanceTest {

	private static final int WINDOW_DAYS = 14;

	/**
	 * One ceiling for every shape, at roughly twice the widest measurement (the 1 203
	 * of an All-time chart) and half the ~5 045 a full scan of {@code log_record}
	 * costs — {@link QueryGuard#assertCeilingCanFail} holds it to that second half.
	 * Well under the 3 135 the 14-day query cost before {@code V14} and the 5 066 the
	 * All-time one did, so dropping the covering index still fails it.
	 *
	 * <p>It used to take two constants, because "All time" was a different kind of
	 * query at guard scale — sequentially scanned where every bounded shape was
	 * index-only. Pricing the planner for the product's storage (#185, class javadoc)
	 * made it the same kind of query, only wider, and one ceiling now covers the lot.
	 *
	 * <p>A fixed number rather than one derived from the window's row count, which
	 * #185 raised as an option: the row count is not what moved. Across six reseeds
	 * the widest shape spans 1 140–1 203, ~5 %, and the flake was a 2x plan flip on a
	 * dataset that had not meaningfully changed. A ceiling that tracked the rows would
	 * have followed the noise it was meant to survive and still admitted the flip.
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
	 * No shape reads an index other than the covering one, and no shape reads a
	 * populated partition end to end. Together: every chart the UI can draw is served
	 * index-only, by {@code V14}'s index, on every partition holding data.
	 *
	 * <p>Naming the index is what makes the first half able to fail. "Some index was
	 * used" stays true when the planner falls back to walking {@code idx_log_ts_id} and
	 * fetching {@code level} from the heap for every row it counts — which is the
	 * 130 805-block plan {@code V14} exists to replace, is ordered, and is sort-free.
	 * It is also precisely the plan #185 caught the 1h chart taking, so this is not a
	 * hypothetical failure mode.
	 *
	 * <p>The second half is the one that changed with #185. {@link
	 * QueryGuard#assertWalksIndex} is containment over the indexes <em>touched</em>, so
	 * on its own it stays green for a partition read with no index at all; this class
	 * used to argue that the gap was unavoidable at guard scale, because a weekly
	 * partition holding a few thousand rows was genuinely cheaper to read end to end.
	 * That was an artefact of a planner priced for rotating disks. With {@code
	 * random_page_cost} at the value the product ships, the covering index wins on
	 * every partition that holds rows, and the two halves can be asserted together.
	 *
	 * <p>{@link QueryGuard#assertNoSequentialScanOfTelemetry} still discounts small
	 * partitions, and that is load-bearing rather than a hedge: the partition manager
	 * keeps a week of partitions ahead of the newest row and the seeder leaves one
	 * behind the oldest, and an <em>empty</em> relation is read sequentially by any
	 * planner because there is nothing to index-scan. Discounting it is not a way of
	 * tolerating the failure this guards; every partition with data in it is covered.
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
