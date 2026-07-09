package dev.outpost.pipeline;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.outpost.db.PartitionManager;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists processed transactions and their spans (§6.4): environment/release
 * auto-upsert, then JDBC batch inserts into txn + span — append-only, no
 * grouping. On failure the batch degrades to per-transaction storage so one
 * poison transaction cannot sink the rest.
 */
@Component
public class TransactionStore {

	private static final Logger log = LoggerFactory.getLogger(TransactionStore.class);

	private static final String TXN_INSERT = """
			INSERT INTO txn (id, project_id, environment, release, trace_id, span_id, parent_span_id, name, op,
			                 start_ts, end_ts, duration_ms, status, data)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
			ON CONFLICT DO NOTHING
			""";

	private static final String SPAN_INSERT = """
			INSERT INTO span (id, txn_id, project_id, trace_id, span_id, parent_span_id, op, description,
			                  start_ts, end_ts, duration_ms, status, data)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
			ON CONFLICT DO NOTHING
			""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transaction;
	private final PartitionManager partitions;
	private final ObjectMapper mapper;

	public TransactionStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
			PartitionManager partitions, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.partitions = partitions;
		this.mapper = mapper;
	}

	/**
	 * Stores a batch in one transaction. On failure, falls back to storing
	 * transactions one by one so a poison transaction cannot sink its whole batch.
	 */
	public void store(List<ProcessedTransaction> batch) {
		if (batch.isEmpty()) {
			return;
		}
		// Partition DDL runs in its own transaction, before the insert transaction.
		// txn and span partition on start_ts; spans of a transaction can straddle a
		// week boundary from the root, so ensure partitions for every span too.
		batch.forEach(txn -> {
			partitions.ensurePartition(PartitionManager.TXN, txn.startTs());
			partitions.ensurePartition(PartitionManager.SPAN, txn.startTs());
			txn.spans().forEach(span -> partitions.ensurePartition(PartitionManager.SPAN, span.startTs()));
		});
		try {
			transaction.executeWithoutResult(status -> storeAll(batch));
		}
		catch (RuntimeException e) {
			if (batch.size() == 1) {
				log.warn("dropping unstorable transaction {}: {}", batch.get(0).id(), e.toString());
				return;
			}
			log.warn("batch insert of {} transactions failed ({}), retrying individually", batch.size(), e.toString());
			for (ProcessedTransaction txn : batch) {
				store(List.of(txn));
			}
		}
	}

	private void storeAll(List<ProcessedTransaction> batch) {
		for (ProcessedTransaction txn : batch) {
			jdbc.update("""
					INSERT INTO environment (project_id, name) VALUES (?, ?)
					ON CONFLICT (project_id, name) DO NOTHING
					""", txn.projectId(), txn.environment());
			if (txn.release() != null) {
				jdbc.update("""
						INSERT INTO release (project_id, version) VALUES (?, ?)
						ON CONFLICT (project_id, version) DO NOTHING
						""", txn.projectId(), txn.release());
			}
		}
		List<Object[]> txnRows = batch.stream()
			.map(txn -> new Object[] { txn.id(), txn.projectId(), txn.environment(), txn.release(), txn.traceId(),
					txn.spanId(), txn.parentSpanId(), txn.name(), txn.op(), Timestamp.from(txn.startTs()),
					Timestamp.from(txn.endTs()), txn.durationMs(), txn.status(), json(txn.data()) })
			.toList();
		jdbc.batchUpdate(TXN_INSERT, txnRows);

		List<Object[]> spanRows = new ArrayList<>();
		for (ProcessedTransaction txn : batch) {
			for (ProcessedSpan span : txn.spans()) {
				spanRows.add(new Object[] { span.id(), span.txnId(), span.projectId(), span.traceId(), span.spanId(),
						span.parentSpanId(), span.op(), span.description(), Timestamp.from(span.startTs()),
						Timestamp.from(span.endTs()), span.durationMs(), span.status(), json(span.data()) });
			}
		}
		if (!spanRows.isEmpty()) {
			jdbc.batchUpdate(SPAN_INSERT, spanRows);
		}
	}

	private String json(JsonNode data) {
		try {
			return mapper.writeValueAsString(data);
		}
		catch (JacksonException e) {
			throw new IllegalStateException("transaction data not serializable", e);
		}
	}
}
