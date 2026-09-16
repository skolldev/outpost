package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import dev.outpost.support.PlanFacts;
import dev.outpost.support.TelemetrySeeder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Performance guards for the seven Tools beyond {@code get_issue_context} (its
 * own guards are in {@code IssueContextPerformanceTest}): none of these write
 * SQL of their own, so what's guarded is the request shape, bound the
 * way the Tool binds it when the caller supplies nothing, reading defaults from
 * {@link QueryPlans} rather than restating them. Everything asserted is logical
 * I/O and plan shape, never wall clock; see {@link QueryGuard} for how a ceiling
 * is calibrated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpToolPerformanceTest {

	/*
	 * find_issues has no buffer ceiling: it touches only `issue`, which a full scan
	 * reads in ~23 blocks while the healthy indexed plan measures 137 — any ceiling
	 * clearing the healthy plan already sits above the scan. See
	 * findIssuesBindsBothPredicatesTheListIndexesLeadWith for the shape guard instead.
	 */

	/**
	 * Healthy is ~411 blocks over the default fourteen-day window. Catches the
	 * window bound going missing — the log list treats {@code from} as optional
	 * since a human always has a range picker set, but without it this Tool would
	 * walk every partition ever created.
	 */
	private static final long MAX_SEARCH_LOGS_BLOCKS = 4_000;

	/**
	 * Healthy is 165-295 blocks per statement; {@code txn} is the smallest of the
	 * four tables this fans out over, so it caps how high the ceiling can sit and
	 * still be able to fail. None of the four carries a time predicate — a Trace ID
	 * is not a time — so each is only ever as cheap as its {@code trace_id} index.
	 */
	private static final long MAX_TRACE_FANOUT_BLOCKS = 2_000;

	/**
	 * Healthy is ~105 blocks: {@code event}'s primary key leads with {@code id}, so
	 * this is one index probe per weekly partition. Catches that probe going
	 * missing and the lookup falling back to reading whole partitions.
	 */
	private static final long MAX_EVENT_RAW_BLOCKS = 1_050;

	/**
	 * Healthy is 518-631 blocks across the four rankings, 415 for the cardinality
	 * count. {@code txn}'s full-scan cost caps how high the ceiling can sit and
	 * still be able to fail.
	 */
	private static final long MAX_PERFORMANCE_BLOCKS = 2_000;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	TelemetrySeeder.Seeded seeded;

	/**
	 * The window the Tools bind when the caller names none, resolved once so every
	 * guard below explains the same request. {@code to} is "now" because that is what
	 * a Tool call with no {@code to} resolves to.
	 */
	Instant to;

	Instant from;

	@BeforeAll
	void seed() {
		seeded = new TelemetrySeeder(jdbc, partitions).seed(TelemetrySeeder.Scale.GUARD);
		to = Instant.now();
		from = to.minus(QueryPlans.toolWindow());
	}

	/**
	 * The two predicates {@code idx_issue_status_last_seen_id} and its
	 * project-scoped sibling lead with. Asserted on the SQL text rather than the
	 * plan, because at guard scale 200 issues live in ten blocks and Postgres reads
	 * them end to end regardless of the predicates, so a plan assertion would pass
	 * even with the time bound deleted.
	 */
	@Test
	void findIssuesBindsBothPredicatesTheListIndexesLeadWith() {
		for (String status : QueryPlans.toolIssueStatuses()) {
			for (String sort : QueryPlans.findIssuesSorts()) {
				String sql = QueryPlans.findIssues(List.of(), null, status, null, from, to, null, sort, null).sql();

				assertThat(sql).as("find_issues, status=%s sort=%s", status, sort)
					.contains("AND status = ?")
					.contains("AND last_seen >= ?");
			}
		}
	}

	/**
	 * Both orderings the Tool offers, not just the default. The loop reads the
	 * Tool's own sort whitelist rather than a list written here, so a new sort
	 * added there is covered on the same commit.
	 */
	@Test
	void findIssuesSortsInMemoryForEveryStatusAndOrderItAnswers() {
		for (String status : QueryPlans.toolIssueStatuses()) {
			for (String sort : QueryPlans.findIssuesSorts()) {
				PlanFacts facts = QueryPlans.findIssues(List.of(), null, status, null, from, to, null, sort, null)
					.explain(jdbc);

				QueryGuard.assertNoTempFiles(facts, "find_issues, status=" + status + " sort=" + sort);
			}
		}
	}

	/**
	 * The default window is the whole reason this Tool can reuse the log list's
	 * statement safely: the list makes {@code from} optional, and an unbounded
	 * ordered walk reads every partition that has ever existed.
	 */
	@Test
	void searchLogsPrunesToTheWindowItDefaultsTo() {
		PlanFacts facts = searchLogs();

		QueryGuard.assertCeilingCanFail(jdbc, MAX_SEARCH_LOGS_BLOCKS, PartitionManager.LOG_RECORD);
		QueryGuard.assertPrunesFrom(jdbc, facts, PartitionManager.LOG_RECORD, from, "search_logs");
		QueryGuard.assertUnderCeiling(facts, MAX_SEARCH_LOGS_BLOCKS, "search_logs");
		QueryGuard.assertNoTempFiles(facts, "search_logs");
		QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, "search_logs");
	}

	/**
	 * The trace filter is the other shape this Tool sends. It walks
	 * {@code idx_log_record_trace} rather than the ordering index, re-asserted here
	 * because the Tool pairs it with a window bound the log page does not send.
	 */
	@Test
	void searchLogsByTraceIdStaysUnderTheSameCeiling() {
		PlanFacts facts = QueryPlans
			.searchLogs(List.of(), null, null, seeded.traceId(), null, null, null, from, to, null)
			.explain(jdbc);

		QueryGuard.assertUnderCeiling(facts, MAX_SEARCH_LOGS_BLOCKS, "search_logs by trace_id");
		QueryGuard.assertNoTempFiles(facts, "search_logs by trace_id");
		QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, "search_logs by trace_id");
	}

	/**
	 * The four statements {@code get_trace} fans out into, each guarded against the
	 * table it reads.
	 */
	@Test
	void everyTraceFanOutStatementStaysUnderItsCeiling() {
		QueryGuard.assertCeilingCanFail(jdbc, MAX_TRACE_FANOUT_BLOCKS, PartitionManager.TXN);

		for (QueryPlans.Built built : QueryPlans.traceDetail(seeded.traceId())) {
			PlanFacts facts = built.explain(jdbc);
			String what = "a get_trace fan-out statement";

			QueryGuard.assertUnderCeiling(facts, MAX_TRACE_FANOUT_BLOCKS, what);
			QueryGuard.assertNoTempFiles(facts, what);
			QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, what);
		}
	}

	/**
	 * One {@code tools/call} costs the four statements together, so the plan
	 * assertions are made on their sum. Ceilings stay per statement, since each is
	 * bounded by a different table.
	 */
	@Test
	void theWholeTraceCallSortsInMemoryAndScansNoTelemetrySequentially() {
		PlanFacts call = QueryPlans.traceDetail(seeded.traceId())
			.stream()
			.map(built -> built.explain(jdbc))
			.reduce(PlanFacts::merge)
			.orElseThrow();

		QueryGuard.assertNoTempFiles(call, "one get_trace call");
		QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, call, "one get_trace call");
	}

	@Test
	void getEventRawIsOneIndexProbePerPartition() {
		PlanFacts facts = eventRaw();

		QueryGuard.assertCeilingCanFail(jdbc, MAX_EVENT_RAW_BLOCKS, PartitionManager.EVENT);
		QueryGuard.assertUnderCeiling(facts, MAX_EVENT_RAW_BLOCKS, "get_event_raw");
		QueryGuard.assertNoTempFiles(facts, "get_event_raw");
		QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, "get_event_raw");
	}

	/**
	 * Every ranking the Tool offers, read from its own whitelist so a ranking added
	 * there is covered on the same commit. The percentile sorts subscript an array
	 * the ordered-set aggregate already produced — a second {@code percentile_cont}
	 * in the {@code ORDER BY} would sort every group's durations twice.
	 */
	@Test
	void everyPerformanceRankingStaysUnderItsCeilingAndPrunesToItsWindow() {
		QueryGuard.assertCeilingCanFail(jdbc, MAX_PERFORMANCE_BLOCKS, PartitionManager.TXN);
		TransactionGroupController.Window window = QueryPlans.performanceWindow(from, to);

		for (String sort : QueryPlans.performanceSorts()) {
			PlanFacts facts = performanceOverview(sort);
			String what = "performance_overview sorted by " + sort;

			QueryGuard.assertUnderCeiling(facts, MAX_PERFORMANCE_BLOCKS, what);
			QueryGuard.assertNoTempFiles(facts, what);
			QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, what);
			QueryGuard.assertPrunesFrom(jdbc, facts, PartitionManager.TXN, window.from(), what);
		}
	}

	/**
	 * The cardinality count is a second pass over the same window, issued on every
	 * call, so the Tool's cost is the two together and guarding only the ranking
	 * would understate it by half.
	 */
	@Test
	void thePerformanceCardinalityCountIsGuardedLikeTheRankingItAnnotates() {
		TransactionGroupController.Window window = QueryPlans.performanceWindow(from, to);
		PlanFacts facts = performanceCardinality();
		String what = "the performance_overview cardinality count";

		QueryGuard.assertUnderCeiling(facts, MAX_PERFORMANCE_BLOCKS, what);
		QueryGuard.assertNoTempFiles(facts, what);
		QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, what);
		QueryGuard.assertPrunesFrom(jdbc, facts, PartitionManager.TXN, window.from(), what);
	}

	/**
	 * The Tool's default window must survive the endpoint's own 30-day cap
	 * unclamped, or every call would report a clamp the caller didn't cause.
	 */
	@Test
	void theDefaultWindowIsInsideThePerformanceViewsOwnCap() {
		assertThat(QueryPlans.performanceWindow(from, to).clamped())
			.as("the Tool's default window is clamped by the Performance view's cap before any caller asks for it")
			.isFalse();
	}

	/**
	 * The one statement {@code find_transactions} issues, in both orderings it
	 * offers — the Tool's own SQL, since the UI's drill-down aggregates a group
	 * and never lists its rows. Healthy is an index range over one group's window
	 * via {@code idx_txn_performance}; catches the key predicates falling off that
	 * index and the lookup reading every group.
	 */
	@Test
	void findTransactionsReadsOneGroupNotTheTable() {
		QueryGuard.assertCeilingCanFail(jdbc, MAX_PERFORMANCE_BLOCKS, PartitionManager.TXN);
		TransactionGroupController.Window window = QueryPlans.performanceWindow(from, to);

		for (String sort : QueryPlans.findTransactionsSorts()) {
			PlanFacts facts = QueryPlans
				.findTransactions(seeded.projectId(), TelemetrySeeder.KNOWN_TRANSACTION_NAME,
						TelemetrySeeder.KNOWN_TRANSACTION_OP, null, null, sort, window.from(), window.to(),
						QueryPlans.findTransactionsDefaultFetch())
				.explain(jdbc);
			String what = "find_transactions sorted by " + sort;

			QueryGuard.assertUnderCeiling(facts, MAX_PERFORMANCE_BLOCKS, what);
			QueryGuard.assertNoTempFiles(facts, what);
			QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, what);
			QueryGuard.assertPrunesFrom(jdbc, facts, PartitionManager.TXN, window.from(), what);
		}
	}

	/**
	 * The parameters {@code uptime_status} takes must reach the database, not just
	 * trim the payload after reading every row. Asserted on the SQL text rather
	 * than the plan, for the reason
	 * {@link #findIssuesBindsBothPredicatesTheListIndexesLeadWith} reads SQL: at
	 * guard scale the whole table is too small for a plan assertion to fail.
	 */
	@Test
	void uptimeStatusNarrowsInTheDatabaseRatherThanInJava() {
		List<QueryPlans.Built> scoped = QueryPlans.uptimeStatus(List.of(seeded.projectId()),
				QueryPlans.uptimeDefaultDays());

		assertThat(scoped).allSatisfy(built -> assertThat(built.sql())
			.as("a uptime_status statement that ignores the Project filter")
			.contains("m.project_id IN ("));
		assertThat(scoped).anySatisfy(built -> assertThat(built.params()).contains(
				QueryPlans.uptimeDefaultDays() - 1));
	}

	/**
	 * No buffer ceiling: {@code uptime_check} isn't a partitioned telemetry table,
	 * so there's no full-scan cost to validate one against, and what can fail
	 * instead is a spill in the rollup's grouping or {@code DISTINCT ON} sort, both
	 * bounded by the window and Project filter. Asserted at the widest history the
	 * Tool will read, not just the default.
	 */
	@Test
	void noUptimeStatusStatementSpillsAtTheWidestHistoryItWillRead() {
		for (int days : new int[] { QueryPlans.uptimeDefaultDays(), QueryPlans.uptimeMaxDays() }) {
			for (QueryPlans.Built built : QueryPlans.uptimeStatus(List.of(), days)) {
				QueryGuard.assertNoTempFiles(built.explain(jdbc), "a uptime_status statement over " + days + " days");
			}
		}
	}

	/** {@code find_issues} as the Tool binds it with nothing supplied but a status. */
	private PlanFacts findIssues(String status) {
		return QueryPlans.findIssues(List.of(), null, status, null, from, to, null, "last_seen", null).explain(jdbc);
	}

	/** {@code search_logs} as the Tool binds it with nothing supplied at all. */
	private PlanFacts searchLogs() {
		return QueryPlans.searchLogs(List.of(), null, null, null, null, null, null, from, to, null).explain(jdbc);
	}

	private PlanFacts eventRaw() {
		return QueryPlans.eventRaw(seeded.eventId()).explain(jdbc);
	}

	private PlanFacts performanceOverview(String sort) {
		TransactionGroupController.Window window = QueryPlans.performanceWindow(from, to);
		return QueryPlans.performanceOverview(List.of(), null, null, null, sort, window.from(), window.to())
			.explain(jdbc);
	}

	private PlanFacts performanceCardinality() {
		TransactionGroupController.Window window = QueryPlans.performanceWindow(from, to);
		return QueryPlans.performanceCardinality(List.of(), null, null, null, window.from(), window.to())
			.explain(jdbc);
	}

}
