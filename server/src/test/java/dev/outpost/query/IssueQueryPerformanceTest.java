package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import dev.outpost.support.PlanFacts;
import dev.outpost.support.TelemetrySeeder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Performance guards for the issue list — the most-visited screen in the product
 * and the one that issues the most queries per page load: the list itself, a
 * 14-day sparkline, a distinct-user count, and the environment rollup.
 *
 * <p>Each guard {@code EXPLAIN}s the controller's own SQL through
 * {@link QueryPlans} — never a copy, which would keep passing after the real
 * query regressed — and asserts on logical I/O and plan shape only. No wall
 * clock. {@link QueryGuard} documents how the ceilings are calibrated and why
 * three of these are {@code @Disabled}.
 *
 * <p>Baselines below were measured on 2026-08-01 against
 * {@link TelemetrySeeder.Scale#GUARD}: 40 003 events over 10 weekly partitions,
 * 200 issues. A full scan of {@code event} costs 15 045 blocks on that dataset,
 * which is the number every enabled ceiling has to sit below.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueQueryPerformanceTest {

	private static final int SPARKLINE_DAYS = 14;

	/** Healthy is 30 blocks; 300 is the standard 10x, and ~50x below a full scan of {@code event}. */
	private static final long MAX_LIST_BLOCKS = 300;

	/**
	 * Healthy is 9 428 blocks — high because at guard scale the 14-day window holds
	 * most of the events. 10x would be 94 280, above the 15 045 a full scan of
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
	 * too. Today it costs 20 045.
	 */
	private static final long MAX_USERS_AFFECTED_BLOCKS = MAX_SPARKLINE_BLOCKS;

	/**
	 * The target for the release filter (#127), derived from the environment filter:
	 * the same "which issues have signal X" question answered against a rollup costs
	 * 132 blocks, so 10x that is the class the release filter belongs in. Today it
	 * costs 12 527.
	 */
	private static final long MAX_RELEASE_FILTER_BLOCKS = 1_320;

	/** Page 1 and page N differ by the keyset predicate alone, so a small constant covers the noise. */
	private static final int DEEP_PAGE_TOLERANCE = 4;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	TelemetrySeeder.Seeded seeded;

	@BeforeAll
	void seed() {
		seeded = new TelemetrySeeder(jdbc, partitions).seed(TelemetrySeeder.Scale.GUARD);
	}

	// -------------------------------------------------------------------- list

	@Test
	void issueListPageOneStaysUnderCeiling() {
		PlanFacts facts = pageOne().explain(jdbc);

		QueryGuard.assertUnderCeiling(facts, MAX_LIST_BLOCKS, "issue list page 1");
		QueryGuard.assertNoTempFiles(facts, "issue list page 1");
		QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, "issue list page 1");
		QueryGuard.assertCeilingCanFail(jdbc, MAX_LIST_BLOCKS, "event");
	}

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

		PlanFacts deep = QueryPlans.issueList(null, null, null, null, null, null, null, "last_seen", cursor)
			.explain(jdbc);

		assertThat(deep.logicalIo()).as("blocks for a deep issue page against the %d page 1 costs%n%s", pageOneBlocks,
				deep.plan()).isLessThanOrEqualTo(DEEP_PAGE_TOLERANCE * pageOneBlocks);
	}

	/**
	 * The list orders by {@code (last_seen, id)} or {@code (event_count, id)}, and
	 * the only index on {@code issue} is {@code (project_id, last_seen DESC)} — so
	 * the global list sorts the whole table on every page. A {@code Sort} node is
	 * the shape that says so, and it says it at any dataset size, which is why this
	 * asserts on the plan rather than on a block count that guard scale keeps small.
	 */
	@Test
	@Disabled("#126 — no index supports the issue list's sort order")
	void issueListSortIsIndexSupported() {
		assertThat(pageOne().explain(jdbc).ran("Sort"))
			.as("issue list page 1 sorts the whole table rather than walking an index")
			.isFalse();
	}

	// -------------------------------------------------------------- aggregates

	/**
	 * The sparkline is bounded to the last 14 days, so it must read only the weekly
	 * partitions from that week onwards — never the whole retention span.
	 */
	@Test
	void sparklinePrunesToItsWindow() {
		Instant since = sparklineWindowStart();
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

		QueryGuard.assertPrunesFrom(jdbc, facts, "event", sparklineWindowStart(),
				"the issue-list users-affected aggregate");
		QueryGuard.assertUnderCeiling(facts, MAX_USERS_AFFECTED_BLOCKS, "the issue-list users-affected aggregate");
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

	private static Instant sparklineWindowStart() {
		return LocalDate.now(ZoneOffset.UTC).minusDays(SPARKLINE_DAYS - 1L).atStartOfDay().toInstant(ZoneOffset.UTC);
	}

	private QueryPlans.Built pageOne() {
		return QueryPlans.issueList(null, null, null, null, null, null, null, "last_seen", null);
	}

	private List<Long> pageIds() {
		return QueryPlans.issueIdsOnPage(jdbc, "last_seen", null);
	}

}
