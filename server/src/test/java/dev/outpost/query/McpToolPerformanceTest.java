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
 * Performance guards for the seven Tools beyond {@code get_issue_context}, whose
 * own guards are in {@code IssueContextPerformanceTest}.
 *
 * <p>None of these Tools writes SQL — {@code McpToolQueryReuseTest} asserts that
 * outright — so what is guarded here is not a statement but a <b>shape</b>. That
 * is the half of ADR-0016 the reuse rule does not cover: reuse buys the SQL and
 * whatever guard was written for it, but only for requests shaped the way the UI
 * shapes them, and the warning ADR-0016 records is that an agent omits what a
 * user never picks. Every guard below therefore binds what the Tool binds when
 * the caller supplies nothing, reading the defaults from the code that owns them
 * through {@link QueryPlans} rather than restating them — a guard with its own
 * copy of a default would {@code EXPLAIN} a request no Tool can make.
 *
 * <p>Two of these paths had no plan guard before this, only a benchmark: the
 * trace detail fan-out {@code get_trace} reuses, and the row lookup
 * {@code get_event_raw} reuses. They are guarded here because this is the surface
 * on which an unattended caller reaches them.
 *
 * <p>Everything asserted is logical I/O and plan shape, never wall clock.
 * {@link QueryGuard} documents how a ceiling is calibrated and why each one has
 * to sit below the cost of simply reading the table.
 *
 * <p>Baselines measured 2026-08-29 against {@link TelemetrySeeder.Scale#GUARD}:
 * 40 003 events, 40 010 log records, 8 000 transactions and 24 000 spans over 10
 * weekly partitions each, where a full scan costs 15 024 blocks on {@code event},
 * 5 163 on {@code span}, 5 028 on {@code log_record} and 2 688 on {@code txn}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpToolPerformanceTest {

	/*
	 * find_issues has no buffer ceiling, for the reason IssueQueryPerformanceTest
	 * records at length: the statement touches only `issue`, which a full scan reads
	 * in ~23 blocks at guard scale while the healthy indexed plan measures 137 —
	 * fifty random heap fetches into a ten-block table legitimately cost more than
	 * reading the ten blocks. Every ceiling clearing the healthy plan would sit above
	 * the scan and could not fail. What this surface adds to that guard is not a
	 * number but the shape the Tool arrives in; see
	 * findIssuesBindsBothPredicatesTheListIndexesLeadWith.
	 */

	/**
	 * Healthy is ~411 blocks over the default fourteen-day window; 10x is 4 110 and
	 * still under the 5 028 a full scan of {@code log_record} costs, so it can fail.
	 * What it fails on is the window bound going missing — the log list treats
	 * {@code from} as optional because a human always has a range picker set, and
	 * without it this is an ordered walk over every partition ever created.
	 */
	private static final long MAX_SEARCH_LOGS_BLOCKS = 4_000;

	/**
	 * Healthy is 165–295 blocks per statement. Not the standard 10x, which would be
	 * 2 950 and above the 2 688 a full scan of {@code txn} costs: {@code txn} is the
	 * smallest of the four tables this fans out over, so it is the one that decides
	 * whether the ceiling can fail at all. 2 000 is ~6.8x over the measured plan and
	 * still under it.
	 *
	 * <p>None of the four carries a time predicate, because a Trace ID is not a time.
	 * That is a property of the question rather than a defect, and it is why each is
	 * only ever as cheap as its {@code trace_id} index.
	 */
	private static final long MAX_TRACE_FANOUT_BLOCKS = 2_000;

	/**
	 * Healthy is ~105 blocks: {@code event}'s primary key leads with {@code id}, so
	 * this is one index probe per weekly partition. 10x, far below {@code event}'s
	 * 15 024. The regression it catches is that probe going missing and the lookup
	 * falling back to reading partitions.
	 */
	private static final long MAX_EVENT_RAW_BLOCKS = 1_050;

	/**
	 * Healthy is 518–631 blocks across the four rankings, and 415 for the cardinality
	 * count beside them. The same 2 000 the Performance view's own guard uses, chosen
	 * there for the same reason: 10x would be above {@code txn}'s 2 688 and could not
	 * fail. This window is the narrower of the two — fourteen days against the
	 * endpoint's thirty-day cap — so the shared ceiling is if anything stricter here.
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

	// ------------------------------------------------------------ find_issues

	/**
	 * The two predicates {@code idx_issue_status_last_seen_id} and its
	 * project-scoped sibling lead with. Asserted on the statement rather than on its
	 * plan because that is the claim this surface makes and the plan cannot make it:
	 * at guard scale 200 issues live in ten blocks and Postgres reads them end to
	 * end whatever the predicates say, so a plan assertion here would pass with the
	 * time bound deleted. #126 shipped once with exactly that hole — a green guard
	 * over a shape the product could not send.
	 */
	@Test
	void findIssuesBindsBothPredicatesTheListIndexesLeadWith() {
		for (String status : QueryPlans.toolIssueStatuses()) {
			String sql = QueryPlans.findIssues(List.of(), null, status, null, from, to, null, "last_seen", null).sql();

			assertThat(sql).as("find_issues, status=%s", status).contains("AND status = ?").contains(
					"AND last_seen >= ?");
		}
	}

	@Test
	void findIssuesSortsInMemoryForEveryStatusItAnswers() {
		for (String status : QueryPlans.toolIssueStatuses()) {
			PlanFacts facts = findIssues(status);

			QueryGuard.assertNoTempFiles(facts, "find_issues, status=" + status);
		}
	}

	// ------------------------------------------------------------ search_logs

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
	 * The trace filter is the other shape this Tool is called in, and it is the one
	 * an agent reaches from a Trace ID it read in another payload. It walks
	 * {@code idx_log_record_trace} rather than the ordering index — the same lookup
	 * {@code LogQueryPerformanceTest} guards, re-asserted here because the Tool
	 * sends it with a window bound the log page does not.
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

	// -------------------------------------------------------------- get_trace

	/**
	 * The four statements {@code get_trace} fans out into, each guarded against the
	 * table it reads. Guarded here rather than alongside the trace detail endpoint
	 * because until this Tool existed they had a benchmark and no guard, and a
	 * benchmark reports a number nobody's build fails on.
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
	 * What one {@code tools/call} costs is the four statements together, so the
	 * plan-shape assertions are also made against their sum. The ceiling stays per
	 * statement: each is bounded by a different table, and one number would have to
	 * sit below the smallest of them to be able to fail, which would say nothing
	 * about the other three.
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

	// ---------------------------------------------------------- get_event_raw

	@Test
	void getEventRawIsOneIndexProbePerPartition() {
		PlanFacts facts = eventRaw();

		QueryGuard.assertCeilingCanFail(jdbc, MAX_EVENT_RAW_BLOCKS, PartitionManager.EVENT);
		QueryGuard.assertUnderCeiling(facts, MAX_EVENT_RAW_BLOCKS, "get_event_raw");
		QueryGuard.assertNoTempFiles(facts, "get_event_raw");
		QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, "get_event_raw");
	}

	// --------------------------------------------------- performance_overview

	/**
	 * Every ranking the Tool offers, read from its own whitelist so a ranking added
	 * there is one this guard covers on the same commit. The percentile sorts are
	 * the ones worth naming: they subscript an array the ordered-set aggregate
	 * already produced, and a second {@code percentile_cont} in the {@code ORDER BY}
	 * would sort every group's durations twice for a number the first sort made.
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
	 * The Tool's default window has to survive the endpoint's own 30-day cap
	 * unclamped, or every call would come back reporting a clamp the caller did not
	 * cause — and the disclosure would stop meaning anything by being always on.
	 */
	@Test
	void theDefaultWindowIsInsideThePerformanceViewsOwnCap() {
		assertThat(QueryPlans.performanceWindow(from, to).clamped())
			.as("the Tool's default window is clamped by the Performance view's cap before any caller asks for it")
			.isFalse();
	}

	// ------------------------------------------------------------------ shapes

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
