package dev.outpost.retention;

import dev.outpost.pipeline.EventIssueLock;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

/** Deletes expired telemetry and rebuilds aggregates affected by event expiry. */
@Service
public class DataRetentionService {

	private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);

	public record CleanupResult(int events, int issues, int logs, int transactions, int spans, int uptimeChecks,
			int uptimeIncidents, int deferredProjects) {
	}

	private record ProjectChunk(int events, int issues, boolean acquired) {
	}

	private record TelemetryCleanup(int logs, int transactions, int spans, int uptimeChecks, int uptimeIncidents) {
	}

	private final JdbcClient jdbc;
	private final TransactionTemplate projectChunkTransaction;
	private final TransactionTemplate telemetryTransaction;
	private final EventIssueLock eventIssueLock;
	private final int eventChunkSize;

	public DataRetentionService(JdbcClient jdbc, PlatformTransactionManager transactionManager,
			EventIssueLock eventIssueLock,
			@Value("${outpost.retention.event-chunk-size:10000}") int eventChunkSize,
			@Value("${outpost.retention.chunk-timeout-seconds:30}") int chunkTimeoutSeconds) {
		if (eventChunkSize < 1) {
			throw new IllegalArgumentException("outpost.retention.event-chunk-size must be positive");
		}
		if (chunkTimeoutSeconds < 1) {
			throw new IllegalArgumentException("outpost.retention.chunk-timeout-seconds must be positive");
		}
		this.jdbc = jdbc;
		this.projectChunkTransaction = new TransactionTemplate(transactionManager);
		this.projectChunkTransaction.setTimeout(chunkTimeoutSeconds);
		this.telemetryTransaction = new TransactionTemplate(transactionManager);
		this.eventIssueLock = eventIssueLock;
		this.eventChunkSize = eventChunkSize;
	}

	/** Runs bounded event cleanup per project, then cleans unrelated telemetry without the event lock. */
	public CleanupResult cleanup(Instant cutoff) {
		Timestamp timestamp = Timestamp.from(cutoff);
		int events = 0;
		int issues = 0;
		int deferredProjects = 0;
		List<Long> projectIds = jdbc.sql("SELECT id FROM project ORDER BY id").query(Long.class).list();

		for (long projectId : projectIds) {
			while (true) {
				ProjectChunk chunk;
				try {
					chunk = Objects.requireNonNull(projectChunkTransaction
						.execute(status -> cleanupProjectChunk(projectId, timestamp)));
				}
				catch (RuntimeException e) {
					if (!isTimeout(e)) {
						throw e;
					}
					deferredProjects++;
					log.warn("deferring retention for project {} after a cleanup chunk timed out", projectId);
					break;
				}
				if (!chunk.acquired()) {
					deferredProjects++;
					log.info("deferring retention for busy project {}", projectId);
					break;
				}
				events += chunk.events();
				issues += chunk.issues();
				if (chunk.events() < eventChunkSize) {
					break;
				}
			}
		}

		TelemetryCleanup telemetry = Objects.requireNonNull(
				telemetryTransaction.execute(status -> cleanupTelemetry(timestamp)));
		return new CleanupResult(events, issues, telemetry.logs(), telemetry.transactions(), telemetry.spans(),
				telemetry.uptimeChecks(), telemetry.uptimeIncidents(), deferredProjects);
	}

	private ProjectChunk cleanupProjectChunk(long projectId, Timestamp cutoff) {
		if (!eventIssueLock.tryAcquire(projectId)) {
			return new ProjectChunk(0, 0, false);
		}
		jdbc.sql("""
				CREATE TEMPORARY TABLE retention_expired_event (
				    id uuid NOT NULL,
				    "timestamp" timestamptz NOT NULL,
				    issue_id bigint NOT NULL,
				    PRIMARY KEY (id, "timestamp")
				) ON COMMIT DROP
				""").update();
		jdbc.sql("""
				INSERT INTO retention_expired_event (id, "timestamp", issue_id)
				SELECT id, "timestamp", issue_id FROM event
				WHERE project_id = ? AND "timestamp" < ?
				ORDER BY "timestamp", id
				LIMIT ?
				""").param(projectId).param(cutoff).param(eventChunkSize).update();
		jdbc.sql("""
				CREATE TEMPORARY TABLE retention_affected_issue (
				    issue_id bigint PRIMARY KEY
				) ON COMMIT DROP
				""").update();
		jdbc.sql("""
				INSERT INTO retention_affected_issue (issue_id)
				SELECT DISTINCT issue_id FROM retention_expired_event
				""").update();

		int events = jdbc.sql("""
				DELETE FROM event e USING retention_expired_event expired
				WHERE e.id = expired.id AND e."timestamp" = expired."timestamp"
				""").update();
		int issues = jdbc.sql("""
				DELETE FROM issue i
				WHERE i.project_id = ?
				  AND NOT EXISTS (SELECT 1 FROM event e WHERE e.issue_id = i.id)
				""").param(projectId).update();

		jdbc.sql("""
				UPDATE issue i SET
				    event_count = aggregate.event_count,
				    first_seen = aggregate.first_seen,
				    last_seen = aggregate.last_seen,
				    level = COALESCE(aggregate.latest_level, i.level)
				FROM (
				    SELECT e.issue_id,
				           count(*) AS event_count,
				           min(e."timestamp") AS first_seen,
				           max(e."timestamp") AS last_seen,
				           (array_agg(e.level ORDER BY e."timestamp" DESC, e.id DESC)
				               FILTER (WHERE e.level IS NOT NULL))[1] AS latest_level
				    FROM event e
				    JOIN retention_affected_issue affected ON affected.issue_id = e.issue_id
				    GROUP BY e.issue_id
				) aggregate
				WHERE i.id = aggregate.issue_id
				""").update();
		jdbc.sql("""
				DELETE FROM issue_env_stats stats
				USING retention_affected_issue affected
				WHERE stats.issue_id = affected.issue_id
				""").update();
		jdbc.sql("""
				INSERT INTO issue_env_stats (issue_id, environment, event_count, last_seen)
				SELECT e.issue_id, e.environment, count(*), max(e."timestamp")
				FROM event e
				JOIN retention_affected_issue affected ON affected.issue_id = e.issue_id
				JOIN issue i ON i.id = e.issue_id
				GROUP BY e.issue_id, e.environment
				""").update();

		return new ProjectChunk(events, issues, true);
	}

	private TelemetryCleanup cleanupTelemetry(Timestamp cutoff) {
		int logs = jdbc.sql("DELETE FROM log_record WHERE \"timestamp\" < ?").param(cutoff).update();
		int transactions = jdbc.sql("DELETE FROM txn WHERE start_ts < ?").param(cutoff).update();
		int spans = jdbc.sql("""
				DELETE FROM span s
				WHERE s.start_ts < ?
				   OR NOT EXISTS (SELECT 1 FROM txn t WHERE t.id = s.txn_id)
				""").param(cutoff).update();
		int uptimeChecks = jdbc.sql("DELETE FROM uptime_check WHERE checked_at < ?").param(cutoff).update();
		int uptimeIncidents = jdbc
			.sql("DELETE FROM uptime_incident WHERE closed_at IS NOT NULL AND closed_at < ?")
			.param(cutoff)
			.update();
		return new TelemetryCleanup(logs, transactions, spans, uptimeChecks, uptimeIncidents);
	}

	private boolean isTimeout(RuntimeException exception) {
		if (exception instanceof TransactionTimedOutException || exception instanceof QueryTimeoutException) {
			return true;
		}
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sqlException && "57014".equals(sqlException.getSQLState())) {
				return true;
			}
		}
		return false;
	}
}
