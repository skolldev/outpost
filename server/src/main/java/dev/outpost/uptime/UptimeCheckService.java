package dev.outpost.uptime;

import dev.outpost.notifications.NotificationOccurrence;
import dev.outpost.notifications.NotificationPublisher;
import dev.outpost.uptime.UptimeProber.ProbeResult;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records a completed probe and drives incident transitions: the 3rd consecutive
 * failure opens an incident (idempotent via the partial unique index on open
 * incidents), the first success closes it. The counter arithmetic never races
 * with itself because {@link UptimeScheduler}'s in-flight set already serializes
 * per monitor.
 *
 * <p>Transitions cross the notifications publisher seam (#45), and publishing
 * happens only after the transaction commits so a rolled-back transition never
 * notifies. The seam is fire-and-forget (ADR 0005), so a notification hiccup can
 * never fail a recorded check.
 */
@Service
public class UptimeCheckService {

	static final int FAILURES_TO_OPEN_INCIDENT = 3;

	private final JdbcClient jdbc;
	private final TransactionTemplate transaction;
	private final NotificationPublisher notifications;

	public UptimeCheckService(JdbcClient jdbc, PlatformTransactionManager transactionManager,
			NotificationPublisher notifications) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.notifications = notifications;
	}

	public void recordResult(long monitorId, int intervalSeconds, ProbeResult result) {
		// Collected inside the transaction, published only after it commits.
		AtomicReference<NotificationOccurrence> occurrence = new AtomicReference<>();
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
				occurrence.set(closeIncident(monitorId));
			}
			else {
				int failures = jdbc.sql("""
						UPDATE uptime_monitor SET consecutive_failures = consecutive_failures + 1,
							next_check_at = now() + make_interval(secs => ?)
						WHERE id = ? RETURNING consecutive_failures
						""").param(intervalSeconds).param(monitorId).query(Integer.class).single();
				if (failures >= FAILURES_TO_OPEN_INCIDENT) {
					occurrence.set(openIncident(monitorId, result.error()));
				}
			}
		});
		NotificationOccurrence pending = occurrence.get();
		if (pending != null) {
			// The seam never throws, but guard anyway so a notification hiccup can't
			// fail an already-recorded check.
			try {
				notifications.publish(pending);
			}
			catch (RuntimeException e) {
				// Swallowed by design: the check is durably recorded; the notification is not.
			}
		}
	}

	/**
	 * Opens (or, on the 4th+ failure, refreshes) the monitor's incident. Returns an
	 * occurrence only when the row was actually inserted, so notifications fire once
	 * per incident rather than per failed check: {@code xmax = 0} is true exactly
	 * for the tuple this statement inserted, false for the {@code DO UPDATE} path.
	 */
	private NotificationOccurrence openIncident(long monitorId, String failureReason) {
		Incident incident = jdbc.sql("""
				INSERT INTO uptime_incident (monitor_id, last_error) VALUES (?, ?)
				ON CONFLICT (monitor_id) WHERE closed_at IS NULL
				DO UPDATE SET last_error = EXCLUDED.last_error
				RETURNING opened_at, closed_at, (xmax = 0) AS inserted
				""")
			.param(monitorId)
			.param(failureReason)
			.query((rs, i) -> new Incident(rs.getTimestamp("opened_at").toInstant(), null, rs.getBoolean("inserted")))
			.single();
		if (!incident.inserted()) {
			return null;
		}
		Monitor monitor = loadMonitor(monitorId);
		if (monitor == null) {
			return null;
		}
		return new NotificationOccurrence.IncidentStarted(monitor.projectId(), monitorId, monitor.url(),
				monitor.environment(), failureReason, incident.openedAt());
	}

	/**
	 * Closes the monitor's open incident, if any. {@code RETURNING} yields a row only
	 * when this UPDATE actually transitioned an open incident, so an occurrence is
	 * produced once per recovery — never on a success while already healthy.
	 */
	private NotificationOccurrence closeIncident(long monitorId) {
		Incident incident = jdbc.sql("""
				UPDATE uptime_incident SET closed_at = now()
				WHERE monitor_id = ? AND closed_at IS NULL
				RETURNING opened_at, closed_at
				""")
			.param(monitorId)
			.query((rs, i) -> new Incident(rs.getTimestamp("opened_at").toInstant(),
					rs.getTimestamp("closed_at").toInstant(), false))
			.optional()
			.orElse(null);
		if (incident == null) {
			return null;
		}
		Monitor monitor = loadMonitor(monitorId);
		if (monitor == null) {
			return null;
		}
		Duration downtime = Duration.between(incident.openedAt(), incident.closedAt());
		return new NotificationOccurrence.IncidentResolved(monitor.projectId(), monitorId, monitor.url(),
				monitor.environment(), incident.openedAt(), incident.closedAt(), downtime);
	}

	/** Null if the monitor is gone. */
	private Monitor loadMonitor(long monitorId) {
		return jdbc.sql("SELECT project_id, url, environment FROM uptime_monitor WHERE id = ?")
			.param(monitorId)
			.query((rs, i) -> new Monitor(rs.getLong("project_id"), rs.getString("url"), rs.getString("environment")))
			.optional()
			.orElse(null);
	}

	private record Incident(Instant openedAt, Instant closedAt, boolean inserted) {
	}

	private record Monitor(long projectId, String url, String environment) {
	}
}
