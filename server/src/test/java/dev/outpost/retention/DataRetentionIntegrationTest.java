package dev.outpost.retention;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import dev.outpost.pipeline.EventIssueLock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class DataRetentionIntegrationTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	@Autowired
	DataRetentionSettings settings;

	@Autowired
	DataRetentionScheduler scheduler;

	@Autowired
	EventIssueLock eventIssueLock;

	@Autowired
	PlatformTransactionManager transactionManager;

	final RestTemplate rest = new RestTemplate();
	String adminCookie;

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM span").update();
		jdbc.sql("DELETE FROM txn").update();
		jdbc.sql("DELETE FROM log_record").update();
		jdbc.sql("DELETE FROM event").update();
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM uptime_monitor").update();
		jdbc.sql("DELETE FROM artifact_bundle").update();
		jdbc.sql("DELETE FROM api_token").update();
		jdbc.sql("DELETE FROM project").update();
		jdbc.sql("DELETE FROM app_user WHERE email <> 'admin@test.local'").update();
		jdbc.sql("DELETE FROM setting WHERE key IN (?, ?)")
			.param(DataRetentionSettings.ENABLED_KEY)
			.param(DataRetentionSettings.DAYS_KEY)
			.update();
		adminCookie = login("admin@test.local", "test-password");
	}

	@Test
	void settingsDefaultPersistValidateAndRequireAdmin() {
		ResponseEntity<Map> defaults = exchange(HttpMethod.GET, null, adminCookie);
		assertThat(defaults.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(defaults.getBody()).containsEntry("enabled", false).containsEntry("retention_days", 90);

		ResponseEntity<Map> updated = exchange(HttpMethod.PUT,
				Map.of("enabled", true, "retention_days", 30), adminCookie);
		assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updated.getBody()).containsEntry("enabled", true).containsEntry("retention_days", 30);
		assertThat(exchange(HttpMethod.GET, null, adminCookie).getBody())
			.containsEntry("enabled", true)
			.containsEntry("retention_days", 30);
		assertThat(jdbc.sql("SELECT value FROM setting WHERE key = ?")
			.param(DataRetentionSettings.DAYS_KEY)
			.query(String.class)
			.single()).isEqualTo("30");

		assertThat(exchange(HttpMethod.PUT, Map.of("enabled", true), adminCookie).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(exchange(HttpMethod.PUT, Map.of("retention_days", 60), adminCookie).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(exchange(HttpMethod.PUT, Map.of("enabled", true, "retention_days", 7), adminCookie)
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		postUser("member@test.local", "member-password");
		String memberCookie = login("member@test.local", "member-password");
		assertThat(exchange(HttpMethod.GET, null, memberCookie).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(exchange(HttpMethod.PUT, Map.of("enabled", false, "retention_days", 90), memberCookie)
			.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void disabledPolicyChangesNothing() {
		Instant run = Instant.parse("2026-07-19T02:00:00Z");
		Instant expired = run.minus(Duration.ofDays(91));
		long projectId = insertProject();
		partitions.ensurePartition(PartitionManager.LOG_RECORD, expired);
		insertLog(projectId, expired, "expired");
		settings.save(false, 30);

		scheduler.runOnce(run);

		assertThat(count("log_record")).isEqualTo(1);
	}

	@Test
	void enabledCleanupUsesStrictCutoffRebuildsIssuesAndRetainsConfiguration() {
		Instant run = Instant.parse("2026-07-19T02:00:00Z");
		Instant cutoff = run.minus(Duration.ofDays(60));
		Instant before = cutoff.minusSeconds(1);
		Instant at = cutoff;
		Instant after = cutoff.plusSeconds(1);
		long projectId = insertProject();
		for (Instant instant : List.of(before, at, after)) {
			partitions.ensurePartition(PartitionManager.EVENT, instant);
			partitions.ensurePartition(PartitionManager.LOG_RECORD, instant);
			partitions.ensurePartition(PartitionManager.TXN, instant);
			partitions.ensurePartition(PartitionManager.SPAN, instant);
		}

		seedRetainedConfiguration(projectId);
		long staleIssue = insertIssue(projectId, "stale", "Stale title", "old culprit", "unresolved", before);
		insertEvent(projectId, staleIssue, before, "prod", "error");
		long emptyIssue = insertIssue(projectId, "empty", "Empty title", "empty culprit", "unresolved", before);

		long survivingIssue = insertIssue(projectId, "survivor", "Preserved title", "preserved culprit", "resolved",
				before.minusSeconds(20));
		insertEvent(projectId, survivingIssue, before, "prod", "error");
		insertEvent(projectId, survivingIssue, at, "prod", "info");
		insertEvent(projectId, survivingIssue, after, "staging", "warning");
		jdbc.sql("INSERT INTO issue_env_stats (issue_id, environment, event_count, last_seen) VALUES (?, 'prod', 99, ?)")
			.param(survivingIssue)
			.param(timestamp(before))
			.update();

		insertLog(projectId, before, "before");
		insertLog(projectId, at, "at");
		insertLog(projectId, after, "after");

		UUID expiredTxn = insertTransaction(projectId, before, "expired");
		UUID atTxn = insertTransaction(projectId, at, "at");
		insertTransaction(projectId, after, "after");
		insertSpan(projectId, expiredTxn, after, "owned-by-expired");
		insertSpan(projectId, atTxn, before, "expired-span");
		insertSpan(projectId, atTxn, after, "retained-span");

		long monitorId = jdbc.sql("""
				INSERT INTO uptime_monitor (project_id, environment, url, interval_seconds, next_check_at)
				VALUES (?, 'prod', 'https://example.test/health', 3600, now() + interval '1 day') RETURNING id
				""").param(projectId).query(Long.class).single();
		insertCheck(monitorId, before);
		insertCheck(monitorId, at);
		insertCheck(monitorId, after);
		jdbc.sql("INSERT INTO uptime_incident (monitor_id, opened_at, closed_at) VALUES (?, ?, ?)")
			.param(monitorId).param(timestamp(before.minusSeconds(10))).param(timestamp(before)).update();
		jdbc.sql("INSERT INTO uptime_incident (monitor_id, opened_at, closed_at) VALUES (?, ?, ?)")
			.param(monitorId).param(timestamp(before)).param(timestamp(at)).update();
		jdbc.sql("INSERT INTO uptime_incident (monitor_id, opened_at) VALUES (?, ?)")
			.param(monitorId).param(timestamp(before)).update();

		settings.save(true, 60);
		scheduler.runOnce(run);

		assertThat(count("event")).isEqualTo(2);
		assertThat(count("log_record")).isEqualTo(2);
		assertThat(count("txn")).isEqualTo(2);
		assertThat(count("span")).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM span s WHERE NOT EXISTS (SELECT 1 FROM txn t WHERE t.id = s.txn_id)")
			.query(Long.class).single()).isZero();
		assertThat(count("uptime_check")).isEqualTo(2);
		assertThat(count("uptime_incident")).isEqualTo(2);
		assertThat(jdbc.sql("SELECT count(*) FROM uptime_incident WHERE closed_at IS NULL")
			.query(Long.class).single()).isEqualTo(1);

		assertThat(jdbc.sql("SELECT count(*) FROM issue WHERE id = ?").param(staleIssue).query(Long.class).single())
			.isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM issue WHERE id = ?").param(emptyIssue).query(Long.class).single())
			.isZero();
		Map<String, Object> issue = jdbc.sql("""
				SELECT fingerprint, title, culprit, status, level, event_count, first_seen, last_seen
				FROM issue WHERE id = ?
				""").param(survivingIssue).query((rs, rowNum) -> Map.<String, Object>of(
					"fingerprint", rs.getString("fingerprint"), "title", rs.getString("title"),
					"culprit", rs.getString("culprit"), "status", rs.getString("status"),
					"level", rs.getString("level"), "event_count", rs.getLong("event_count"),
					"first_seen", rs.getTimestamp("first_seen").toInstant(),
					"last_seen", rs.getTimestamp("last_seen").toInstant())).single();
		assertThat(issue).containsEntry("fingerprint", "survivor")
			.containsEntry("title", "Preserved title")
			.containsEntry("culprit", "preserved culprit")
			.containsEntry("status", "resolved")
			.containsEntry("level", "warning")
			.containsEntry("event_count", 2L)
			.containsEntry("first_seen", at)
			.containsEntry("last_seen", after);
		List<Map<String, Object>> envStats = jdbc.sql("""
				SELECT environment, event_count, last_seen FROM issue_env_stats
				WHERE issue_id = ? ORDER BY environment
				""").param(survivingIssue).query((rs, rowNum) -> Map.<String, Object>of(
					"environment", rs.getString("environment"), "event_count", rs.getLong("event_count"),
					"last_seen", rs.getTimestamp("last_seen").toInstant())).list();
		assertThat(envStats).containsExactly(
				Map.of("environment", "prod", "event_count", 1L, "last_seen", at),
				Map.of("environment", "staging", "event_count", 1L, "last_seen", after));

		for (String table : List.of("project", "project_key", "environment", "release", "app_user", "api_token",
				"uptime_monitor", "artifact_bundle", "artifact_bundle_release", "artifact")) {
			assertThat(count(table)).as(table).isEqualTo(1);
		}
	}

	@Test
	void projectDeleteWaitsForEventIssueAggregateRebuild() throws Exception {
		Instant seen = Instant.parse("2026-07-19T01:00:00Z");
		long projectId = insertProject();
		partitions.ensurePartition(PartitionManager.EVENT, seen);
		long issueId = insertIssue(projectId, "deleted-project", "Deleted project", null, "unresolved", seen);
		insertEvent(projectId, issueId, seen, "prod", "error");

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			CompletableFuture<ResponseEntity<Void>> deletion = new TransactionTemplate(transactionManager)
				.execute(status -> {
					eventIssueLock.acquire();
					CompletableFuture<ResponseEntity<Void>> request = CompletableFuture.supplyAsync(
							() -> rest.exchange(url("/api/internal/projects/" + projectId), HttpMethod.DELETE,
									new HttpEntity<>(headers(adminCookie)), Void.class),
							executor);
					awaitEventIssueLockWaiter();
					assertThat(count("event")).isEqualTo(1);
					assertThat(count("project")).isEqualTo(1);
					return request;
				});

			assertThat(deletion).isNotNull();
			assertThat(deletion.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		}
		assertThat(count("event")).isZero();
		assertThat(count("project")).isZero();
	}

	private long insertProject() {
		return jdbc.sql("INSERT INTO project (slug, name) VALUES ('shop', 'Shop') RETURNING id")
			.query(Long.class)
			.single();
	}

	private void seedRetainedConfiguration(long projectId) {
		jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, '0123456789abcdef0123456789abcdef')")
			.param(projectId).update();
		jdbc.sql("INSERT INTO environment (project_id, name) VALUES (?, 'prod')").param(projectId).update();
		jdbc.sql("INSERT INTO release (project_id, version) VALUES (?, 'shop@1.0.0')").param(projectId).update();
		jdbc.sql("INSERT INTO api_token (name, token_hash) VALUES ('ci', 'hash')").update();
		long bundle = jdbc.sql("INSERT INTO artifact_bundle (checksum, raw) VALUES ('checksum', ?::bytea) RETURNING id")
			.param(new byte[] { 1 }).query(Long.class).single();
		jdbc.sql("INSERT INTO artifact_bundle_release (bundle_id, project_id, release) VALUES (?, ?, 'shop@1.0.0')")
			.param(bundle).param(projectId).update();
		jdbc.sql("""
				INSERT INTO artifact (bundle_id, debug_id, artifact_type, file_path, content)
				VALUES (?, '12345678-1234-1234-1234-123456789abc', 'source_map', '~/main.js.map', ?::bytea)
				""").param(bundle).param(new byte[] { 2 }).update();
	}

	private long insertIssue(long projectId, String fingerprint, String title, String culprit, String status,
			Instant seen) {
		return jdbc.sql("""
				INSERT INTO issue (project_id, fingerprint, title, culprit, level, status, first_seen, last_seen, event_count)
				VALUES (?, ?, ?, ?, 'fatal', ?, ?, ?, 99) RETURNING id
				""").param(projectId).param(fingerprint).param(title).param(culprit).param(status).param(timestamp(seen))
			.param(timestamp(seen))
			.query(Long.class).single();
	}

	private void insertEvent(long projectId, long issueId, Instant timestamp, String environment, String level) {
		jdbc.sql("""
				INSERT INTO event (id, project_id, issue_id, environment, "timestamp", level, data)
				VALUES (?, ?, ?, ?, ?, ?, '{}'::jsonb)
				""").param(UUID.randomUUID()).param(projectId).param(issueId).param(environment)
			.param(timestamp(timestamp)).param(level)
			.update();
	}

	private void insertLog(long projectId, Instant timestamp, String body) {
		jdbc.sql("""
				INSERT INTO log_record (id, project_id, environment, "timestamp", level, body)
				VALUES (?, ?, 'prod', ?, 'info', ?)
				""").param(UUID.randomUUID()).param(projectId).param(timestamp(timestamp)).param(body).update();
	}

	private UUID insertTransaction(long projectId, Instant timestamp, String name) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO txn (id, project_id, environment, trace_id, span_id, name, start_ts, end_ts, duration_ms)
				VALUES (?, ?, 'prod', ?, ?, ?, ?, ?, 1000)
				""").param(id).param(projectId).param(UUID.randomUUID().toString()).param(UUID.randomUUID().toString())
			.param(name).param(timestamp(timestamp)).param(timestamp(timestamp.plusSeconds(1))).update();
		return id;
	}

	private void insertSpan(long projectId, UUID txnId, Instant timestamp, String description) {
		jdbc.sql("""
				INSERT INTO span (id, txn_id, project_id, trace_id, span_id, description, start_ts, end_ts, duration_ms)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1000)
				""").param(UUID.randomUUID()).param(txnId).param(projectId).param(UUID.randomUUID().toString())
			.param(UUID.randomUUID().toString()).param(description).param(timestamp(timestamp))
			.param(timestamp(timestamp.plusSeconds(1)))
			.update();
	}

	private void insertCheck(long monitorId, Instant timestamp) {
		jdbc.sql("""
				INSERT INTO uptime_check (monitor_id, checked_at, success, status_code, latency_ms)
				VALUES (?, ?, true, 200, 10)
				""").param(monitorId).param(timestamp(timestamp)).update();
	}

	private Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}

	private void postUser(String email, String password) {
		HttpHeaders headers = headers(adminCookie);
		ResponseEntity<Map> response = rest.exchange(url("/api/internal/users"), HttpMethod.POST,
				new HttpEntity<>(Map.of("email", email, "password", password, "role", "member"), headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private String login(String email, String password) {
		ResponseEntity<Map> response = rest.postForEntity(url("/api/internal/auth/login"),
				Map.of("email", email, "password", password), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
	}

	private ResponseEntity<Map> exchange(HttpMethod method, Map<String, ?> body, String cookie) {
		return rest.exchange(url("/api/internal/settings/data-retention"), method,
				new HttpEntity<>(body, headers(cookie)), Map.class);
	}

	private HttpHeaders headers(String cookie) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, cookie);
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	private long count(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
	}

	private void awaitEventIssueLockWaiter() {
		Instant deadline = Instant.now().plusSeconds(5);
		while (Instant.now().isBefore(deadline)) {
			long waiters = jdbc.sql("""
					SELECT count(*) FROM pg_locks waiting
					WHERE waiting.locktype = 'advisory' AND NOT waiting.granted
					  AND (waiting.classid, waiting.objid, waiting.objsubid) IN (
					      SELECT held.classid, held.objid, held.objsubid FROM pg_locks held
					      WHERE held.locktype = 'advisory' AND held.granted AND held.pid = pg_backend_pid()
					  )
					""").query(Long.class).single();
			if (waiters > 0) {
				return;
			}
			try {
				Thread.sleep(10);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("interrupted while waiting for project deletion", e);
			}
		}
		throw new AssertionError("project deletion did not wait for the event/issue lock");
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}
}
