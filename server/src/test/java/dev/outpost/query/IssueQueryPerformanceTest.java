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
 * Performance guards for the issue list — the most-visited screen in the product
 * and the one that issues the most queries per page load: the list itself, a
 * 14-day sparkline, a distinct-user count, and the environment rollup.
 *
 * <p>Each guard {@code EXPLAIN}s the controller's own SQL through
 * {@link QueryPlans} — never a copy, which would keep passing after the real
 * query regressed — and asserts on logical I/O and plan shape only. No wall
 * clock. {@link QueryGuard} documents how the ceilings are calibrated and why
 * two of these are {@code @Disabled}.
 *
 * <p><b>The list guards send what the UI sends.</b> Every real page load carries a
 * status and a time range, because both are defaults the user never has to pick —
 * see {@link #uiList}. A guard that left them out would be measuring a request the
 * product cannot make, and #126 shipped once already having indexed exactly that
 * imaginary shape: the Resolved tab was left costing 15x a full table scan while a
 * green guard asserted the list was indexed.
 *
 * <p>Baselines below were measured on 2026-08-01 against
 * {@link TelemetrySeeder.Scale#GUARD}: 40 003 events over 10 weekly partitions,
 * 200 issues — except the list and release-filter figures, re-measured 2026-08-02
 * when #126 indexed the list's orderings. A full scan of {@code event} costs
 * ~15 000 blocks on that dataset, which is the number every ceiling over a
 * telemetry table has to sit below.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueQueryPerformanceTest {

	/*
	 * The list query has no buffer ceiling, deliberately, and QueryGuard's own rule
	 * is why: a ceiling has to sit below the cost of reading the table or it cannot
	 * fail. The list touches only `issue`, which a full scan reads in ~23 blocks at
	 * guard scale, while the healthy indexed plan costs ~78 — fifty random heap
	 * fetches into a ten-block table legitimately cost more than reading the ten
	 * blocks. Every ceiling that clears the healthy plan is therefore already above
	 * the scan, and no honest number exists.
	 *
	 * The ceiling that used to be here validated itself against `event`, which this
	 * query never reads, so it passed vacuously — and could not have caught the
	 * sorting plan it was nominally guarding, which cost 30 blocks. Plan shape is
	 * the guard for this query; see issueListSortIsIndexSupported.
	 */

	/**
	 * Healthy is ~9 470 blocks — high because at guard scale the 14-day window holds
	 * most of the events. 10x would be 94 700, above the ~15 000 a full scan of
	 * {@code event} costs, and a ceiling above the scan cost cannot fail. 14 000 is
	 * what fits: 1.5x headroom for plan drift, still under the scan, and comfortably
	 * under the 20 045 the same aggregate costs once its time bound is removed —
	 * which is the regression this exists to catch.
	 */
	private static final long MAX_SPARKLINE_BLOCKS = 14_000;

	/**
	 * The healthy <em>target</em>, not today's cost: this path is knowingly broken
	 * (#131). The sparkline is the same aggregate over the same 50 issues with a
	 * time bound and passes under 14 000, so a bounded users-affected query should
	 * too. Today it costs ~20 040.
	 */
	private static final long MAX_USERS_AFFECTED_BLOCKS = MAX_SPARKLINE_BLOCKS;

	/**
	 * The target for the release filter (#127), derived from the environment filter:
	 * the same "which issues have signal X" question answered against a rollup costs
	 * ~133 blocks, so 10x that is the class the release filter belongs in. Today it
	 * costs ~2 200–2 800, down from ~11 850 before #126: an ordered index on the
	 * outer scan lets the semi-join stop once the page is full instead of testing
	 * every issue. Closer, but still the wrong order of magnitude, and still #127 —
	 * the {@code EXISTS} remains unbounded and {@code event(release)} still has no
	 * index. The range is the seeder's randomized per-release event spread.
	 */
	private static final long MAX_RELEASE_FILTER_BLOCKS = 1_320;

	/** Page 1 and page N differ by the keyset predicate alone, so a small constant covers the noise. */
	private static final int DEEP_PAGE_TOLERANCE = 4;

	/** What {@code ui/src/app/pages/issues/issues.ts} sends when the user has picked nothing. */
	private static final String DEFAULT_STATUS = "unresolved";

	/** Both tabs the UI offers. The Resolved one is the shape #126 originally missed. */
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

	// -------------------------------------------------------------------- list

	/**
	 * Keyset pagination's whole promise is that page 50 costs what page 1 costs. The
	 * <em>ratio</em> is what survives a change of dataset scale, so that — not an
	 * absolute number — is what this asserts. The cursor is walked rather than
	 * synthesized: it is the journey a user actually takes, and it needs no
	 * production visibility widened to reach it.
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
	 * The default tab, which is what almost every page load is: the list orders by
	 * {@code (last_seen, id)} or {@code (event_count, id)}, and before #126 no index
	 * covered either, so it sorted the whole table on every page. A {@code Sort} node
	 * is the shape that says so, and it says it at any dataset size — which is why
	 * this asserts on the plan rather than on a block count guard scale keeps small.
	 *
	 * <p>It also asserts no temp file, which is the other thing a page-sized result
	 * should never need and the only part of the deleted buffer-ceiling guard that
	 * could ever fail.
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
	 * Every shape the UI can ask for, against the index meant to serve it. The UI
	 * always sends a status and always sends a time range, so the four shapes are
	 * {unresolved, resolved} × {global, project-scoped}, each in two sort orders —
	 * and each has its own index because leaving {@code status} out of them is what
	 * made the Resolved tab cost 15x a full table scan.
	 *
	 * <p>Naming the index is the point. Asserting only the absence of a {@code Sort}
	 * passes with the project-scoped indexes dropped, because Postgres will walk a
	 * global index and apply {@code project_id} as a filter — ordered, sort-free, and
	 * exactly the plan those indexes exist to avoid. "Some index was used" cannot
	 * fail when a redundant index is added; "<em>this</em> index was used" can.
	 *
	 * <p>What it deliberately does not assert is that these are the plans chosen
	 * <em>today</em>. At guard scale three of the four are not: 200 issues live in
	 * ten blocks, and reading ten blocks legitimately beats an index walk. Whether
	 * the crossover has been passed is a question about dataset size; pricing the
	 * sort out of reach isolates the half that is structural.
	 * {@code docs/performance/measuring-retrieval.md}, finding 6, has the numbers.
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
	 * The shape is served by {@code index} and served <em>in order</em> — the second
	 * half matters because a bitmap scan of the same index would satisfy the first
	 * and still hand its rows over in heap order to be sorted.
	 */
	private static void assertWalks(String shape, PlanFacts facts, String index) {
		assertThat(facts.indexesUsed())
			.as("indexes read for issue list %s, with the sort priced out%n%s", shape, facts.plan())
			.contains(index);
		assertDoesNotSort(shape + " (with the sort priced out)", facts);
	}

	/**
	 * {@code EXPLAIN} with sorting priced out of reach — not forbidden, so a query
	 * with no ordered path still plans a {@code Sort} and is still visible as one. It
	 * has to be the sort that is priced out rather than the scan: disabling
	 * sequential scans alone just moves Postgres onto a bitmap scan of the
	 * {@code (project_id, fingerprint)} unique index, which returns rows in heap
	 * order and sorts them anyway.
	 *
	 * <p>{@code SET LOCAL} inside a rolled-back transaction so the setting reverts
	 * with it: a session {@code SET} would leak to whichever test next drew the same
	 * pooled connection.
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

	// -------------------------------------------------------------- aggregates

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
	 * {@code count(DISTINCT user_ident)} for the page's 50 issues, with <b>no time
	 * bound</b>, on every issue-list page load. Every partition ever created is
	 * read, so the cost grows with retention rather than with the page.
	 *
	 * <p>The pruning assertion comes first deliberately: it is the one that states
	 * the defect, and it holds at any dataset size. The ceiling behind it is the
	 * sparkline's — the same aggregate over the same issues, bounded.
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

	// ----------------------------------------------------------------- filters

	/**
	 * The release filter is {@code EXISTS (SELECT 1 FROM event e WHERE e.issue_id =
	 * issue.id AND e.release = ?)} — unbounded, and with no {@code event(release)}
	 * index to serve it. The environment filter is the same question answered
	 * against the {@code issue_env_stats} rollup, and it is two orders of magnitude
	 * cheaper; that gap is the finding.
	 */
	@Test
	@Disabled("#127 — no event(release) index and no time bound on the issue release filter")
	void releaseFilterCostsWhatTheEnvironmentFilterCosts() {
		PlanFacts facts = QueryPlans.issueList(null, null, null, seeded.release(), null, null, null, "last_seen", null)
			.explain(jdbc);

		QueryGuard.assertUnderCeiling(facts, MAX_RELEASE_FILTER_BLOCKS, "the issue-list release filter");
		QueryGuard.assertCeilingCanFail(jdbc, MAX_RELEASE_FILTER_BLOCKS, "event");
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

	// ----------------------------------------------------------------- helpers

	/**
	 * A list request shaped the way the UI sends one. Both filters are defaults the
	 * user never has to pick, so a guard that omitted them would measure a query the
	 * product cannot produce: {@code issues.ts} defaults {@code status} to
	 * {@link #DEFAULT_STATUS}, and {@code filters.ts} defaults the range to
	 * {@link #UI_RANGE_DAYS} days, which reaches the server as a {@code last_seen}
	 * lower bound.
	 *
	 * <p>Of the two it is {@code status} that changes the plan — it is an equality
	 * predicate no index covered before #126, and the Resolved tab is the shape that
	 * exposed it. The range bound is a range start on the same index and would be
	 * harmless to omit; it is here because sending one and not the other would be a
	 * shape nothing produces either.
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
