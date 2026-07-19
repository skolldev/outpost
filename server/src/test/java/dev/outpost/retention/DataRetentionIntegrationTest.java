package dev.outpost.retention;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import dev.outpost.pipeline.EventIssueLock;
import dev.outpost.pipeline.EventStore;
import dev.outpost.pipeline.ProcessedEvent;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
		"outpost.retention.event-chunk-size=2", "outpost.retention.chunk-timeout-seconds=1" })
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
	DataRetentionService cleanup;

	@Autowired
	EventIssueLock eventIssueLock;

	@Autowired
	EventStore eventStore;

	@Autowired
	ObjectMapper mapper;

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
	void disabledPolicyKeepsTelemetryButStillCapsUptimeHistoryAtNinetyDays() {
		Instant run = Instant.parse("2026-07-19T02:00:00Z");
		Instant beyondDefault = run.minus(Duration.ofDays(91));
		Instant withinDefault = run.minus(Duration.ofDays(89));
		long projectId = insertProject();
		partitions.ensurePartition(PartitionManager.LOG_RECORD, beyondDefault);
		insertLog(projectId, beyondDefault, "kept while disabled");
		long monitorId = jdbc.sql("""
				INSERT INTO uptime_monitor (project_id, environment, url, interval_seconds, next_check_at)
				VALUES (?, 'prod', 'https://example.test/health', 3600, now() + interval '1 day') RETURNING id
				""").param(projectId).query(Long.class).single();
		insertCheck(monitorId, beyondDefault);
		insertCheck(monitorId, withinDefault);
		jdbc.sql("INSERT INTO uptime_incident (monitor_id, opened_at, closed_at) VALUES (?, ?, ?)")
			.param(monitorId).param(timestamp(beyondDefault.minusSeconds(10))).param(timestamp(beyondDefault)).update();
		jdbc.sql("INSERT INTO uptime_incident (monitor_id, opened_at, closed_at) VALUES (?, ?, ?)")
			.param(monitorId).param(timestamp(withinDefault.minusSeconds(10))).param(timestamp(withinDefault)).update();
		jdbc.sql("INSERT INTO uptime_incident (monitor_id, opened_at) VALUES (?, ?)")
			.param(monitorId).param(timestamp(beyondDefault)).update();
		// A 30-day policy that is switched off must not shrink the uptime window.
		settings.save(false, 30);

		scheduler.runOnce(run);

		assertThat(count("log_record")).isEqualTo(1);
		assertThat(count("uptime_check")).isEqualTo(1);
		assertThat(count("uptime_incident")).isEqualTo(2);
		assertThat(jdbc.sql("SELECT count(*) FROM uptime_incident WHERE closed_at IS NULL")
			.query(Long.class).single()).isEqualTo(1);
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
					eventIssueLock.acquire(projectId);
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

	@Test
	void mixedIngestBatchCommitsUnlockedProjectBeforeBusyProject() throws Exception {
		Instant seen = Instant.parse("2026-07-19T01:00:00Z");
		long availableProject = insertProject("available");
		long busyProject = insertProject("busy");
		CountDownLatch acquired = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			CompletableFuture<Void> holder = holdProjectLock(busyProject, acquired, release, executor);
			assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
			CompletableFuture<Void> storage = CompletableFuture.runAsync(() -> eventStore.store(List.of(
					processedEvent(availableProject, seen, "available"),
					processedEvent(busyProject, seen, "busy"))), executor);
			try {
				await(() -> eventCount(availableProject) == 1);
				assertThat(eventCount(busyProject)).isZero();
				assertThat(storage).isNotDone();
			}
			finally {
				release.countDown();
			}
			holder.get(5, TimeUnit.SECONDS);
			storage.get(5, TimeUnit.SECONDS);
		}
		assertThat(eventCount(availableProject)).isEqualTo(1);
		assertThat(eventCount(busyProject)).isEqualTo(1);
	}

	@Test
	void retentionUsesMultipleChunksAndRebuildsAggregates() {
		Instant cutoff = Instant.parse("2026-05-20T02:00:00Z");
		Instant retained = cutoff.plusSeconds(1);
		long projectId = insertProject();
		for (Instant instant : List.of(cutoff.minusSeconds(3), cutoff.minusSeconds(2), cutoff.minusSeconds(1),
				retained)) {
			partitions.ensurePartition(PartitionManager.EVENT, instant);
		}
		long issueId = insertIssue(projectId, "chunked", "Chunked", null, "unresolved", cutoff.minusSeconds(3));
		insertEvent(projectId, issueId, cutoff.minusSeconds(3), "prod", "error");
		insertEvent(projectId, issueId, cutoff.minusSeconds(2), "prod", "error");
		insertEvent(projectId, issueId, cutoff.minusSeconds(1), "staging", "warning");
		insertEvent(projectId, issueId, retained, "staging", "info");

		DataRetentionService.CleanupResult result = cleanup.cleanup(cutoff);

		assertThat(result.events()).isEqualTo(3);
		assertThat(result.deferredProjects()).isZero();
		assertThat(jdbc.sql("SELECT event_count FROM issue WHERE id = ?")
			.param(issueId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT first_seen FROM issue WHERE id = ?")
			.param(issueId).query(Timestamp.class).single().toInstant()).isEqualTo(retained);
		assertThat(jdbc.sql("SELECT environment, event_count FROM issue_env_stats WHERE issue_id = ?")
			.param(issueId)
			.query((rs, rowNum) -> Map.of("environment", rs.getString(1), "event_count", rs.getLong(2)))
			.list()).containsExactly(Map.of("environment", "staging", "event_count", 1L));
	}

	@Test
	void retentionDefersBusyProjectAndContinuesOtherCleanup() throws Exception {
		Instant cutoff = Instant.parse("2026-05-20T02:00:00Z");
		Instant expired = cutoff.minusSeconds(1);
		Instant expiredOldWeek = Instant.parse("2026-05-06T12:00:00Z"); // week 05-04, entirely expired
		long busyProject = insertProject("busy");
		long availableProject = insertProject("available");
		for (String table : List.of(PartitionManager.EVENT, PartitionManager.LOG_RECORD)) {
			partitions.ensurePartition(table, expired);
		}
		partitions.ensurePartition(PartitionManager.EVENT, expiredOldWeek);
		long busyIssue = insertIssue(busyProject, "busy", "Busy", null, "unresolved", expired);
		insertEvent(busyProject, busyIssue, expired, "prod", "error");
		insertEvent(busyProject, busyIssue, expiredOldWeek, "prod", "error");
		long availableIssue = insertIssue(availableProject, "available", "Available", null, "unresolved", expired);
		insertEvent(availableProject, availableIssue, expired, "prod", "error");
		insertLog(busyProject, expired, "expired despite busy event cleanup");
		CountDownLatch acquired = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			CompletableFuture<Void> holder = holdProjectLock(busyProject, acquired, release, executor);
			assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
			DataRetentionService.CleanupResult result;
			try {
				result = cleanup.cleanup(cutoff);
			}
			finally {
				release.countDown();
			}
			holder.get(5, TimeUnit.SECONDS);
			assertThat(result.events()).isEqualTo(1);
			assertThat(result.logs()).isEqualTo(1);
			assertThat(result.deferredProjects()).isEqualTo(1);
		}
		assertThat(eventCount(busyProject)).isEqualTo(2);
		assertThat(eventCount(availableProject)).isZero();
		assertThat(count("log_record")).isZero();
		// A deferred project may still own rows in expired event weeks, so no
		// event partition is dropped this run.
		assertThat(partitionExists("event_p20260504")).isTrue();
	}

	@Test
	void timedOutProjectChunkRollsBackAndOtherProjectsContinue() {
		Instant cutoff = Instant.parse("2026-05-20T02:00:00Z");
		Instant expired = cutoff.minusSeconds(1);
		long slowProject = insertProject("slow");
		long availableProject = insertProject("available");
		partitions.ensurePartition(PartitionManager.EVENT, expired);
		long slowIssue = insertIssue(slowProject, "slow", "Slow", null, "unresolved", expired);
		insertEvent(slowProject, slowIssue, expired, "prod", "error");
		long availableIssue = insertIssue(availableProject, "available", "Available", null, "unresolved", expired);
		insertEvent(availableProject, availableIssue, expired, "prod", "error");
		jdbc.sql("""
				CREATE FUNCTION retention_test_delay() RETURNS trigger LANGUAGE plpgsql AS $function$
				BEGIN
				    IF OLD.project_id = %d THEN
				        PERFORM pg_sleep(2);
				    END IF;
				    RETURN OLD;
				END
				$function$
				""".formatted(slowProject)).update();
		jdbc.sql("""
				CREATE TRIGGER retention_test_delay_before_delete BEFORE DELETE ON event
				FOR EACH ROW EXECUTE FUNCTION retention_test_delay()
				""").update();
		try {
			DataRetentionService.CleanupResult result = cleanup.cleanup(cutoff);
			assertThat(result.events()).isEqualTo(1);
			assertThat(result.deferredProjects()).isEqualTo(1);
			assertThat(eventCount(slowProject)).isEqualTo(1);
			assertThat(eventCount(availableProject)).isZero();
		}
		finally {
			jdbc.sql("DROP TRIGGER retention_test_delay_before_delete ON event").update();
			jdbc.sql("DROP FUNCTION retention_test_delay()").update();
		}
	}

	@Test
	void retentionDropsFullyExpiredPartitionsAndPrunesBoundary() {
		Instant cutoff = Instant.parse("2026-05-20T02:00:00Z"); // Wed; week Mon 2026-05-18..05-25
		Instant old = Instant.parse("2026-05-06T12:00:00Z"); // week 05-04..05-11, entirely expired
		Instant before = cutoff.minusSeconds(1); // boundary week, expired
		Instant after = cutoff.plusSeconds(1); // boundary week, retained
		Instant future = Instant.parse("2026-06-01T12:00:00Z"); // week 06-01.., retained
		long projectId = insertProject();
		for (Instant instant : List.of(old, before, after, future)) {
			partitions.ensurePartition(PartitionManager.LOG_RECORD, instant);
		}
		for (Instant instant : List.of(old, after)) {
			partitions.ensurePartition(PartitionManager.TXN, instant);
			partitions.ensurePartition(PartitionManager.SPAN, instant);
			partitions.ensurePartition(PartitionManager.EVENT, instant);
		}
		long issueId = insertIssue(projectId, "boundary", "Boundary", null, "unresolved", old);
		insertEvent(projectId, issueId, old, "prod", "error");
		insertEvent(projectId, issueId, after, "prod", "error");
		insertLog(projectId, old, "old");
		insertLog(projectId, before, "before");
		insertLog(projectId, after, "after");
		insertLog(projectId, future, "future");
		UUID oldTxn = insertTransaction(projectId, old, "old");
		UUID keptTxn = insertTransaction(projectId, after, "kept");
		insertSpan(projectId, oldTxn, old, "old-span");
		insertSpan(projectId, keptTxn, after, "kept-span");

		DataRetentionService.CleanupResult result = cleanup.cleanup(cutoff);

		// Whole expired weeks are dropped, not row-deleted. The event week is
		// emptied by the per-project pass first, then dropped like the others.
		assertThat(partitionExists("log_record_p20260504")).isFalse();
		assertThat(partitionExists("txn_p20260504")).isFalse();
		assertThat(partitionExists("span_p20260504")).isFalse();
		assertThat(partitionExists("event_p20260504")).isFalse();
		// The boundary and future weeks survive as partitions.
		assertThat(partitionExists("log_record_p20260518")).isTrue();
		assertThat(partitionExists("log_record_p20260601")).isTrue();
		assertThat(partitionExists("event_p20260518")).isTrue();
		assertThat(result.droppedPartitions()).isGreaterThanOrEqualTo(4);
		assertThat(count("event")).isEqualTo(1); // the retained boundary-week event

		// Boundary rows are pruned at the exact cutoff; retained/future rows stay.
		assertThat(count("log_record")).isEqualTo(2); // after + future
		assertThat(count("txn")).isEqualTo(1); // keptTxn
		assertThat(count("span")).isEqualTo(1); // kept-span
		assertThat(jdbc.sql("SELECT count(*) FROM span s WHERE NOT EXISTS (SELECT 1 FROM txn t WHERE t.id = s.txn_id)")
			.query(Long.class).single()).isZero();
	}

	@Test
	void retentionDeletesRetainedSpanOrphanedByDroppedTxnPartition() {
		Instant cutoff = Instant.parse("2026-05-20T02:00:00Z"); // boundary week Mon 2026-05-18..05-25
		Instant old = Instant.parse("2026-05-06T12:00:00Z"); // week 05-04..05-11, txn partition is dropped
		Instant after = cutoff.plusSeconds(1); // boundary week, span is retained
		long projectId = insertProject();
		partitions.ensurePartition(PartitionManager.TXN, old);
		partitions.ensurePartition(PartitionManager.TXN, after);
		partitions.ensurePartition(PartitionManager.SPAN, after);
		// The owning txn lives in a fully-expired week (dropped wholesale); its span
		// starts after the cutoff, so its own partition survives -> a cross-week orphan.
		UUID droppedTxn = insertTransaction(projectId, old, "dropped");
		UUID keptTxn = insertTransaction(projectId, after, "kept");
		insertSpan(projectId, droppedTxn, after, "cross-week-orphan");
		insertSpan(projectId, keptTxn, after, "kept-span");

		cleanup.cleanup(cutoff);

		assertThat(partitionExists("txn_p20260504")).isFalse(); // droppedTxn gone with the partition
		assertThat(count("span")).isEqualTo(1); // orphan removed, kept-span survives
		assertThat(jdbc.sql("SELECT description FROM span").query(String.class).single()).isEqualTo("kept-span");
		assertThat(jdbc.sql("SELECT count(*) FROM span s WHERE NOT EXISTS (SELECT 1 FROM txn t WHERE t.id = s.txn_id)")
			.query(Long.class).single()).isZero();
	}

	@Test
	void retentionDropsPartitionWhoseUpperBoundEqualsCutoffButKeepsTheNext() {
		Instant cutoff = Instant.parse("2026-05-18T00:00:00Z"); // Monday midnight = a week boundary
		Instant priorWeek = Instant.parse("2026-05-14T12:00:00Z"); // week 05-11..05-18, upper bound == cutoff
		Instant atCutoff = cutoff; // first instant of the 05-18..05-25 week, retained
		long projectId = insertProject();
		partitions.ensurePartition(PartitionManager.LOG_RECORD, priorWeek);
		partitions.ensurePartition(PartitionManager.LOG_RECORD, atCutoff);
		insertLog(projectId, priorWeek, "prior");
		insertLog(projectId, atCutoff, "at-cutoff");

		cleanup.cleanup(cutoff);

		// upper bound (2026-05-18T00:00Z) <= cutoff -> dropped; the next week is kept.
		assertThat(partitionExists("log_record_p20260511")).isFalse();
		assertThat(partitionExists("log_record_p20260518")).isTrue();
		assertThat(count("log_record")).isEqualTo(1); // the at-cutoff row (>= cutoff) survives
	}

	@Test
	void onlyIfEmptyDropKeepsExpiredEventPartitionHoldingALateArrival() {
		Instant cutoff = Instant.parse("2026-05-20T02:00:00Z");
		Instant old = Instant.parse("2026-05-06T12:00:00Z"); // week 05-04..05-11, entirely expired
		long projectId = insertProject();
		partitions.ensurePartition(PartitionManager.EVENT, old);
		long issueId = insertIssue(projectId, "late", "Late arrival", null, "unresolved", old);
		// A stale-timestamped event committed after the per-project pass: the
		// partition is expired but not empty, so it must survive the drop.
		insertEvent(projectId, issueId, old, "prod", "error");

		assertThat(partitions.dropExpiredPartitions(PartitionManager.EVENT, cutoff, 5, true)).isZero();

		assertThat(partitionExists("event_p20260504")).isTrue();
		assertThat(count("event")).isEqualTo(1);

		// Once the row is gone (next run's locked cleanup), the drop proceeds.
		jdbc.sql("DELETE FROM event").update();
		assertThat(partitions.dropExpiredPartitions(PartitionManager.EVENT, cutoff, 5, true)).isEqualTo(1);
		assertThat(partitionExists("event_p20260504")).isFalse();
	}

	private long insertProject() {
		return insertProject("shop");
	}

	private long insertProject(String slug) {
		return jdbc.sql("INSERT INTO project (slug, name) VALUES (?, ?) RETURNING id")
			.param(slug)
			.param(slug)
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

	private ProcessedEvent processedEvent(long projectId, Instant timestamp, String fingerprint) {
		return new ProcessedEvent(UUID.randomUUID(), projectId, "prod", null, timestamp, null, "error",
				fingerprint, null, fingerprint, null, fingerprint, null, mapper.createObjectNode(), null, null);
	}

	private CompletableFuture<Void> holdProjectLock(long projectId, CountDownLatch acquired, CountDownLatch release,
			ExecutorService executor) {
		return CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			eventIssueLock.acquire(projectId);
			acquired.countDown();
			await(release);
		}), executor);
	}

	private void await(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("interrupted while holding project lock", e);
		}
	}

	private void await(BooleanSupplier condition) {
		Instant deadline = Instant.now().plusSeconds(5);
		while (Instant.now().isBefore(deadline)) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(10);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("interrupted while waiting for condition", e);
			}
		}
		throw new AssertionError("condition was not met before timeout");
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

	private boolean partitionExists(String partition) {
		return jdbc.sql("SELECT to_regclass(?) IS NOT NULL").param(partition).query(Boolean.class).single();
	}

	private long eventCount(long projectId) {
		return jdbc.sql("SELECT count(*) FROM event WHERE project_id = ?")
			.param(projectId)
			.query(Long.class)
			.single();
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
