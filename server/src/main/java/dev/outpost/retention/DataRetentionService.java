package dev.outpost.retention;

import dev.outpost.pipeline.EventIssueLock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Deletes expired telemetry and rebuilds aggregates affected by event expiry. */
@Service
public class DataRetentionService {

	public record CleanupResult(int events, int issues, int logs, int transactions, int spans, int uptimeChecks,
			int uptimeIncidents) {
	}

	private final JdbcClient jdbc;
	private final TransactionTemplate transaction;
	private final EventIssueLock eventIssueLock;

	public DataRetentionService(JdbcClient jdbc, PlatformTransactionManager transactionManager,
			EventIssueLock eventIssueLock) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.eventIssueLock = eventIssueLock;
	}

	/** Runs the complete cleanup in one transaction using one captured cutoff. */
	public CleanupResult cleanup(Instant cutoff) {
		Timestamp timestamp = Timestamp.from(cutoff);
		return Objects.requireNonNull(transaction.execute(status -> cleanup(timestamp)));
	}

	private CleanupResult cleanup(Timestamp cutoff) {
		eventIssueLock.acquire();
		jdbc.sql("""
				CREATE TEMPORARY TABLE retention_affected_issue (
				    issue_id bigint PRIMARY KEY
				) ON COMMIT DROP
				""").update();
		jdbc.sql("""
				INSERT INTO retention_affected_issue (issue_id)
				SELECT DISTINCT issue_id FROM event WHERE "timestamp" < ?
				""").param(cutoff).update();

		int events = jdbc.sql("DELETE FROM event WHERE \"timestamp\" < ?").param(cutoff).update();
		int issues = jdbc.sql("""
				DELETE FROM issue i
				WHERE NOT EXISTS (SELECT 1 FROM event e WHERE e.issue_id = i.id)
				""").update();

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

		return new CleanupResult(events, issues, logs, transactions, spans, uptimeChecks, uptimeIncidents);
	}
}
