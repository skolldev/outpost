package dev.outpost.pipeline;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import dev.outpost.db.PartitionManager;
import dev.outpost.notifications.NotificationOccurrence;
import dev.outpost.notifications.NotificationPublisher;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists processed error events (§6.2): environment/release auto-upsert,
 * issue upsert with regression handling, per-environment stats, then a JDBC
 * batch insert of the event rows.
 *
 * <p>The publisher seam (parent #41): when an issue upsert inserts — a
 * fingerprint's first Event — a {@code new_issue} occurrence is published, but
 * only after the storing transaction commits, so a notification is never sent
 * for an Issue that rolled back. Repeats and regressions do not publish.
 */
@Component
public class EventStore {

	private static final Logger log = LoggerFactory.getLogger(EventStore.class);

	/** Result of the issue upsert: the row id and whether this statement inserted it. */
	private record IssueUpsert(long id, boolean inserted) {
	}

	// xmax = 0 marks a row this statement just inserted; a row updated via ON
	// CONFLICT has a non-zero xmax. This distinguishes a fingerprint's first
	// Event (a new Issue → notify) from a repeat or a regression (never notify).
	private static final String ISSUE_UPSERT = """
			INSERT INTO issue (project_id, fingerprint, title, culprit, level, status, first_seen, last_seen, event_count)
			VALUES (?, ?, ?, ?, ?, 'unresolved', ?, ?, 1)
			ON CONFLICT (project_id, fingerprint) DO UPDATE SET
			    first_seen = LEAST(issue.first_seen, EXCLUDED.first_seen),
			    last_seen = GREATEST(issue.last_seen, EXCLUDED.last_seen),
			    title = EXCLUDED.title,
			    culprit = EXCLUDED.culprit,
			    level = EXCLUDED.level,
			    event_count = issue.event_count + 1,
			    status = CASE WHEN issue.status = 'resolved' THEN 'unresolved' ELSE issue.status END
			RETURNING id, (xmax = 0) AS inserted
			""";

	private static final String EVENT_INSERT = """
			INSERT INTO event (id, project_id, issue_id, environment, release, "timestamp", trace_id, level,
			                   message, exception_type, user_ident, data, raw, symbolication_status)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
			ON CONFLICT DO NOTHING
			""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transaction;
	private final PartitionManager partitions;
	private final ObjectMapper mapper;
	private final EventIssueLock eventIssueLock;
	private final NotificationPublisher notifications;

	public EventStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, PartitionManager partitions,
			ObjectMapper mapper, EventIssueLock eventIssueLock, NotificationPublisher notifications) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.partitions = partitions;
		this.mapper = mapper;
		this.eventIssueLock = eventIssueLock;
		this.notifications = notifications;
	}

	/**
	 * Stores each project's events in a separate transaction so unrelated projects
	 * can ingest concurrently. On failure, falls back to storing events one by one
	 * so a poison event cannot sink its project batch.
	 */
	public void store(List<ProcessedEvent> batch) {
		if (batch.isEmpty()) {
			return;
		}
		// Partition DDL runs in its own transaction, before the insert transaction.
		batch.stream()
			.map(ProcessedEvent::timestamp)
			.distinct()
			.forEach(timestamp -> partitions.ensurePartition(PartitionManager.EVENT, timestamp));
		Map<Long, List<ProcessedEvent>> byProject = batch.stream()
			.collect(Collectors.groupingBy(ProcessedEvent::projectId, LinkedHashMap::new, Collectors.toList()));
		byProject.values().forEach(this::storeProject);
	}

	private void storeProject(List<ProcessedEvent> batch) {
		List<NotificationOccurrence> newIssues = new ArrayList<>();
		try {
			transaction.executeWithoutResult(status -> storeAll(batch, newIssues));
		}
		catch (RuntimeException e) {
			if (batch.size() == 1) {
				log.warn("dropping unstorable event {}: {}", batch.get(0).id(), e.toString());
				return;
			}
			log.warn("batch insert of {} events failed ({}), retrying individually", batch.size(), e.toString());
			for (ProcessedEvent event : batch) {
				storeProject(List.of(event));
			}
			return;
		}
		// Publish only after the transaction commits, so a rolled-back Issue never
		// notifies. The seam is fire-and-forget (ADR 0005): publish never throws,
		// but guard anyway so a notification hiccup can't fail a stored batch.
		for (NotificationOccurrence occurrence : newIssues) {
			try {
				notifications.publish(occurrence);
			}
			catch (RuntimeException e) {
				log.warn("failed to publish new-issue notification: {}", e.toString());
			}
		}
	}

	private void storeAll(List<ProcessedEvent> batch, List<NotificationOccurrence> newIssues) {
		eventIssueLock.acquire(batch.getFirst().projectId());
		for (ProcessedEvent event : batch) {
			jdbc.update("""
					INSERT INTO environment (project_id, name) VALUES (?, ?)
					ON CONFLICT (project_id, name) DO NOTHING
					""", event.projectId(), event.environment());
			if (event.release() != null) {
				jdbc.update("""
						INSERT INTO release (project_id, version) VALUES (?, ?)
						ON CONFLICT (project_id, version) DO NOTHING
						""", event.projectId(), event.release());
			}
		}
		List<Object[]> eventRows = batch.stream().map(event -> {
			IssueUpsert upsert = jdbc.queryForObject(ISSUE_UPSERT,
					(rs, i) -> new IssueUpsert(rs.getLong("id"), rs.getBoolean("inserted")), event.projectId(),
					event.fingerprint(), event.title(), event.culprit(), event.level(),
					Timestamp.from(event.timestamp()), Timestamp.from(event.timestamp()));
			long issueId = upsert.id();
			if (upsert.inserted()) {
				newIssues.add(new NotificationOccurrence.NewIssue(event.projectId(), issueId, event.title(),
						event.culprit(), event.environment(), event.timestamp()));
			}
			jdbc.update("""
					INSERT INTO issue_env_stats (issue_id, environment, event_count, last_seen)
					VALUES (?, ?, 1, ?)
					ON CONFLICT (issue_id, environment) DO UPDATE SET
					    event_count = issue_env_stats.event_count + 1,
					    last_seen = GREATEST(issue_env_stats.last_seen, EXCLUDED.last_seen)
					""", issueId, event.environment(), Timestamp.from(event.timestamp()));
			return new Object[] { event.id(), event.projectId(), issueId, event.environment(), event.release(),
					Timestamp.from(event.timestamp()), event.traceId(), event.level(), event.message(),
					event.exceptionType(), event.userIdent(), json(event), event.rawGzip(),
					event.symbolicationStatus() };
		}).toList();

		jdbc.batchUpdate(EVENT_INSERT, eventRows);
	}

	private String json(ProcessedEvent event) {
		try {
			return mapper.writeValueAsString(event.data());
		}
		catch (JacksonException e) {
			throw new IllegalStateException("event data not serializable", e);
		}
	}
}
