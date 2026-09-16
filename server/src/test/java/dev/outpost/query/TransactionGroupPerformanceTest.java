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
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Performance guards for the Performance leaderboard (#159, #160, #161), the
 * Transaction Group detail view it opens into (#162), and the duration trend on
 * that view (#163). The leaderboard's percentile ranking is an ordered-set
 * aggregate, which cannot be hashed, so Postgres must receive rows pre-sorted by
 * (project_id, name, op) or spill the whole window to disk — {@code V15} exists to
 * make that unnecessary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionGroupPerformanceTest {

	/**
	 * The most expensive 30-day leaderboard shape (a percentile ranking) measures
	 * ~855 blocks; this ceiling sits well under the ~4 600 the same request costs
	 * with {@code V15} dropped, or with a Release filter and {@code V16} dropped, but
	 * below the full-scan cost of {@code txn} so it can still fail. One ceiling
	 * covers both the ranked list and the cardinality count, since both read the same
	 * span of the same index.
	 */
	private static final long MAX_LEADERBOARD_BLOCKS = 2_000;

	/**
	 * The detail view (#162) and trend (#163) bind the whole Transaction Group key to
	 * constants and measure ~42-60 blocks, against 713 for the list over the same 30
	 * days; 200 rather than a tighter multiple of 42 because a session's first
	 * {@code EXPLAIN} touches ~65 blocks more than every later one. It cannot catch
	 * {@link #everyPredicateOnTheDetailViewIsAnIndexCondition}'s regression, which
	 * costs nothing at this scale and is asserted on the plan instead.
	 */
	private static final long MAX_DETAIL_BLOCKS = 200;

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
	 * The leaderboards the Performance page can produce, at the widest window since
	 * ADR-0015 caps it at 30 days; sorts come from the controller's own whitelist so a
	 * new ranking is covered automatically. The two filter shapes are the ones that can
	 * change the scan — a Release filter reads a column {@code idx_txn_performance}
	 * carries only via {@code V16}'s INCLUDE list.
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
	 * No shape reads a populated partition end to end. {@code txn} rows carry a
	 * ~1.4 KB payload, far more expensive to read than the 96-byte index entries
	 * {@code V15} covers it with, so the planner prefers the index at every size this
	 * guard sees.
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
	 * Nothing spills. At this fixture's scale the sort fits in {@code work_mem}
	 * whether or not {@code V15} is present, so this assertion cannot fail here — the
	 * regression it guards against only shows at higher row counts.
	 */
	@Test
	void theAggregateDoesNotSpill() {
		for (Shape shape : uiShapes()) {
			PlanFacts facts = leaderboard(shape).explain(jdbc);

			QueryGuard.assertNoTempFiles(facts, "leaderboard — " + shape.name());
		}
	}

	/**
	 * No subquery is evaluated per output row (#130). Cost cannot express this, since
	 * an index makes 100 probes cheap enough to hide under any ceiling a fixture can
	 * honestly set, so it is asserted directly.
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
	 * Both halves matter to every other assertion in this class: below the minimum
	 * sample count the guards would be explaining a query that returns nothing, and
	 * "reads no blocks" passes a ceiling for the wrong reason.
	 */
	@Test
	void theFixtureStillFillsTheLimitOnceTheSampleFloorApplies() {
		Shape widest = uiShapes().get(0);

		int ranked = leaderboard(widest).rows(jdbc).size();
		long distinct = (long) cardinality(widest).rows(jdbc).get(0).get("count");

		// The statement stops at MAX_GROUPS + 1, so this asserts the list came back full.
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

	/**
	 * The detail view a leaderboard row opens into (#162) pays the same guards, over
	 * the same window, for both shapes of its op predicate. It is bounded by an
	 * equality on the leading columns of {@code idx_txn_performance} rather than by
	 * the window alone, and both {@code = ?} and {@code IS NULL} are covered since
	 * they are different index conditions and therefore different plans.
	 */
	@Test
	void theDetailViewIsGuardedLikeTheListItOpensFrom() {
		for (Group group : detailGroups()) {
			QueryPlans.Built built = detail(group);
			PlanFacts facts = built.explain(jdbc);
			String what = "detail — " + group.label();

			// A plan explained over a key matching nothing reads nothing, passing everything below for the wrong reason.
			assertThat((Long) built.rows(jdbc).get(0).get("txn_count")).as("Transactions in %s", what)
				.isGreaterThan(0);
			QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, what);
			QueryGuard.assertUnderCeiling(facts, MAX_DETAIL_BLOCKS, what);
			QueryGuard.assertNoTempFiles(facts, what);
			assertThat(facts.correlatedSubplans()).as("subplans re-run per output row by %s%n%s", what, facts.plan())
				.isEmpty();
		}
		QueryGuard.assertCeilingCanFail(jdbc, MAX_DETAIL_BLOCKS, "txn");
	}

	/**
	 * The bucketed trend the detail view returns alongside its statistics (#163) pays
	 * every assertion the leaderboard does, temp blocks included: it groups by
	 * {@code date_bin(start_ts)}, an expression no index can be read in the order of,
	 * so Postgres sorts, and what bounds that sort is the key — one Transaction
	 * Group's Transactions, not the window's. Both op branches are covered, since
	 * {@code = ?} and {@code IS NULL} are different index conditions.
	 */
	@Test
	void theTrendIsGuardedLikeTheStatisticsItAccompanies() {
		for (Group group : detailGroups()) {
			QueryPlans.Built built = trend(group);
			PlanFacts facts = built.explain(jdbc);
			String what = "trend — " + group.label();

			// Explaining a grouping that produced no buckets passes everything below for the wrong reason.
			assertThat(built.rows(jdbc)).as("buckets returned by %s", what).isNotEmpty();
			QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, what);
			QueryGuard.assertUnderCeiling(facts, MAX_DETAIL_BLOCKS, what);
			QueryGuard.assertNoTempFiles(facts, what);
			assertThat(facts.correlatedSubplans()).as("subplans re-run per output row by %s%n%s", what, facts.plan())
				.isEmpty();
		}
	}

	/** And prunes to the same window the statistics above it were computed over. */
	@Test
	void theTrendPrunesToTheSameWindowAsTheStatistics() {
		QueryPlans.Built built = trend(detailGroups().get(0));

		PlanFacts facts = built.explain(jdbc);

		assertThat(built.rows(jdbc)).as("buckets returned — pruning to nothing proves nothing").isNotEmpty();
		QueryGuard.assertPrunesFrom(jdbc, facts, "txn", window(uiShapes().get(0)).from(), "a 30-day trend");
	}

	/**
	 * Every predicate the detail view carries is an index <em>condition</em>, not a
	 * {@code Filter} the scan re-checks per row — asserted on the plan because a
	 * non-indexable op predicate (e.g. {@code op IS NOT DISTINCT FROM ?}) costs the
	 * same as the indexable form at this fixture's scale. These shapes carry no
	 * Release filter deliberately: {@code release} is a legitimate {@code Filter}
	 * inside the index-only scan since it is an INCLUDE column, and adding one here
	 * would fail this assertion for a reason that is not a regression.
	 */
	@Test
	void everyPredicateOnTheDetailViewIsAnIndexCondition() {
		for (Group group : detailGroups()) {
			// The trend seeks the same way: `date_bin` appears in its grouping, not its WHERE.
			for (Map.Entry<String, QueryPlans.Built> statement : Map.of("detail", detail(group), "trend",
					trend(group))
				.entrySet()) {
				PlanFacts facts = statement.getValue().explain(jdbc);

				// The JSON key, quoted: "Rows Removed by Filter" contains the bare word too.
				assertThat(facts.plan())
					.as("%s — %s re-checks a predicate per row rather than seeking on it%n%s", statement.getKey(),
							group.label(), facts.plan())
					.doesNotContain("\"Filter\"");
			}
		}
	}

	/** And prunes to its window, which is the only other thing bounding it. */
	@Test
	void theDetailViewPrunesToItsWindow() {
		Group group = detailGroups().get(0);

		PlanFacts facts = detail(group).explain(jdbc);

		QueryGuard.assertPrunesFrom(jdbc, facts, "txn", window(uiShapes().get(0)).from(), "a 30-day detail view");
	}

	/** One Transaction Group key, as the detail view is asked for it. */
	private record Group(String label, long project, String name, String op) {
	}

	/**
	 * The two groups whose detail the guard explains: the one holding the most
	 * Transactions in the fixture, and one whose op is null. Read from the fixture
	 * rather than named here, since the seeder builds its names in SQL and a constant
	 * restating them would drift the day it changed.
	 */
	private List<Group> detailGroups() {
		return List.of(busiestGroup("busiest group", "op IS NOT NULL"), busiestGroup("null op", "op IS NULL"));
	}

	private Group busiestGroup(String label, String where) {
		return jdbc.sql("""
				SELECT project_id, name, op FROM txn WHERE %s
				GROUP BY project_id, name, op ORDER BY count(*) DESC, project_id, name LIMIT 1
				""".formatted(where))
			.query((rs, i) -> new Group(label, rs.getLong("project_id"), rs.getString("name"), rs.getString("op")))
			.single();
	}

	private QueryPlans.Built detail(Group group) {
		TransactionGroupController.Window window = window(uiShapes().get(0));
		return QueryPlans.transactionGroupDetail(group.project(), group.name(), group.op(), null, null, window.from(),
				window.to());
	}

	private QueryPlans.Built trend(Group group) {
		TransactionGroupController.Window window = window(uiShapes().get(0));
		return QueryPlans.transactionGroupTrend(group.project(), group.name(), group.op(), null, null, window.from(),
				window.to());
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
