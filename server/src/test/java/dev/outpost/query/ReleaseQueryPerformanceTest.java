package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import dev.outpost.support.PlanFacts;
import dev.outpost.support.TelemetrySeeder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Performance guard for the releases page. Its {@code issue_count} column is
 * structurally the same defect trace search already had and
 * {@link TraceSearchPerformanceTest} now locks out: a correlated aggregate over a
 * partitioned telemetry table, evaluated once per output row, with no time
 * bound.
 *
 * <p>Baselines measured 2026-08-01 against {@link TelemetrySeeder.Scale#GUARD}:
 * 40 003 events over 10 weekly partitions, 8 releases on the project.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReleaseQueryPerformanceTest {

	/**
	 * The bound is computed from the dataset rather than hardcoded, so it means the
	 * same thing at any scale: annotating a page of releases must not cost more than
	 * reading {@code event} twice. Today the page costs 240 368 blocks against the
	 * 15 045 one full scan costs — 16x — because the count runs once per release.
	 */
	private static final int FULL_SCANS_ALLOWED = 2;

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
	@Disabled("#130 — issue_count is a correlated unbounded aggregate run once per release row")
	void releaseListDoesNotOutcostReadingEventTwice() {
		long fullScan = QueryGuard.fullScanCost(jdbc, "event");
		PlanFacts facts = QueryPlans.releaseList(seeded.projectId()).explain(jdbc);

		assertThat(facts.logicalIo())
			.as("blocks for the release list against the %d a single full scan of event costs%n%s", fullScan,
					facts.plan())
			.isLessThan(FULL_SCANS_ALLOWED * fullScan);
	}

	/**
	 * Separately worth locking in: however the counts are computed, a page of at
	 * most 200 rows must not spill to disk. A correlated plan happens not to — it is
	 * slow rather than memory-hungry — so this stays enabled and will keep the
	 * eventual grouped rewrite honest.
	 */
	@Test
	void releaseListDoesNotSpillToDisk() {
		PlanFacts facts = QueryPlans.releaseList(seeded.projectId()).explain(jdbc);

		QueryGuard.assertNoTempFiles(facts, "the release list");
	}

}
