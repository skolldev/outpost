package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import dev.outpost.support.PlanFacts;
import dev.outpost.support.TelemetrySeeder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Performance guards for the Performance leaderboard (#159, #160, #161). Baselines
 * measured 2026-08-19 against {@link TelemetrySeeder.Scale#GUARD}: 8 004 transactions in
 * 433 Transaction Groups over nine weekly partitions, where a full scan of {@code txn}
 * costs 2 691 blocks.
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
 * <p>One request runs <b>two</b> statements over the same window: the ranked list, and
 * the distinct-Transaction-Group count the cardinality warning reads (#160). They are
 * separate because the count has to be taken before the minimum-sample floor — the
 * Project it warns about is the one whose groups the floor removes — and folding that
 * into one statement means a twice-referenced CTE spooling every group into a
 * tuplestore, which spills for the same reason the sort {@code V15} removed did. The
 * page's cost is therefore the sum of the two columns below, and guarding only the
 * first would understate it by nearly half.
 *
 * <pre>
 *   shape                       list   count
 *   30d (the cap)                713     571    index-only, no sequential scan
 *   14d (the product default)    508     407
 *   30d, one project             378     303
 *   30d, one environment         713     571
 *   30d, project + environment   378     303
 *   1h                           167     126
 *   30d, one release             713     571    V16 put `release` in the index
 *   30d, name search             713     571
 *   30d, release + name search   713     571
 *   30d, sort=p95 / sort=p50     855     571
 *   30d, sort=count              713     571
 * </pre>
 *
 * <p>The count is cheaper than the list on every shape without being cheap: it reads no
 * {@code duration_ms} and builds no per-group tuplesort, but it still walks the same
 * span of {@code idx_txn_performance}, and nothing about a leaderboard's cost is
 * bounded by how few groups come back. It is also flat across the filters and every
 * sort, which is the point: they narrow or reorder what the same index scan produces,
 * they do not change what it has to read.
 *
 * <p><b>Neither filter costs anything, and one of them used to cost everything.</b> A
 * name search is a predicate on a column the index leads with. A Release filter is a
 * predicate on a column {@code V15} did not carry at all, which took the whole query off
 * the index and onto the heap — 4 614 blocks for the list and 3 067 for the count, both
 * by sequentially scanning every in-window partition. {@code V16} put {@code release} in
 * the index's INCLUDE list, and the shape is here so that removing it fails this class
 * rather than a user's page.
 *
 * <p><b>The percentile sorts cost ~140 blocks more than the rest, and that is the whole
 * price of the feature.</b> Ordering by {@code total_ms} lets Postgres flatten the
 * subquery the statement wraps its aggregate in; ordering by a percentile subscript
 * cannot be flattened, so a subquery-scan node stays and its output is fed to a top-N
 * heapsort. The aggregate underneath is identical — same index-only scans, same merge
 * append, same single ordered-set aggregate — which is why every sort reads the same
 * groups for the same count of blocks below that node.
 *
 * <p><b>What each assertion here can and cannot catch.</b> Dropping {@code V15} is
 * caught twice over: the planner falls straight back to sequentially scanning every
 * in-window partition, which {@link #noShapeSequentiallyScansTheTransactionTable}
 * rejects, and the 30-day shape goes 713 -> ~4 600 blocks, which is over the ceiling.
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
	 * The widest window the endpoint answers is 30 days, and the most expensive thing
	 * it can be asked for over that window is a percentile ranking: 855 blocks for the
	 * list and 571 for the count. 2 000 is ~2.3x that rather than the standard 10x,
	 * because 10x would be above the 2 691 a full scan of {@code txn} costs and
	 * therefore unable to fail. It still sits well under the ~4 600 the same request
	 * costs with {@code V15} dropped, or with a Release filter and {@code V16}
	 * dropped, which are the two regressions it is here to catch.
	 *
	 * <p>One ceiling covers both statements rather than a tighter one each: they read
	 * the same span of the same index for the same reasons, so a regression that lifts
	 * one lifts the other, and a second constant would only be a second thing to
	 * re-calibrate.
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
	private record Shape(String name, List<Long> project, List<String> environment, String release, String query,
			String sort, int days) {

		static Shape of(String name, List<Long> project, List<String> environment, int days) {
			return new Shape(name, project, environment, null, null, "total_ms", days);
		}

		Shape sortedBy(String sort) {
			return new Shape(name + ", sort=" + sort, project, environment, release, query, sort, days);
		}

		Shape filteredBy(String label, String release, String query) {
			return new Shape(name + ", " + label, project, environment, release, query, sort, days);
		}
	}

	/**
	 * The leaderboards the Performance page can produce. {@code range=all} is absent
	 * because the endpoint does not answer it — ADR-0015 caps the window at 30 days,
	 * so the widest shape here <em>is</em> the widest shape in production.
	 *
	 * <p>The sorts are enumerated from the controller's own whitelist rather than
	 * listed here, so a fifth ranking cannot be added without this guard explaining
	 * its plan. They are applied at the widest window because that is where an
	 * ordering that stopped being a bounded top-N would show: sorting by a percentile
	 * ranks the same groups the default does, over the same aggregate, and the only
	 * thing that can differ is what happens above it.
	 *
	 * <p>The two filter shapes are here because they are the two that can change the
	 * <em>scan</em>. A name search stays inside {@code idx_txn_performance}, which
	 * covers {@code name}; a Release filter reads a column that index does not carry
	 * — {@code V16} is what keeps it index-only, and dropping that column from the
	 * INCLUDE list sends this shape to the heap for every Transaction in the window.
	 */
	private List<Shape> uiShapes() {
		List<Long> oneProject = List.of(seeded.projectId());
		List<String> oneEnvironment = List.of(seeded.environment());
		Shape widest = Shape.of("30d (the cap)", null, null, 30);
		List<Shape> shapes = new ArrayList<>(List.of(widest, Shape.of("14d (the default)", null, null, 14),
				Shape.of("30d, one project", oneProject, null, 30),
				Shape.of("30d, one environment", null, oneEnvironment, 30),
				Shape.of("30d, project + environment", oneProject, oneEnvironment, 30),
				Shape.of("1h", null, null, 0),
				widest.filteredBy("one release", seeded.release(), null),
				widest.filteredBy("name search", null, "orders"),
				widest.filteredBy("release + name search", seeded.release(), "orders")));
		for (String sort : QueryPlans.transactionGroupSorts()) {
			shapes.add(widest.sortedBy(sort));
		}
		return List.copyOf(shapes);
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

	/**
	 * The floor does not gut the fixture, and the fixture does exercise truncation.
	 *
	 * <p>Both halves matter to every other assertion in this class. A guard whose
	 * groups had fallen below {@link TransactionGroupController} 's minimum sample
	 * count would be explaining a query that returns nothing — and "reads no blocks"
	 * passes a ceiling for the wrong reason. A guard that never filled the limit would
	 * be explaining a plan no busy Installation gets.
	 */
	@Test
	void theFixtureStillFillsTheLimitOnceTheSampleFloorApplies() {
		Shape widest = uiShapes().get(0);

		int ranked = leaderboard(widest).rows(jdbc).size();
		long distinct = (long) cardinality(widest).rows(jdbc).get(0).get("count");

		// The statement stops at MAX_GROUPS + 1, so "more than 100" is the only way it can
		// say "there was more past the limit" — this asserts the list came back full.
		assertThat(ranked).as("ranked groups at the 30-day cap — the floor left too few to certify a plan")
			.isGreaterThan(100);
		assertThat(distinct).as("distinct Transaction Groups in the window").isGreaterThan(100);
	}

	/** The cardinality count reads the same window, and pays the same guards for it. */
	@Test
	void theCardinalityCountIsGuardedLikeTheListItAnnotates() {
		for (Shape shape : uiShapes()) {
			PlanFacts facts = cardinality(shape).explain(jdbc);
			String what = "cardinality — " + shape.name();

			QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, what);
			QueryGuard.assertUnderCeiling(facts, MAX_LEADERBOARD_BLOCKS, what);
			QueryGuard.assertNoTempFiles(facts, what);
			assertThat(facts.correlatedSubplans()).as("subplans re-run per output row by %s%n%s", what, facts.plan())
				.isEmpty();
		}
	}

	private QueryPlans.Built leaderboard(Shape shape) {
		TransactionGroupController.Window window = window(shape);
		return QueryPlans.transactionGroups(shape.project(), shape.environment(), shape.release(), shape.query(),
				shape.sort(), window.from(), window.to());
	}

	private QueryPlans.Built cardinality(Shape shape) {
		TransactionGroupController.Window window = window(shape);
		return QueryPlans.transactionGroupCardinality(shape.project(), shape.environment(), shape.release(),
				shape.query(), window.from(), window.to());
	}

	/** Resolved by the controller, so the guard explains the window the endpoint would run. */
	private TransactionGroupController.Window window(Shape shape) {
		Instant now = Instant.now();
		Instant from = shape.days() > 0 ? now.minus(shape.days(), ChronoUnit.DAYS) : now.minus(1, ChronoUnit.HOURS);
		return QueryPlans.transactionGroupWindow(from, now);
	}

}
