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

/**
 * Performance guards for the log stream. Baselines measured 2026-08-01 against
 * {@link TelemetrySeeder.Scale#GUARD}: 40 010 log records over 10 weekly
 * partitions, where a full scan of {@code log_record} costs 5 043 blocks.
 *
 * <p>Two of these are {@code @Disabled}, and the root cause of the first is worth
 * stating up front: {@code log_record} has no index serving the stream's
 * {@code ("timestamp", id)} ordering, so <b>page 1 already reads every partition
 * and sorts</b> (#128). That plan cannot be made worse by an unindexable
 * predicate, which is why the attribute guard (#132) is written as a
 * <em>differential</em> assertion — a filter eliminating 99.9 % of rows must make
 * the query cheaper — rather than as an absolute ceiling. Stated as a ceiling it
 * would fail for #128's reason and hide its own.
 *
 * <p>Body-substring search is deliberately <b>not</b> guarded here. It looked
 * broken at this scale and is not: 40 000 rows is small enough that the planner
 * correctly prefers a scan to {@code idx_log_body_trgm}, and at the retrieval
 * benchmark's 500 000 it uses the index for a 10x saving. A guard whose verdict
 * flips with dataset size is worse than none, so that question lives in the
 * benchmark, which has the rows to answer it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogQueryPerformanceTest {

	private static final int WINDOW_DAYS = 14;

	/** Healthy is 79 blocks for the indexed trace lookup; 790 is the standard 10x, well under a full scan. */
	private static final long MAX_TRACE_LOOKUP_BLOCKS = 790;

	/**
	 * The target for page 1 (#128), not today's 8 804. An indexed 10-row lookup on
	 * the same table costs 79 blocks; a 101-row page merged across 10 partitions is
	 * a larger job but the same shape, and 1 000 leaves room for it while sitting
	 * ~5x below the 5 043 a full scan costs — so it can fail.
	 */
	private static final long MAX_PAGE_ONE_BLOCKS = 1_000;

	/** A filter that removes 99.9 % of rows should remove most of the work; 4x is the modest floor. */
	private static final int SELECTIVE_FILTER_SAVING = 4;

	/** Page 1 and page N differ by the keyset predicate alone, so a small constant covers the noise. */
	private static final int DEEP_PAGE_TOLERANCE = 2;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	TelemetrySeeder.Seeded seeded;

	@BeforeAll
	void seed() {
		seeded = new TelemetrySeeder(jdbc, partitions).seed(TelemetrySeeder.Scale.GUARD);
	}

	// --------------------------------------------------------------- ordering

	/**
	 * Page 1 of the global stream returns 100 rows and should cost about what
	 * fetching 100 rows costs. It does not: with no index on
	 * {@code ("timestamp" DESC, id DESC)} the plan reads every partition and sorts
	 * the lot — more expensive than simply reading the table, because it reads the
	 * table <em>and then</em> sorts it.
	 */
	@Test
	@Disabled("#128 — no index supports the global log stream's ordering")
	void logListPageOneIsIndexSupported() {
		PlanFacts facts = pageOne().explain(jdbc);

		assertThat(facts.ran("Sort")).as("log list page 1 sorts every partition rather than merging indexes%n%s",
				facts.plan()).isFalse();
		QueryGuard.assertUnderCeiling(facts, MAX_PAGE_ONE_BLOCKS, "log list page 1");
		QueryGuard.assertCeilingCanFail(jdbc, MAX_PAGE_ONE_BLOCKS, "log_record");
	}

	/** Whatever page 1 costs, page N must cost the same — that is the whole claim keyset paging makes. */
	@Test
	void deepPageCostsWhatPageOneCosts() {
		long pageOneBlocks = pageOne().explain(jdbc).logicalIo();
		String cursor = QueryPlans.logCursorAtPage(jdbc, 3);

		PlanFacts deep = QueryPlans.logs(null, null, null, null, null, null, null, null, null, cursor).explain(jdbc);

		assertThat(deep.logicalIo()).as("blocks for a deep log page against the %d page 1 costs%n%s", pageOneBlocks,
				deep.plan()).isLessThanOrEqualTo(DEEP_PAGE_TOLERANCE * pageOneBlocks);
	}

	// ---------------------------------------------------------------- lookups

	@Test
	void traceLookupUsesTheTraceIndex() {
		PlanFacts facts = QueryPlans.logs(null, null, null, seeded.traceId(), null, null, null, null, null, null)
			.explain(jdbc);

		QueryGuard.assertUnderCeiling(facts, MAX_TRACE_LOOKUP_BLOCKS, "logs by trace_id");
		QueryGuard.assertNoSequentialScanOfTelemetry(jdbc, facts, "logs by trace_id");
		QueryGuard.assertNoTempFiles(facts, "logs by trace_id");
		QueryGuard.assertCeilingCanFail(jdbc, MAX_TRACE_LOOKUP_BLOCKS, "log_record");
	}

	/**
	 * A bounded log query must prune to the partitions its window covers. No buffer
	 * ceiling here on purpose: at guard scale the 14-day window holds most of the
	 * table, so there is no number both above the healthy plan and below a full
	 * scan. Pruning is the assertion that means something, and it holds at any
	 * scale.
	 */
	@Test
	void timeBoundedQueryPrunesToItsWindow() {
		Instant since = windowStart();
		PlanFacts facts = boundedLogs(null, null).explain(jdbc);

		QueryGuard.assertPrunesFrom(jdbc, facts, "log_record", since, "a 14-day-bounded log query");
		QueryGuard.assertNoTempFiles(facts, "a 14-day-bounded log query");
	}

	// ---------------------------------------------------------------- filters

	/**
	 * {@code attributes->>? = ?} cannot use {@code idx_log_attributes}: the key is a
	 * bind parameter, and {@code jsonb_ops} indexes containment rather than text
	 * extraction. Adding a filter that matches one row in a thousand therefore costs
	 * exactly as much as not adding it.
	 *
	 * <p>Comparing against the same query without the filter, rather than against a
	 * fixed ceiling, keeps this measuring the predicate and not #128.
	 */
	@Test
	@Disabled("#132 — attributes->>? = ? cannot use the attributes GIN index")
	void attributeFilterMakesTheQueryCheaper() {
		assertSelectiveFilterPaysForItself(
				boundedLogs(null, List.of(seeded.attributeKey() + "=" + seeded.attributeValue())),
				"the attribute-equality filter");
	}

	/** Presence filtering goes through {@code jsonb_exists}, which the GIN index does serve. */
	@Test
	void attributePresenceFilterStaysWithinTheUnfilteredCost() {
		long unfiltered = boundedLogs(null, null).explain(jdbc).logicalIo();
		PlanFacts facts = boundedLogs(null, List.of(seeded.attributeKey())).explain(jdbc);

		assertThat(facts.logicalIo())
			.as("blocks for an attribute-presence filter against the %d an unfiltered page costs%n%s", unfiltered,
					facts.plan())
			.isLessThanOrEqualTo(DEEP_PAGE_TOLERANCE * unfiltered);
	}

	// ----------------------------------------------------------------- helpers

	/**
	 * Both queries are time-bounded so partition pruning is held constant and the
	 * only difference between them is the predicate under test.
	 */
	private void assertSelectiveFilterPaysForItself(QueryPlans.Built filtered, String what) {
		long unfiltered = boundedLogs(null, null).explain(jdbc).logicalIo();
		PlanFacts facts = filtered.explain(jdbc);

		assertThat(facts.logicalIo())
			.as("%s matches ~0.1%% of rows but costs %d blocks against the %d an unfiltered page costs — "
					+ "the predicate is being applied after the read, not by an index%n%s", what, facts.logicalIo(),
					unfiltered, facts.plan())
			.isLessThan(unfiltered / SELECTIVE_FILTER_SAVING);
	}

	private static Instant windowStart() {
		return Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);
	}

	private QueryPlans.Built pageOne() {
		return QueryPlans.logs(null, null, null, null, null, null, null, null, null, null);
	}

	private QueryPlans.Built boundedLogs(String query, List<String> attr) {
		return QueryPlans.logs(null, null, null, null, null, query, attr, windowStart(), null, null);
	}

}
