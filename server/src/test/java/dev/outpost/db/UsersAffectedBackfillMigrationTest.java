package dev.outpost.db;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V18's backfill against events that predate it: the application's own schema
 * is already at the latest version, so this replays the migrations into a
 * scratch schema, stops at V17, seeds, and only then applies V18.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class UsersAffectedBackfillMigrationTest {

	private static final String SCHEMA = "v18_backfill";

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcClient jdbc;

	@AfterEach
	void dropSchema() {
		jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
	}

	@Test
	void backfillCountsDistinctNonNullUsersPerIssue() {
		migrateTo("17");
		jdbc.sql("CREATE TABLE %s.event_backfill PARTITION OF %s.event FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')"
			.formatted(SCHEMA, SCHEMA)).update();
		long project = jdbc.sql("INSERT INTO %s.project (slug, name) VALUES ('p', 'P') RETURNING id".formatted(SCHEMA))
			.query(Long.class).single();
		long checkout = issue(project, "checkout");
		long login = issue(project, "login");
		long anonymous = issue(project, "anonymous");
		event(project, checkout, "alice", "2026-06-01T00:00:00Z");
		event(project, checkout, "alice", "2026-06-02T00:00:00Z");
		event(project, checkout, "bob", "2026-06-02T00:00:00Z");
		event(project, checkout, null, "2026-06-02T00:00:00Z");
		event(project, login, "alice", "2026-06-03T00:00:00Z");
		event(project, anonymous, null, "2026-06-03T00:00:00Z");
		// Past the btree entry limit if it were the key itself; must not block startup.
		event(project, login, incompressible(10_000), "2026-06-03T00:00:00Z");
		// event carries no FK to issue, so an orphan can exist and must not fail the migration.
		event(project, 999_999, "ghost", "2026-06-03T00:00:00Z");

		migrateTo("18");

		assertThat(jdbc.sql("SELECT fingerprint, users_affected FROM %s.issue".formatted(SCHEMA))
			.query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list())
			.containsExactlyInAnyOrder(Map.entry("checkout", 2L), Map.entry("login", 2L), Map.entry("anonymous", 0L));
		assertThat(jdbc.sql("SELECT count(*) FROM %s.issue_user_stats".formatted(SCHEMA)).query(Long.class).single())
			.isEqualTo(4);
	}

	private void migrateTo(String version) {
		Flyway.configure()
			.dataSource(dataSource)
			.schemas(SCHEMA)
			// pg_trgm's operator classes live in public, where the application's own migration installed it.
			.initSql("SET search_path TO " + SCHEMA + ", public")
			.target(version)
			.load()
			.migrate();
	}

	private long issue(long project, String fingerprint) {
		return jdbc.sql("""
				INSERT INTO %s.issue (project_id, fingerprint, title, first_seen, last_seen, event_count)
				VALUES (?, ?, ?, now(), now(), 1) RETURNING id
				""".formatted(SCHEMA)).param(project).param(fingerprint).param(fingerprint).query(Long.class).single();
	}

	private void event(long project, long issueId, String userIdent, String timestamp) {
		jdbc.sql("""
				INSERT INTO %s.event (id, project_id, issue_id, environment, "timestamp", user_ident, data)
				VALUES (?, ?, ?, 'prod', ?::timestamptz, ?, '{}'::jsonb)
				""".formatted(SCHEMA)).param(UUID.randomUUID()).param(project).param(issueId).param(timestamp)
			.param(userIdent).update();
	}

	/** Random hex, so neither TOAST nor index compression can shrink it under the btree entry limit. */
	private static String incompressible(int length) {
		StringBuilder text = new StringBuilder(length + 36);
		while (text.length() < length) {
			text.append(UUID.randomUUID());
		}
		return text.substring(0, length);
	}
}
