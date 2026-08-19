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
 * Performance guards for the Performance leaderboard (#159). Baselines measured
 * 2026-08-19 against {@link TelemetrySeeder.Scale#GUARD}: 8 004 transactions in 433
 * Transaction Groups over nine weekly partitions, where a full scan of {@code txn}
 * costs 2 715 blocks.
 *
 * <p>This is the <b>second</b> query in the codebase whose cost is O(matching rows)
 * rather than O(page), after the log timeline {@link LogTimelinePerformanceTest}
 * guards — and it is the more dangerous of the two, because an ordered-set aggregate
 * cannot be hashed. {@code percentile_cont} disqualifies hashed grouping outright, so
 * Postgres must deliver the input sorted by (project_id, name, op); given any index
 * that does not already produce that order it sorts the whole window, and past
 * {@code work_mem} that sort goes to disk. {@code V15} exists to make the sort
 * unnecessary, and the numbers that decided its column order are in the migration.
 *
 * <pre>
 *   shape                     blocks
 *   30d (the cap)                673    index-only, no sequential scan
 *   14d (the product default)    448
 *   30d, one project             313
 *   30d, one environment         613
 *   30d, project + environment   313
 *   1h                           147
 * </pre>
 *
 * <p><b>What each assertion here can and cannot catch.</b> Dropping {@code V15} is
 * caught twice over: the planner falls straight back to sequentially scanning every
 * in-window partition, which {@link #noShapeSequentiallyScansTheTransactionTable}
 * rejects, and the 30-day shape goes 673 -> 4 626 blocks, which is over the ceiling.
 *
 * <p><b>The spill assertion is the weak one, and deliberately kept anyway.</b> At
 * 8 004 rows the input sort fits in {@code work_mem} whether or not {@code V15} is
 * present — measured 0 temp blocks both ways — so
 * {@link #theAggregateDoesNotSpill} cannot fail here for the reason it exists. That
 * reason is visible only at volume: at 500 004 transactions the same 30-day request
 * writes 21 704 temp blocks on every filter-leading index and 0 on this one. It is
 * asserted regardless because it is free, it fails on a regression that makes the
 * grouping itself larger (a key that stopped being (project, name, op) would), and a
 * guard that is silent about its own blind spot is worse than one that names it —
 * the same limit {@code LogTimelinePerformanceTest} records for its index-only claim.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionGroupPerformanceTest {

	/**
	 * The 30-day cap is the widest window the endpoint answers, and it measured 673
	 * blocks. 2 000 is 3x that rather than the standard 10x, because 10x would be
	 * 6 730 — above the 2 715 a full scan of {@code txn} costs, therefore unable to
	 * fail. It still sits well under the 4 626 the same request costs with
	 * {@code V15} dropped, which is the regression it is here to catch.
	 */
	private static final long MAX_LEADERBOARD_BLOCKS = 2_000;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	TelemetrySeeder.Seeded seeded;

	@BeforeAll
	void seed() {
		seeded = new TelemetrySeeder(jdbc, partitions).seed(TelemetrySeeder.Scale.GUARD);
	}

	/** One leaderboard the UI can ask for, at the window the global filter resolved to. */
	private record Shape(String name, List<Long> project, List<String> environment, int days) {
	}

	/**
	 * The leaderboards the Performance page can produce. {@code range=all} is absent
	 * because the endpoint does not answer it — ADR-0015 caps the window at 30 days,
	 * so the widest shape here <em>is</em> the widest shape in production.
	 */
	private List<Shape> uiShapes() {
		List<Long> oneProject = List.of(seeded.projectId());
		List<String> oneEnvironment = List.of(seeded.environment());
		return List.of(new Shape("30d (the cap)", null, null, 30), new Shape("14d (the default)", null, null, 14),
				new Shape("30d, one project", oneProject, null, 30),
				new Shape("30d, one environment", null, oneEnvironment, 30),
				new Shape("30d, project + environment", oneProject, oneEnvironment, 30),
				new Shape("1h", null, null, 0));
	}

	/**
	 * No shape reads a populated partition end to end.
	 *
	 * <p>This is the assertion {@link LogTimelinePerformanceTest} explains it cannot
	 * make, and the difference is the fixture rather than the query: {@code txn} rows
	 * carry a ~1.4 KB payload, so even a small partition is far more expensive to read
	 * than the 96-byte index entries {@code V15} covers it with, and the planner
	 * prefers the index at every size this guard sees.
	 */
	@Test
	void noShapeSequentiallyScansTheTransactionTable() {
		for (Shape shape : uiShapes()) {
			PlanFacts facts = leaderboard(shape).explain(jdbc);

			QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, "leaderboard — " + shape.name());
		}
	}

	/** And costs a bounded amount while doing it, at every window the picker can reach. */
	@Test
	void everyShapeStaysUnderItsCeiling() {
		for (Shape shape : uiShapes()) {
			PlanFacts facts = leaderboard(shape).explain(jdbc);

			QueryGuard.assertUnderCeiling(facts, MAX_LEADERBOARD_BLOCKS, "leaderboard — " + shape.name());
		}
		QueryGuard.assertCeilingCanFail(jdbc, MAX_LEADERBOARD_BLOCKS, "txn");
	}

	/**
	 * The window is the only thing bounding this query, so it has to prune — and the
	 * result has to be non-empty, or "read no partitions" would satisfy the assertion
	 * for the wrong reason.
	 */
	@Test
	void theLeaderboardPrunesToItsWindow() {
		Shape widest = uiShapes().get(0);
		QueryPlans.Built built = leaderboard(widest);

		PlanFacts facts = built.explain(jdbc);

		assertThat(built.rows(jdbc)).as("Transaction Groups returned — pruning to nothing proves nothing").isNotEmpty();
		QueryGuard.assertPrunesFrom(jdbc, facts, "txn", window(widest).from(), "a 30-day leaderboard");
	}

	/**
	 * Nothing spills. See the class javadoc for what this can and cannot catch at
	 * guard scale — the fact it guards is real, its ability to fail here is not.
	 */
	@Test
	void theAggregateDoesNotSpill() {
		for (Shape shape : uiShapes()) {
			PlanFacts facts = leaderboard(shape).explain(jdbc);

			QueryGuard.assertNoTempFiles(facts, "leaderboard — " + shape.name());
		}
	}

	/**
	 * No subquery is evaluated per output row. Cost cannot express this — an index
	 * makes 100 probes cheap enough to hide under any ceiling a fixture can honestly
	 * set — and it is the shape behind both #130 and the trace-search regression, so
	 * it is asserted directly.
	 */
	@Test
	void nothingIsEvaluatedOncePerGroup() {
		for (Shape shape : uiShapes()) {
			PlanFacts facts = leaderboard(shape).explain(jdbc);

			assertThat(facts.correlatedSubplans())
				.as("subplans re-run per output row by leaderboard — %s%n%s", shape.name(), facts.plan())
				.isEmpty();
		}
	}

	private QueryPlans.Built leaderboard(Shape shape) {
		TransactionGroupController.Window window = window(shape);
		return QueryPlans.transactionGroups(shape.project(), shape.environment(), window.from(), window.to());
	}

	/** Resolved by the controller, so the guard explains the window the endpoint would run. */
	private TransactionGroupController.Window window(Shape shape) {
		Instant now = Instant.now();
		Instant from = shape.days() > 0 ? now.minus(shape.days(), ChronoUnit.DAYS) : now.minus(1, ChronoUnit.HOURS);
		return QueryPlans.transactionGroupWindow(from, now);
	}

}
