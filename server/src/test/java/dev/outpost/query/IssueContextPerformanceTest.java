package dev.outpost.query;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import dev.outpost.support.PlanFacts;
import dev.outpost.support.TelemetrySeeder;
import java.time.Duration;
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
 * Performance guards for the MCP {@code get_issue_context} Tool: EXPLAINs the
 * Tool's own SQL via {@link QueryPlans} and asserts on logical I/O and plan
 * shape only, at the shapes the Tool binds server-side by default (ADR-0016).
 * See {@link QueryGuard} for how ceilings are calibrated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueContextPerformanceTest {

	/**
	 * Healthy is ~292 blocks. Catches the {@code LATERAL} losing
	 * {@code idx_event_issue_ts} and scanning an Issue's Events instead of
	 * stopping at the first row.
	 */
	private static final long MAX_ISSUE_CONTEXT_BLOCKS = 2_900;

	/**
	 * Healthy is ~576 blocks over three partitioned tables with no time bound
	 * (a trace_id is not a time).
	 */
	private static final long MAX_TRACE_SUMMARY_BLOCKS = 2_000;

	/**
	 * Healthy is 4-8 blocks; catches the window bound going missing, which would
	 * read the whole log stream. The range varies run to run because the seeder
	 * scatters timestamps.
	 */
	private static final long MAX_SURROUNDING_LOG_BLOCKS = 80;

	/** The widest window the Tool accepts (one hour), measured at 32-50 blocks. */
	private static final long MAX_WIDEST_SURROUNDING_LOG_BLOCKS = 500;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	TelemetrySeeder.Seeded seeded;

	@BeforeAll
	void seed() {
		seeded = new TelemetrySeeder(jdbc, partitions).seed(TelemetrySeeder.Scale.GUARD);
	}

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
	 * and stopping, not by collecting the Issue's Events and sorting them. The
	 * index is named explicitly because "an index was used" would still pass
	 * with the wrong one chosen.
	 */
	@Test
	void theLatestEventLookupWalksTheIssueIndex() {
		PlanFacts facts = issueContext();

		QueryGuard.assertWalksIndex(jdbc, facts, PartitionManager.EVENT, List.of("idx_event_issue_ts"),
				"the get_issue_context join");
	}

	/**
	 * The Environment-scoped variant stops at the first matching row rather than
	 * the first row, so it needs its own guard instead of inheriting the
	 * unfiltered one's.
	 */
	@Test
	void theEnvironmentScopedLookupWalksTheSameIndexUnderTheSameCeiling() {
		PlanFacts facts = QueryPlans.issueContext(seeded.issueId(), seeded.environment()).explain(jdbc);
		String what = "the get_issue_context join scoped to one Environment";

		QueryGuard.assertUnderCeiling(facts, MAX_ISSUE_CONTEXT_BLOCKS, what);
		QueryGuard.assertNoTempFiles(facts, what);
		QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, what);
		QueryGuard.assertWalksIndex(jdbc, facts, PartitionManager.EVENT, List.of("idx_event_issue_ts"), what);
	}

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
	 * {@code InitPlan} rather than once per candidate transaction — a buffer
	 * ceiling alone can't catch a per-row evaluation cheap enough to hide under it.
	 */
	@Test
	void theTraceCountsAreEvaluatedOnceRatherThanPerRow() {
		PlanFacts facts = traceSummary();

		assertThat(facts.correlatedSubplans())
			.as("per-row subplans in the Trace summary — its three counts must be evaluated once each%n%s",
					facts.plan())
			.isEmpty();
	}

	@Test
	void everySurroundingLogWindowPrunesToItsOwnPartitions() {
		QueryGuard.assertCeilingCanFail(jdbc, MAX_WIDEST_SURROUNDING_LOG_BLOCKS, PartitionManager.LOG_RECORD);
		long[] ceilings = { MAX_SURROUNDING_LOG_BLOCKS, MAX_WIDEST_SURROUNDING_LOG_BLOCKS };
		List<Duration> windows = QueryPlans.surroundingLogWindows();

		for (int i = 0; i < windows.size(); i++) {
			Duration width = windows.get(i);
			String what = "the get_issue_context Log Records over " + width;
			PlanFacts facts = surroundingLogs(width);

			QueryGuard.assertUnderCeiling(facts, ceilings[i], what);
			QueryGuard.assertNoTempFiles(facts, what);
			QueryGuard.assertPrunesFrom(jdbc, facts, PartitionManager.LOG_RECORD,
					seeded.eventTimestamp().minus(width), what);
		}
	}

	/**
	 * One {@code tools/call} costs the three statements together, so the plan
	 * assertions are made on their sum. Ceilings stay per statement, since each
	 * is bounded by a different table.
	 */
	@Test
	void theWholeToolCallSortsInMemoryAndScansNoTelemetrySequentially() {
		PlanFacts call = issueContext().merge(traceSummary())
			.merge(surroundingLogs(QueryPlans.surroundingLogWindows().get(0)));

		QueryGuard.assertNoTempFiles(call, "one get_issue_context call");
		QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, call, "one get_issue_context call");
	}

	private PlanFacts issueContext() {
		return QueryPlans.issueContext(seeded.issueId()).explain(jdbc);
	}

	private PlanFacts traceSummary() {
		return QueryPlans.traceSummary(seeded.traceId()).explain(jdbc);
	}

	/** The window as the Tool binds it: {@code width} back from the Event, ending at it. */
	private PlanFacts surroundingLogs(Duration width) {
		Instant at = seeded.eventTimestamp();
		return QueryPlans.surroundingLogs(seeded.projectId(), at.minus(width), at.plusSeconds(1)).explain(jdbc);
	}
}
