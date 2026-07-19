package dev.outpost.pipeline;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Serializes changes to event membership and issue aggregates, including
 * ingestion, retention rebuilds, and project deletion. The lock is
 * transaction-scoped, so callers must acquire it inside the transaction that
 * changes events or issue aggregates.
 */
@Component
public class EventIssueLock {

	private static final int LOCK_NAMESPACE = 727_572_058;

	private final JdbcClient jdbc;

	public EventIssueLock(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void acquire(long projectId) {
		jdbc.sql("SELECT pg_advisory_xact_lock(?, ?)")
			.param(LOCK_NAMESPACE)
			.param(Long.hashCode(projectId))
			.query(rs -> {
		});
	}

	/** Attempts acquisition without waiting, giving ingestion priority over retention. */
	public boolean tryAcquire(long projectId) {
		return jdbc.sql("SELECT pg_try_advisory_xact_lock(?, ?)")
			.param(LOCK_NAMESPACE)
			.param(Long.hashCode(projectId))
			.query(Boolean.class)
			.single();
	}
}
