package dev.outpost.query;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance guards for the MCP Surface's {@code get_issue_context}, which is
 * the case ADR-0016 named when it said the reuse rule is not an exemption from
 * guarding. The Tool answers one call with three statements: an Issue + Project
 * + latest-Event join no controller performs, a Trace summary the trace detail
 * endpoint does not compute, and the log list's own factory bound to a window.
 * Only the third arrives with a guard already written, and it arrives at a shape
 * the log stream never sends — minutes wide rather than the UI's fourteen days.
 *
 * <p>Each guard {@code EXPLAIN}s the Tool's own SQL through {@link QueryPlans},
 * never a copy, and asserts on logical I/O and plan shape only. {@link QueryGuard}
 * documents how a ceiling is calibrated and why every one of them has to sit
 * below the cost of simply reading the table.
 *
 * <p>Baselines measured 2026-08-28 against {@link TelemetrySeeder.Scale#GUARD}:
 * 40 003 events, 40 010 log records and 8 000 transactions over 10 weekly
 * partitions each, where a full scan costs 15 027 blocks on {@code event},
 * 5 046 on {@code log_record}, 5 184 on {@code span} and 2 691 on {@code txn}.
 * One whole Tool call measured 927 blocks — 292 + 576 + 59, in that order.
 *
 * <p><b>The Tool binds the shapes the indexes were tuned for, and that is what
 * these guards check.</b> ADR-0016's warning is that an agent omits parameters a
 * user never has to pick, so the Tool applies its own defaults server-side; a
 * guard passing a window nobody sends would measure a request the Tool cannot
 * make, which is #126's failure mode transplanted onto this surface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueContextPerformanceTest {

	/**
	 * Healthy is ~292 blocks; 10x is the standard multiple and sits far below the
	 * ~15 000 a full scan of {@code event} costs, so it can still fail. What it
	 * fails on is the lookup losing its index: the {@code LATERAL} has no time
	 * bound, so an Issue's Events are only cheap to reach because
	 * {@code idx_event_issue_ts} leads with {@code issue_id} and the ordered Append
	 * over the weekly partitions stops at the first row.
	 */
	private static final long MAX_ISSUE_CONTEXT_BLOCKS = 2_900;

	/**
	 * Healthy is ~576 blocks, over three partitioned tables with no time bound —
	 * a trace_id is not a time, and the trace detail endpoint has always paid the
	 * same price for the same reason. Not the standard 10x, which would be 5 760 and
	 * above the 2 691 a full scan of {@code txn} costs: {@code txn} is the smallest
	 * of the three tables this reads, so it is the one that decides whether the
	 * ceiling can fail at all. 2 000 is ~3.5x over the measured plan and still under
	 * it.
	 */
	private static final long MAX_TRACE_SUMMARY_BLOCKS = 2_000;

	/**
	 * Healthy is ~59 blocks — a ten-minute window is one partition and a handful of
	 * rows. 10x, comfortably under {@code log_record}'s ~5 046. The regression it
	 * catches is the window bound going missing, which turns this into the whole
	 * log stream.
	 */
	private static final long MAX_SURROUNDING_LOG_BLOCKS = 590;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	TelemetrySeeder.Seeded seeded;

	@BeforeAll
	void seed() {
		seeded = new TelemetrySeeder(jdbc, partitions).seed(TelemetrySeeder.Scale.GUARD);
	}

	// ----------------------------------------------------------- issue + event

	@Test
	void theIssueContextJoinStaysUnderItsCeiling() {
		PlanFacts facts = issueContext();

		QueryGuard.assertCeilingCanFail(jdbc, MAX_ISSUE_CONTEXT_BLOCKS, PartitionManager.EVENT);
		QueryGuard.assertUnderCeiling(facts, MAX_ISSUE_CONTEXT_BLOCKS, "the get_issue_context join");
		QueryGuard.assertNoTempFiles(facts, "the get_issue_context join");
		QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, "the get_issue_context join");
	}

	/**
	 * The latest Event is reached by walking {@code idx_event_issue_ts} backwards
	 * and stopping, not by collecting an Issue's Events and sorting them. The index
	 * is named rather than merely required, for the reason #126 established: "an
	 * index was used" cannot fail when the wrong one is chosen.
	 */
	@Test
	void theLatestEventLookupWalksTheIssueIndex() {
		PlanFacts facts = issueContext();

		QueryGuard.assertWalksIndex(jdbc, facts, PartitionManager.EVENT, List.of("idx_event_issue_ts"),
				"the get_issue_context join");
	}

	// -------------------------------------------------------------------- trace

	@Test
	void theTraceSummaryStaysUnderItsCeiling() {
		PlanFacts facts = traceSummary();

		QueryGuard.assertCeilingCanFail(jdbc, MAX_TRACE_SUMMARY_BLOCKS, PartitionManager.TXN);
		QueryGuard.assertUnderCeiling(facts, MAX_TRACE_SUMMARY_BLOCKS, "the get_issue_context Trace summary");
		QueryGuard.assertNoTempFiles(facts, "the get_issue_context Trace summary");
		QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, "the get_issue_context Trace summary");
	}

	/**
	 * The three counts are uncorrelated, so Postgres evaluates each once as an
	 * {@code InitPlan} rather than once per candidate transaction. This is the
	 * defect behind the trace-search regression and #130, and the one a buffer
	 * ceiling cannot express: the right index makes a page's worth of per-row
	 * probes cheap enough to sit under any ceiling a fixture can honestly set.
	 */
	@Test
	void theTraceCountsAreEvaluatedOnceRatherThanPerRow() {
		PlanFacts facts = traceSummary();

		assertThat(facts.correlatedSubplans())
			.as("per-row subplans in the Trace summary — its three counts must be evaluated once each%n%s",
					facts.plan())
			.isEmpty();
	}

	// --------------------------------------------------------------------- logs

	@Test
	void theSurroundingLogWindowPrunesToItsOwnPartitions() {
		Instant from = seeded.eventTimestamp().minus(QueryPlans.surroundingLogWindow());
		PlanFacts facts = surroundingLogs();

		QueryGuard.assertCeilingCanFail(jdbc, MAX_SURROUNDING_LOG_BLOCKS, PartitionManager.LOG_RECORD);
		QueryGuard.assertUnderCeiling(facts, MAX_SURROUNDING_LOG_BLOCKS, "the get_issue_context Log Records");
		QueryGuard.assertPrunesFrom(jdbc, facts, PartitionManager.LOG_RECORD, from,
				"the get_issue_context Log Records");
	}

	// ---------------------------------------------------------------- whole call

	/**
	 * What one {@code tools/call} costs is the three statements together, so the
	 * plan-shape assertions are also made against their sum — reporting one of three
	 * would be a number nobody waits for. The ceiling stays per statement: each is
	 * bounded by a different table, and a single number would have to sit below the
	 * smallest of them to be able to fail, which would say nothing about the other
	 * two.
	 */
	@Test
	void theWholeToolCallSortsInMemoryAndScansNoTelemetrySequentially() {
		PlanFacts call = issueContext().merge(traceSummary()).merge(surroundingLogs());

		QueryGuard.assertNoTempFiles(call, "one get_issue_context call");
		QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, call, "one get_issue_context call");
	}

	// ------------------------------------------------------------------ fixtures

	private PlanFacts issueContext() {
		return QueryPlans.issueContext(seeded.issueId()).explain(jdbc);
	}

	private PlanFacts traceSummary() {
		return QueryPlans.traceSummary(seeded.traceId()).explain(jdbc);
	}

	/** The window the Tool applies when an agent names none — the shape it actually sends. */
	private PlanFacts surroundingLogs() {
		Instant at = seeded.eventTimestamp();
		return QueryPlans
			.surroundingLogs(seeded.projectId(), at.minus(QueryPlans.surroundingLogWindow()),
					at.plus(QueryPlans.surroundingLogWindow()))
			.explain(jdbc);
	}
}
