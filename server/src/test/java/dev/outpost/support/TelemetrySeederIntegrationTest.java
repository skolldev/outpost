package dev.outpost.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The seeder is the load-bearing piece of both retrieval tiers, and its failure
 * mode is silent: a dataset that lands entirely in one partition, or with every
 * event on one issue, produces fast numbers that mean nothing. This asserts the
 * properties the guards and the benchmark depend on but cannot themselves see.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelemetrySeederIntegrationTest {

	private static final TelemetrySeeder.Scale SCALE = TelemetrySeeder.Scale.GUARD.times(0.25);

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	TelemetrySeeder.Seeded seeded;

	TelemetrySeeder.Seeded seeded() {
		if (seeded == null) {
			seeded = new TelemetrySeeder(jdbc, partitions).seed(SCALE);
		}
		return seeded;
	}

	@Test
	void seedsTheRowCountsItWasAskedFor() {
		TelemetrySeeder.Seeded result = seeded();

		// The known-trace fixture adds a handful of rows on top of the requested
		// volume, so this is a floor rather than an equality.
		assertThat(result.events()).isGreaterThanOrEqualTo(SCALE.events());
		assertThat(result.logs()).isGreaterThanOrEqualTo(SCALE.logs());
		assertThat(result.txns()).isGreaterThanOrEqualTo(SCALE.txns());
		assertThat(result.spans()).isGreaterThanOrEqualTo(SCALE.spans());
		assertThat(result.projectIds()).hasSize(SCALE.projects());
	}

	/**
	 * A dataset that fits in one partition would make every pruning assertion in
	 * {@code dev.outpost.query} pass for the wrong reason, forever.
	 */
	@Test
	void spreadsEveryTelemetryTableOverManyWeeklyPartitions() {
		seeded();
		int expectedWeeks = SCALE.windowDays() / 7;

		for (String table : List.of("event", "log_record", "txn", "span")) {
			// Both halves matter: partitions that exist but hold nothing would let a
			// pruning assertion pass while the whole dataset sat in one week.
			assertThat(partitionCount(table)).as("weekly partitions of " + table)
				.isGreaterThanOrEqualTo(expectedWeeks);
			assertThat(populatedWeeks(table)).as("weeks of " + table + " that actually hold rows")
				.isGreaterThanOrEqualTo(expectedWeeks);
		}
	}

	/** Uniform events-per-issue makes every issue equally cheap; production is nothing like that. */
	@Test
	void skewsEventsSoAFewIssuesOwnMostOfThem() {
		TelemetrySeeder.Seeded result = seeded();

		long busiest = jdbc.sql("SELECT max(event_count) FROM issue").query(Long.class).single();
		long uniform = result.events() / result.issues();

		assertThat(busiest).as("events on the busiest issue vs the %d a uniform spread would give", uniform)
			.isGreaterThan(uniform * 5);
	}

	/**
	 * {@code count(DISTINCT user_ident)} is only as expensive as its cardinality:
	 * one distinct user makes it free, one per row makes it degenerate.
	 */
	@Test
	void seedsRealisticUserAndReleaseCardinality() {
		TelemetrySeeder.Seeded result = seeded();

		long users = jdbc.sql("SELECT count(DISTINCT user_ident) FROM event").query(Long.class).single();
		long releases = jdbc.sql("SELECT count(DISTINCT release) FROM event").query(Long.class).single();

		assertThat(users).isGreaterThan(1).isLessThan(result.events());
		assertThat(releases).isBetween(2L, (long) SCALE.releases() + 1);
	}

	/** Trace detail fans out across four tables; a trace present in only one measures nothing. */
	@Test
	void sharesTheKnownTraceAcrossAllFourTables() {
		TelemetrySeeder.Seeded result = seeded();

		for (String table : List.of("txn", "span", "event", "log_record")) {
			long rows = jdbc.sql("SELECT count(*) FROM " + table + " WHERE trace_id = ?")
				.param(result.traceId())
				.query(Long.class)
				.single();
			assertThat(rows).as("rows on the known trace in " + table).isPositive();
		}
	}

	/** Without statistics the planner is blind, and every plan the guards assert on is the wrong one. */
	@Test
	void analyzesSoThePlannerHasStatistics() {
		seeded();

		for (String table : List.of("event", "log_record", "txn", "span", "issue")) {
			Long analyzed = jdbc
				.sql("SELECT count(*) FROM pg_stats WHERE schemaname = 'public' AND tablename = ?")
				.param(table)
				.query(Long.class)
				.single();
			assertThat(analyzed).as("column statistics for " + table).isPositive();
		}
	}

	/** Conditions a report has to state — read back from the server, never assumed. */
	@Test
	void reportsThePostgresSettingsThatDecideWhetherARunIsMeasuringCacheOrDisk() {
		Map<String, String> settings = new TelemetrySeeder(jdbc, partitions).settings();

		assertThat(settings).containsKeys("shared_buffers", "work_mem", "synchronous_commit", "server_version");
	}

	private long partitionCount(String table) {
		return jdbc.sql("""
				SELECT count(*) FROM pg_inherits i
				JOIN pg_class p ON p.oid = i.inhparent
				WHERE p.relname = ?
				""").param(table).query(Long.class).single();
	}

	private long populatedWeeks(String table) {
		String column = table.equals("txn") || table.equals("span") ? "start_ts" : "\"timestamp\"";
		return jdbc.sql("SELECT count(DISTINCT date_trunc('week', " + column + ")) FROM " + table)
			.query(Long.class)
			.single();
	}

}
