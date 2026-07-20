package dev.outpost.retention;

import dev.outpost.db.PartitionManager;
import dev.outpost.pipeline.EventIssueLock;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
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

	public record CleanupResult(int events, int issues, int logs, int transactions, int spans, int droppedPartitions,
			int uptimeChecks, int uptimeIncidents, int deferredProjects) {
	}

	public record UptimeCleanup(int checks, int incidents) {
	}

	private record ProjectChunk(int events, int issues, boolean acquired) {
	}

	private record TelemetryCleanup(int logs, int transactions, int spans, int droppedPartitions, int uptimeChecks,
			int uptimeIncidents) {
	}

	private record TxnSpanCleanup(int transactions, int spans) {
	}

	private final JdbcClient jdbc;
	private final PartitionManager partitions;
	private final TransactionTemplate projectChunkTransaction;
	private final TransactionTemplate telemetryTransaction;
	private final EventIssueLock eventIssueLock;
	private final int eventChunkSize;
	private final int chunkTimeoutSeconds;

	public DataRetentionService(JdbcClient jdbc, PlatformTransactionManager transactionManager,
			PartitionManager partitions, EventIssueLock eventIssueLock,
			@Value("${outpost.retention.event-chunk-size:10000}") int eventChunkSize,
			@Value("${outpost.retention.chunk-timeout-seconds:30}") int chunkTimeoutSeconds) {
		if (eventChunkSize < 1) {
			throw new IllegalArgumentException("outpost.retention.event-chunk-size must be positive");
		}
		if (chunkTimeoutSeconds < 1) {
			throw new IllegalArgumentException("outpost.retention.chunk-timeout-seconds must be positive");
		}
		this.jdbc = jdbc;
		this.partitions = partitions;
		this.projectChunkTransaction = new TransactionTemplate(transactionManager);
		this.projectChunkTransaction.setTimeout(chunkTimeoutSeconds);
		this.telemetryTransaction = new TransactionTemplate(transactionManager);
		this.telemetryTransaction.setTimeout(chunkTimeoutSeconds);
		this.eventIssueLock = eventIssueLock;
		this.eventChunkSize = eventChunkSize;
		this.chunkTimeoutSeconds = chunkTimeoutSeconds;
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

		// With no deferrals, every event below the cutoff was deleted and its issue
		// aggregates rebuilt, so fully-expired event weeks are empty and can be
		// dropped. A deferred project may still own rows in them; leave the
		// partitions for the next run in that case. The drop is only-if-empty:
		// ingestion can commit a stale-timestamped event into an expired week
		// after the per-project pass, and dropping it here would bypass the event
		// lock and strand its just-incremented issue aggregates.
		int droppedEventPartitions = deferredProjects == 0
				? runStep("event partition drop", 0,
						() -> partitions.dropExpiredPartitions(PartitionManager.EVENT, cutoff, chunkTimeoutSeconds, true))
				: 0;

		TelemetryCleanup telemetry = cleanupTelemetry(cutoff, timestamp);
		return new CleanupResult(events, issues, telemetry.logs(), telemetry.transactions(), telemetry.spans(),
				droppedEventPartitions + telemetry.droppedPartitions(), telemetry.uptimeChecks(),
				telemetry.uptimeIncidents(), deferredProjects);
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

	/**
	 * Retires expired telemetry. The partitioned tables (log_record, txn, span)
	 * shed whole expired weeks by dropping partitions — reclaiming disk instantly
	 * instead of row-deleting — and row-delete only the single boundary partition
	 * straddling the cutoff, so the effective cutoff stays exact. Each step runs
	 * in its own timed transaction, so none pins the vacuum horizon and a timeout
	 * defers that step to the next run rather than failing the whole cleanup.
	 */
	private TelemetryCleanup cleanupTelemetry(Instant cutoff, Timestamp timestamp) {
		int droppedPartitions = dropExpiredPartitions(PartitionManager.LOG_RECORD, cutoff)
				+ dropExpiredPartitions(PartitionManager.TXN, cutoff)
				+ dropExpiredPartitions(PartitionManager.SPAN, cutoff);

		int logs = runStep("log_record boundary delete", 0,
				() -> boundaryDelete("DELETE FROM log_record WHERE \"timestamp\" < ?", timestamp));
		Timestamp boundaryWeekEnd = weekAfter(cutoff);
		TxnSpanCleanup txnSpan = runStep("txn/span boundary delete", new TxnSpanCleanup(0, 0),
				() -> Objects.requireNonNull(
						telemetryTransaction.execute(status -> cleanupBoundaryTxnAndSpans(timestamp, boundaryWeekEnd))));
		UptimeCleanup uptime = cleanupUptime(cutoff);

		return new TelemetryCleanup(logs, txnSpan.transactions(), txnSpan.spans(), droppedPartitions, uptime.checks(),
				uptime.incidents());
	}

	/**
	 * Prunes uptime checks and closed incidents older than {@code cutoff}. Also
	 * runs standalone when retention is disabled: uptime history has always been
	 * capped (formerly by an hourly sweep in {@code UptimeScheduler}), so it is
	 * not subject to the opt-in policy.
	 */
	public UptimeCleanup cleanupUptime(Instant cutoff) {
		Timestamp timestamp = Timestamp.from(cutoff);
		int checks = runStep("uptime_check delete", 0,
				() -> boundaryDelete("DELETE FROM uptime_check WHERE checked_at < ?", timestamp));
		int incidents = runStep("uptime_incident delete", 0, () -> boundaryDelete(
				"DELETE FROM uptime_incident WHERE closed_at IS NOT NULL AND closed_at < ?", timestamp));
		return new UptimeCleanup(checks, incidents);
	}

	/**
	 * Prunes notification history rows older than {@code cutoff} (#47). Runs on
	 * every daily sweep regardless of the Data Retention Policy — that policy is
	 * telemetry-only, whereas notification history is capped unconditionally at a
	 * fixed ~30-day window so it never grows unbounded (mirrors how uptime history
	 * is always capped). A plain single-transaction delete: the table is low
	 * volume, and channel deletion already cascades its rows away.
	 */
	public int cleanupNotificationHistory(Instant cutoff) {
		return runStep("notification_history delete", 0,
				() -> boundaryDelete("DELETE FROM notification_history WHERE created_at < ?", Timestamp.from(cutoff)));
	}

	private int dropExpiredPartitions(String table, Instant cutoff) {
		return runStep(table + " partition drop", 0,
				() -> partitions.dropExpiredPartitions(table, cutoff, chunkTimeoutSeconds));
	}

	private int boundaryDelete(String sql, Timestamp cutoff) {
		return Objects.requireNonNull(telemetryTransaction.execute(status -> jdbc.sql(sql).param(cutoff).update()));
	}

	/**
	 * Prunes the boundary partition's expired txns and spans. A span can outlive
	 * its transaction across the cutoff — whether the txn was removed by the
	 * boundary delete below or vanished with a dropped partition — so retained
	 * spans in the boundary week whose txn no longer exists are deleted via the
	 * txn index. Scoping to the boundary week keeps this a single-partition
	 * indexed anti-join rather than the whole-table scan the old {@code NOT
	 * EXISTS} predicate forced: a span starts within its transaction's window, so
	 * an orphan is at most one week boundary away from its now-gone txn.
	 */
	private TxnSpanCleanup cleanupBoundaryTxnAndSpans(Timestamp cutoff, Timestamp boundaryWeekEnd) {
		int transactions = jdbc.sql("DELETE FROM txn WHERE start_ts < ?").param(cutoff).update();
		int expiredSpans = jdbc.sql("DELETE FROM span WHERE start_ts < ?").param(cutoff).update();
		int orphanSpans = jdbc.sql("""
				DELETE FROM span s
				WHERE s.start_ts >= ? AND s.start_ts < ?
				  AND NOT EXISTS (SELECT 1 FROM txn t WHERE t.id = s.txn_id)
				""").param(cutoff).param(boundaryWeekEnd).update();
		return new TxnSpanCleanup(transactions, expiredSpans + orphanSpans);
	}

	/** First instant of the week after the one holding {@code cutoff}, on the same Monday-UTC boundaries as PartitionManager. */
	private static Timestamp weekAfter(Instant cutoff) {
		LocalDate weekStart = cutoff.atZone(ZoneOffset.UTC).toLocalDate().with(DayOfWeek.MONDAY);
		return Timestamp.from(weekStart.plusWeeks(1).atStartOfDay(ZoneOffset.UTC).toInstant());
	}

	private <T> T runStep(String description, T timeoutFallback, Supplier<T> step) {
		try {
			return step.get();
		}
		catch (RuntimeException e) {
			if (!isTimeout(e)) {
				throw e;
			}
			log.warn("deferring retention step '{}' after it timed out", description);
			return timeoutFallback;
		}
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
