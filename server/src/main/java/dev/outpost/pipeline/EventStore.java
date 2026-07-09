package dev.outpost.pipeline;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import dev.outpost.db.PartitionManager;
import java.sql.Timestamp;
import java.util.List;
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
 */
@Component
public class EventStore {

	private static final Logger log = LoggerFactory.getLogger(EventStore.class);

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
			RETURNING id
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

	public EventStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, PartitionManager partitions,
			ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.partitions = partitions;
		this.mapper = mapper;
	}

	/**
	 * Stores a batch in one transaction. On failure, falls back to storing
	 * events one by one so a poison event cannot sink its whole batch.
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
		try {
			transaction.executeWithoutResult(status -> storeAll(batch));
		}
		catch (RuntimeException e) {
			if (batch.size() == 1) {
				log.warn("dropping unstorable event {}: {}", batch.get(0).id(), e.toString());
				return;
			}
			log.warn("batch insert of {} events failed ({}), retrying individually", batch.size(), e.toString());
			for (ProcessedEvent event : batch) {
				store(List.of(event));
			}
		}
	}

	private void storeAll(List<ProcessedEvent> batch) {
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
			Long issueId = jdbc.queryForObject(ISSUE_UPSERT, Long.class, event.projectId(), event.fingerprint(),
					event.title(), event.culprit(), event.level(), Timestamp.from(event.timestamp()),
					Timestamp.from(event.timestamp()));
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
