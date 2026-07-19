package dev.outpost.uptime;

import dev.outpost.uptime.UptimeProber.ProbeResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records a completed probe and drives incident transitions: the 3rd
 * consecutive failure opens an incident (idempotent via the partial unique
 * index on open incidents), the first success closes it. Per-monitor
 * serialization is guaranteed by {@link UptimeScheduler}'s in-flight set, so
 * the counter arithmetic here never races with itself.
 */
@Service
public class UptimeCheckService {

	static final int FAILURES_TO_OPEN_INCIDENT = 3;

	private final JdbcClient jdbc;
	private final TransactionTemplate transaction;

	public UptimeCheckService(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	public void recordResult(long monitorId, int intervalSeconds, ProbeResult result) {
		transaction.executeWithoutResult(status -> {
			jdbc.sql("""
					INSERT INTO uptime_check (monitor_id, success, status_code, latency_ms, error)
					VALUES (?, ?, ?, ?, ?)
					""")
				.param(monitorId)
				.param(result.success())
				.param(result.statusCode())
				.param(result.latencyMs())
				.param(result.error())
				.update();
			if (result.success()) {
				jdbc.sql("""
						UPDATE uptime_monitor SET consecutive_failures = 0,
							next_check_at = now() + make_interval(secs => ?)
						WHERE id = ?
						""").param(intervalSeconds).param(monitorId).update();
				jdbc.sql("UPDATE uptime_incident SET closed_at = now() WHERE monitor_id = ? AND closed_at IS NULL")
					.param(monitorId)
					.update();
			}
			else {
				int failures = jdbc.sql("""
						UPDATE uptime_monitor SET consecutive_failures = consecutive_failures + 1,
							next_check_at = now() + make_interval(secs => ?)
						WHERE id = ? RETURNING consecutive_failures
						""").param(intervalSeconds).param(monitorId).query(Integer.class).single();
				if (failures >= FAILURES_TO_OPEN_INCIDENT) {
					jdbc.sql("""
							INSERT INTO uptime_incident (monitor_id, last_error) VALUES (?, ?)
							ON CONFLICT (monitor_id) WHERE closed_at IS NULL
							DO UPDATE SET last_error = EXCLUDED.last_error
							""").param(monitorId).param(result.error()).update();
				}
			}
		});
	}
}
