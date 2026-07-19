package dev.outpost.pipeline;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Serializes event ingestion with retention's event/issue aggregate rebuild.
 * The lock is transaction-scoped, so callers must acquire it inside the
 * transaction that changes events or issue aggregates.
 */
@Component
public class EventIssueLock {

	private static final long ADVISORY_LOCK_KEY = 727_572_058L;

	private final JdbcClient jdbc;

	public EventIssueLock(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void acquire() {
		jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(ADVISORY_LOCK_KEY).query(rs -> {
		});
	}
}
