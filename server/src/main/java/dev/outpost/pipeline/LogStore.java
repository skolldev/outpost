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
 * Persists processed log records (§6.3): environment/release auto-upsert, then
 * a JDBC batch insert — append-only, no grouping. Stored records are published
 * to the live tail.
 */
@Component
public class LogStore {

	private static final Logger log = LoggerFactory.getLogger(LogStore.class);

	private static final String LOG_INSERT = """
			INSERT INTO log_record (id, project_id, environment, "timestamp", trace_id, span_id, level,
			                        severity_number, body, attributes, release)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
			ON CONFLICT DO NOTHING
			""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transaction;
	private final PartitionManager partitions;
	private final LogTail tail;
	private final ObjectMapper mapper;

	public LogStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, PartitionManager partitions,
			LogTail tail, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.partitions = partitions;
		this.tail = tail;
		this.mapper = mapper;
	}

	/**
	 * Stores a batch in one transaction. On failure, falls back to storing
	 * records one by one so a poison record cannot sink its whole batch.
	 */
	public void store(List<ProcessedLog> batch) {
		if (batch.isEmpty()) {
			return;
		}
		// Partition DDL runs in its own transaction, before the insert transaction.
		batch.stream()
			.map(ProcessedLog::timestamp)
			.distinct()
			.forEach(timestamp -> partitions.ensurePartition(PartitionManager.LOG_RECORD, timestamp));
		try {
			transaction.executeWithoutResult(status -> storeAll(batch));
			tail.publish(batch);
		}
		catch (RuntimeException e) {
			if (batch.size() == 1) {
				log.warn("dropping unstorable log record {}: {}", batch.get(0).id(), e.toString());
				return;
			}
			log.warn("batch insert of {} log records failed ({}), retrying individually", batch.size(), e.toString());
			for (ProcessedLog record : batch) {
				store(List.of(record));
			}
		}
	}

	private void storeAll(List<ProcessedLog> batch) {
		for (ProcessedLog record : batch) {
			jdbc.update("""
					INSERT INTO environment (project_id, name) VALUES (?, ?)
					ON CONFLICT (project_id, name) DO NOTHING
					""", record.projectId(), record.environment());
			if (record.release() != null) {
				jdbc.update("""
						INSERT INTO release (project_id, version) VALUES (?, ?)
						ON CONFLICT (project_id, version) DO NOTHING
						""", record.projectId(), record.release());
			}
		}
		List<Object[]> rows = batch.stream()
			.map(record -> new Object[] { record.id(), record.projectId(), record.environment(),
					Timestamp.from(record.timestamp()), record.traceId(), record.spanId(), record.level(),
					record.severityNumber(), record.body(), json(record), record.release() })
			.toList();
		jdbc.batchUpdate(LOG_INSERT, rows);
	}

	private String json(ProcessedLog record) {
		try {
			return mapper.writeValueAsString(record.attributes());
		}
		catch (JacksonException e) {
			throw new IllegalStateException("log attributes not serializable", e);
		}
	}
}
