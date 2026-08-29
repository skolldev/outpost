package dev.outpost.uptime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The read side of uptime monitoring: the Uptime Monitors, their current state,
 * the open Incidents against them, and the daily rollup of Uptime Checks the
 * status page is drawn from.
 *
 * <p>It exists as a service rather than staying inside {@link UptimeController}
 * because it acquired a second consumer: the MCP Surface's {@code uptime_status}
 * Tool answers the same question for an agent, and the alternative was either a
 * Tool injecting a {@code @RestController} or a second copy of these statements.
 * The repo's convention of declaring DTOs inside the controller that uses them
 * assumes one consumer; where there are two, the records move here with the
 * statements that produce them. The controller keeps its mutations and the
 * validation around them, and the JSON on the wire is unchanged — these are the
 * same records under the same component names.
 *
 * <p>The 90-day window is a property of the question rather than of the caller:
 * a status page shows a fixed span, independent of the global range filter. A
 * caller wanting less narrows what it renders; it does not narrow what is read,
 * because the daily rollup is one grouped pass over {@code uptime_check} either
 * way.
 */
@Service
public class UptimeStatusService {

	/** The fixed span the daily rollup covers, in whole UTC days including today. */
	public static final int WINDOW_DAYS = 90;

	public record Monitor(long id, long projectId, String projectSlug, String environment, String url,
			int intervalSeconds, int timeoutSeconds, int consecutiveFailures, Instant createdAt) {
	}

	public record DayBucket(LocalDate date, long total, long failures, double uptimePct, Integer avgLatencyMs) {
	}

	public record OpenIncident(long id, long monitorId, Instant openedAt, String lastError) {
	}

	public record MonitorOverview(long id, long projectId, String projectSlug, String environment, String url,
			int intervalSeconds, String status, OpenIncident openIncident, List<DayBucket> days) {
	}

	public record Overview(List<MonitorOverview> monitors) {
	}

	private final JdbcClient jdbc;

	public UptimeStatusService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Monitor> monitors() {
		return jdbc.sql("""
				SELECT m.id, m.project_id, p.slug, m.environment, m.url, m.interval_seconds, m.timeout_seconds,
					m.consecutive_failures, m.created_at
				FROM uptime_monitor m JOIN project p ON p.id = m.project_id
				ORDER BY p.slug, m.url
				""").query(this::mapMonitor).list();
	}

	public Optional<Monitor> monitor(long id) {
		return jdbc.sql("""
				SELECT m.id, m.project_id, p.slug, m.environment, m.url, m.interval_seconds, m.timeout_seconds,
					m.consecutive_failures, m.created_at
				FROM uptime_monitor m JOIN project p ON p.id = m.project_id WHERE m.id = ?
				""").param(id).query(this::mapMonitor).optional();
	}

	/**
	 * Every Uptime Monitor with its current state, its open Incident if it has one,
	 * and its daily Uptime Check rollup over the {@link #WINDOW_DAYS} window.
	 *
	 * <p>A Monitor with no Uptime Check recorded yet reads {@code unknown} rather
	 * than {@code up}: nothing has been observed, which is not the same as having
	 * observed success.
	 */
	public Overview overview() {
		List<Monitor> monitors = monitors();

		Map<Long, OpenIncident> incidents = new HashMap<>();
		jdbc.sql("SELECT id, monitor_id, opened_at, last_error FROM uptime_incident WHERE closed_at IS NULL")
			.query((rs, i) -> new OpenIncident(rs.getLong("id"), rs.getLong("monitor_id"),
					rs.getTimestamp("opened_at").toInstant(), rs.getString("last_error")))
			.list()
			.forEach(incident -> incidents.put(incident.monitorId(), incident));

		Map<Long, List<DayBucket>> days = new HashMap<>();
		jdbc.sql("""
				SELECT monitor_id, (checked_at AT TIME ZONE 'UTC')::date AS day,
					count(*) AS total,
					count(*) FILTER (WHERE NOT success) AS failures,
					round(avg(latency_ms))::int AS avg_latency_ms
				FROM uptime_check
				WHERE checked_at >= (now() AT TIME ZONE 'UTC')::date - make_interval(days => ?)
				GROUP BY monitor_id, day
				ORDER BY day
				""").param(WINDOW_DAYS - 1).query((rs, i) -> {
			long total = rs.getLong("total");
			long failures = rs.getLong("failures");
			double pct = total == 0 ? 0 : Math.round((total - failures) * 10_000.0 / total) / 100.0;
			return Map.entry(rs.getLong("monitor_id"), new DayBucket(rs.getObject("day", LocalDate.class), total,
					failures, pct, (Integer) rs.getObject("avg_latency_ms")));
		}).list().forEach(entry -> days.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue()));

		Map<Long, Boolean> lastCheck = new HashMap<>();
		jdbc.sql("""
				SELECT DISTINCT ON (monitor_id) monitor_id, success FROM uptime_check
				ORDER BY monitor_id, checked_at DESC
				""").query((rs, i) -> Map.entry(rs.getLong("monitor_id"), rs.getBoolean("success")))
			.list()
			.forEach(entry -> lastCheck.put(entry.getKey(), entry.getValue()));

		List<MonitorOverview> overviews = monitors.stream().map(m -> {
			OpenIncident incident = incidents.get(m.id());
			Boolean lastSuccess = lastCheck.get(m.id());
			String status = incident != null ? "down" : lastSuccess == null ? "unknown" : lastSuccess ? "up" : "down";
			return new MonitorOverview(m.id(), m.projectId(), m.projectSlug(), m.environment(), m.url(),
					m.intervalSeconds(), status, incident, days.getOrDefault(m.id(), List.of()));
		}).toList();
		return new Overview(overviews);
	}

	private Monitor mapMonitor(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new Monitor(rs.getLong("id"), rs.getLong("project_id"), rs.getString("slug"),
				rs.getString("environment"), rs.getString("url"), rs.getInt("interval_seconds"),
				rs.getInt("timeout_seconds"), rs.getInt("consecutive_failures"),
				rs.getTimestamp("created_at").toInstant());
	}

}
