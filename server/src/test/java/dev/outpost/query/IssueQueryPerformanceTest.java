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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Performance guards for the issue list, its 14-day sparkline, distinct-user
 * count, and environment rollup: EXPLAINs the controller's own SQL through
 * {@link QueryPlans} and asserts on logical I/O and plan shape only, sending
 * the status and time range the UI always sends (see {@link #uiList}).
 * See {@link QueryGuard} for how ceilings are calibrated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueQueryPerformanceTest {

	/*
	 * No buffer ceiling here: `issue` full-scans in ~23 blocks vs ~78 for the
	 * healthy plan, so any ceiling above the healthy plan already sits above the
	 * scan and can't fail. Plan shape is guarded instead, in issueListSortIsIndexSupported.
	 */

	/** Healthy is ~9 470 blocks. Catches the time bound going missing (costs ~20 045 without it). */
	private static final long MAX_SPARKLINE_BLOCKS = 14_000;

	/** The target once bounded (#131 — today this path has no time bound and costs ~20 040). */
	private static final long MAX_USERS_AFFECTED_BLOCKS = MAX_SPARKLINE_BLOCKS;

	/** Page 1 and page N differ by the keyset predicate alone, so a small constant covers the noise. */
	private static final int DEEP_PAGE_TOLERANCE = 4;

	/** What {@code ui/src/app/pages/issues/issues.ts} sends when the user has picked nothing. */
	private static final String DEFAULT_STATUS = "unresolved";

	/** Both tabs the UI offers. */
	private static final List<String> STATUSES = List.of("unresolved", "resolved");

	/** Both orderings the list offers, as {@code IssueController.issuePage} reads them. */
	private static final List<String> SORTS = List.of("last_seen", "count");

	/** The default range in {@code ui/src/app/core/filters.ts}, which arrives as a {@code last_seen} bound. */
	private static final int UI_RANGE_DAYS = 14;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	@Autowired
	PlatformTransactionManager transactions;

	TelemetrySeeder.Seeded seeded;

	@BeforeAll
	void seed() {
		seeded = new TelemetrySeeder(jdbc, partitions).seed(TelemetrySeeder.Scale.GUARD);
	}

	/**
	 * Keyset pagination's promise is that page 50 costs what page 1 costs, so the
	 * ratio — not an absolute number — is what this asserts. The cursor is walked
	 * rather than synthesized.
	 */
	@Test
	void deepPageCostsWhatPageOneCosts() {
		long pageOneBlocks = pageOne().explain(jdbc).logicalIo();
		String cursor = QueryPlans.issueCursorAtPage(jdbc, "last_seen", 3);

		PlanFacts deep = uiList(null, DEFAULT_STATUS, "last_seen", cursor).explain(jdbc);

		assertThat(deep.logicalIo()).as("blocks for a deep issue page against the %d page 1 costs%n%s", pageOneBlocks,
				deep.plan()).isLessThanOrEqualTo(DEEP_PAGE_TOLERANCE * pageOneBlocks);
	}

	/**
	 * The list orders by {@code (last_seen, id)} or {@code (event_count, id)}; a
	 * {@code Sort} node means an index isn't covering the ordering, which holds at
	 * any dataset size unlike a block-count ceiling. Also asserts no temp file,
	 * the other thing a page-sized result should never need.
	 */
	@Test
	void issueListSortIsIndexSupported() {
		for (String sort : SORTS) {
			PlanFacts facts = uiList(null, DEFAULT_STATUS, sort, null).explain(jdbc);

			assertDoesNotSort("status=" + DEFAULT_STATUS + "&sort=" + sort, facts);
			QueryGuard.assertNoTempFiles(facts, "the issue list, sort=" + sort);
		}
	}

	/**
	 * Every UI shape — {unresolved, resolved} x {global, project-scoped}, each in
	 * two sort orders — against the index meant to serve it, named explicitly
	 * because "some index was used" would still pass on a global-index fallback.
	 * At guard scale a table scan can beat an index walk, so the sort is priced
	 * out of reach (see {@link #explainWithoutSort}) to isolate the structural claim.
	 */
	@Test
	void everyIssueListShapeWalksItsOwnIndex() {
		List<Long> project = List.of(seeded.projectId());

		for (String status : STATUSES) {
			assertWalks("status=" + status, explainWithoutSort(uiList(null, status, "last_seen", null)),
					"idx_issue_status_last_seen_id");
			assertWalks("status=" + status + "&sort=count", explainWithoutSort(uiList(null, status, "count", null)),
					"idx_issue_status_event_count_id");
			assertWalks("status=" + status + "&project=", explainWithoutSort(uiList(project, status, "last_seen", null)),
					"idx_issue_project_status_last_seen_id");
			assertWalks("status=" + status + "&project=&sort=count",
					explainWithoutSort(uiList(project, status, "count", null)),
					"idx_issue_project_status_event_count_id");
		}
	}

	private static void assertDoesNotSort(String shape, PlanFacts facts) {
		assertThat(facts.ran("Sort"))
			.as("issue list %s sorts the whole table rather than walking an index%n%s", shape, facts.plan())
			.isFalse();
	}

	/**
	 * Served by {@code index}, and served in order: a bitmap scan of the same
	 * index would satisfy the first but still hand rows back in heap order to be
	 * sorted.
	 */
	private static void assertWalks(String shape, PlanFacts facts, String index) {
		assertThat(facts.indexesUsed())
			.as("indexes read for issue list %s, with the sort priced out%n%s", shape, facts.plan())
			.contains(index);
		assertDoesNotSort(shape + " (with the sort priced out)", facts);
	}

	/**
	 * Prices sorting out of reach rather than forbidding it (disabling sequential
	 * scans would just make Postgres fall back to a bitmap scan that still sorts).
	 * {@code SET LOCAL} runs inside a rolled-back transaction so the setting
	 * doesn't leak to the next test on the same pooled connection.
	 */
	private PlanFacts explainWithoutSort(QueryPlans.Built built) {
		TransactionTemplate transaction = new TransactionTemplate(transactions);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return transaction.execute(status -> {
			jdbc.sql("SET LOCAL enable_sort = off").update();
			status.setRollbackOnly();
			return built.explain(jdbc);
		});
	}

	/**
	 * The sparkline is bounded to the last 14 days, so it must read only the weekly
	 * partitions from that week onwards — never the whole retention span.
	 */
	@Test
	void sparklinePrunesToItsWindow() {
		Instant since = QueryPlans.sparklineSince();
		PlanFacts facts = QueryPlans.sparkline(pageIds(), since).explain(jdbc);

		QueryGuard.assertPrunesFrom(jdbc, facts, "event", since, "the issue-list sparkline");
		QueryGuard.assertUnderCeiling(facts, MAX_SPARKLINE_BLOCKS, "the issue-list sparkline");
		QueryGuard.assertNoTempFiles(facts, "the issue-list sparkline");
		QueryGuard.assertCeilingCanFail(jdbc, MAX_SPARKLINE_BLOCKS, "event");
	}

	/**
	 * {@code count(DISTINCT user_ident)} for the page's issues, with no time bound,
	 * on every page load — cost grows with retention, not with the page.
	 */
	@Test
	@Disabled("#131 — users-affected has no time bound and reads every partition")
	void usersAffectedIsTimeBounded() {
		PlanFacts facts = QueryPlans.usersAffected(pageIds()).explain(jdbc);

		QueryGuard.assertPrunesFrom(jdbc, facts, "event", QueryPlans.sparklineSince(),
				"the issue-list users-affected aggregate");
		QueryGuard.assertUnderCeiling(facts, MAX_USERS_AFFECTED_BLOCKS, "the issue-list users-affected aggregate");
		QueryGuard.assertCeilingCanFail(jdbc, MAX_USERS_AFFECTED_BLOCKS, "event");
	}

	/**
	 * Release and environment filtering both answer "does this Issue have Events
	 * carrying one value" from low-volume rollups rather than the partitioned
	 * {@code event} table. No buffer ceiling: any ceiling clearing the healthy
	 * plan (~217 blocks) already sits above the ~30 a full scan of both rollup
	 * tables costs.
	 */
	@Test
	void releaseFilterCostsWhatTheEnvironmentFilterCosts() {
		PlanFacts facts = QueryPlans.issueList(null, null, null, seeded.release(), null, null, null, "last_seen", null)
			.explain(jdbc);

		assertThat(facts.relationsScanned()).as("relations read by the issue-list release filter%n%s", facts.plan())
			.noneMatch(relation -> relation.startsWith("event"));
	}

	/** Environment filtering goes through {@code issue_env_stats}, so it must not touch {@code event} at all. */
	@Test
	void environmentFilterStaysOffTheEventTable() {
		PlanFacts facts = QueryPlans
			.issueList(null, List.of(seeded.environment()), null, null, null, null, null, "last_seen", null)
			.explain(jdbc);

		assertThat(facts.relationsScanned()).as("relations read by the issue-list environment filter%n%s", facts.plan())
			.noneMatch(relation -> relation.startsWith("event"));
	}

	/**
	 * A list request shaped the way the UI sends one: {@code status} defaults to
	 * {@link #DEFAULT_STATUS} ({@code issues.ts}) and the range defaults to
	 * {@link #UI_RANGE_DAYS} days ({@code filters.ts}), arriving as a
	 * {@code last_seen} lower bound.
	 */
	private QueryPlans.Built uiList(List<Long> project, String status, String sort, String cursor) {
		Instant from = Instant.now().minus(UI_RANGE_DAYS, ChronoUnit.DAYS);
		return QueryPlans.issueList(project, null, status, null, from, null, null, sort, cursor);
	}

	private QueryPlans.Built pageOne() {
		return uiList(null, DEFAULT_STATUS, "last_seen", null);
	}

	private List<Long> pageIds() {
		return QueryPlans.issueIdsOnPage(jdbc, pageOne());
	}

}
