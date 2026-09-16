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
 * The read side of uptime monitoring: Monitors, their current state, open
 * Incidents, and the daily rollup of Uptime Checks the status page is drawn
 * from. Public because it has two consumers — {@link UptimeController} and
 * the MCP Surface's {@code uptime_status} Tool — each passing its own
 * {@link JdbcClient} so the MCP path's statement timeout can't leak into UI
 * queries.
 */
@Service
public class UptimeStatusService {

	/**
	 * The rollup's span in whole UTC days including today; the status page
	 * always requests this fixed window, independent of the global range filter.
	 */
	public static final int WINDOW_DAYS = 90;

	/**
	 * A built statement with its ordered bind params — the same shape as
	 * {@code dev.outpost.query.SearchQuery}, kept as a separate type per
	 * ADR-0016.
	 */
	public record UptimeQuery(String sql, List<Object> params) {
	}

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
		return monitors(jdbc, List.of());
	}

	public Optional<Monitor> monitor(long id) {
		UptimeQuery query = buildMonitorQuery(List.of(), id);
		return jdbc.sql(query.sql()).params(query.params()).query(UptimeStatusService::mapMonitor).optional();
	}

	/** The status page's overview: every Monitor, over the full {@link #WINDOW_DAYS} span. */
	public Overview overview() {
		return overview(jdbc, List.of(), WINDOW_DAYS);
	}

	/**
	 * Every Uptime Monitor in scope, its current state, its open Incident if
	 * any, and its daily Uptime Check rollup over the last {@code days} days.
	 * Both narrowings are predicates, not post-filters, since
	 * {@code uptime_check} grows with retention; a Monitor with no Check yet
	 * reads {@code unknown}, not {@code up}, and an Incident opened before the
	 * window still shows.
	 */
	public Overview overview(JdbcClient jdbc, List<Long> projectIds, int days) {
		List<Monitor> monitors = monitors(jdbc, projectIds);

		Map<Long, OpenIncident> incidents = new HashMap<>();
		UptimeQuery open = buildOpenIncidentQuery(projectIds);
		jdbc.sql(open.sql())
			.params(open.params())
			.query((rs, i) -> new OpenIncident(rs.getLong("id"), rs.getLong("monitor_id"),
					rs.getTimestamp("opened_at").toInstant(), rs.getString("last_error")))
			.list()
			.forEach(incident -> incidents.put(incident.monitorId(), incident));

		Map<Long, List<DayBucket>> days_ = new HashMap<>();
		UptimeQuery rollup = buildDailyRollupQuery(projectIds, days);
		jdbc.sql(rollup.sql()).params(rollup.params()).query((rs, i) -> {
			long total = rs.getLong("total");
			long failures = rs.getLong("failures");
			double pct = total == 0 ? 0 : Math.round((total - failures) * 10_000.0 / total) / 100.0;
			return Map.entry(rs.getLong("monitor_id"), new DayBucket(rs.getObject("day", LocalDate.class), total,
					failures, pct, (Integer) rs.getObject("avg_latency_ms")));
		}).list().forEach(entry -> days_.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue()));

		Map<Long, Boolean> lastCheck = new HashMap<>();
		UptimeQuery last = buildLastCheckQuery(projectIds);
		jdbc.sql(last.sql())
			.params(last.params())
			.query((rs, i) -> Map.entry(rs.getLong("monitor_id"), rs.getBoolean("success")))
			.list()
			.forEach(entry -> lastCheck.put(entry.getKey(), entry.getValue()));

		List<MonitorOverview> overviews = monitors.stream().map(m -> {
			OpenIncident incident = incidents.get(m.id());
			Boolean lastSuccess = lastCheck.get(m.id());
			String status = incident != null ? "down" : lastSuccess == null ? "unknown" : lastSuccess ? "up" : "down";
			return new MonitorOverview(m.id(), m.projectId(), m.projectSlug(), m.environment(), m.url(),
					m.intervalSeconds(), status, incident, days_.getOrDefault(m.id(), List.of()));
		}).toList();
		return new Overview(overviews);
	}

	private static List<Monitor> monitors(JdbcClient jdbc, List<Long> projectIds) {
		UptimeQuery query = buildMonitorQuery(projectIds, null);
		return jdbc.sql(query.sql()).params(query.params()).query(UptimeStatusService::mapMonitor).list();
	}

	/**
	 * The Monitors in scope, optionally narrowed to one by id. Extracted as a
	 * factory so a guard can {@code EXPLAIN} this exact statement.
	 */
	public static UptimeQuery buildMonitorQuery(List<Long> project, Long id) {
		StringBuilder sql = new StringBuilder("""
				SELECT m.id, m.project_id, p.slug, m.environment, m.url, m.interval_seconds, m.timeout_seconds,
					m.consecutive_failures, m.created_at
				FROM uptime_monitor m JOIN project p ON p.id = m.project_id WHERE 1=1
				""");
		List<Object> params = new ArrayList<>();
		appendProjects(sql, "m.project_id", project, params);
		if (id != null) {
			sql.append(" AND m.id = ?");
			params.add(id);
		}
		sql.append(" ORDER BY p.slug, m.url");
		return new UptimeQuery(sql.toString(), params);
	}

	/**
	 * The still-open Incidents, which is what makes a Monitor read {@code down}.
	 * Answered via the partial unique index on open Incidents, so it costs one
	 * index scan regardless of history size.
	 */
	public static UptimeQuery buildOpenIncidentQuery(List<Long> project) {
		StringBuilder sql = new StringBuilder("""
				SELECT i.id, i.monitor_id, i.opened_at, i.last_error
				FROM uptime_incident i JOIN uptime_monitor m ON m.id = i.monitor_id
				WHERE i.closed_at IS NULL
				""");
		List<Object> params = new ArrayList<>();
		appendProjects(sql, "m.project_id", project, params);
		return new UptimeQuery(sql.toString(), params);
	}

	/**
	 * Uptime Checks rolled up per Monitor per UTC day over the last
	 * {@code days} days. Cost is O(matching rows), not O(monitors) — an
	 * aggregate can't stop early — which is why the window and Project filter
	 * are predicates, not post-filters.
	 */
	public static UptimeQuery buildDailyRollupQuery(List<Long> project, int days) {
		StringBuilder sql = new StringBuilder("""
				SELECT c.monitor_id, (c.checked_at AT TIME ZONE 'UTC')::date AS day,
					count(*) AS total,
					count(*) FILTER (WHERE NOT c.success) AS failures,
					round(avg(c.latency_ms))::int AS avg_latency_ms
				FROM uptime_check c JOIN uptime_monitor m ON m.id = c.monitor_id
				WHERE c.checked_at >= (now() AT TIME ZONE 'UTC')::date - make_interval(days => ?)
				""");
		List<Object> params = new ArrayList<>();
		params.add(days - 1);
		appendProjects(sql, "m.project_id", project, params);
		sql.append(" GROUP BY c.monitor_id, day ORDER BY day");
		return new UptimeQuery(sql.toString(), params);
	}

	/**
	 * The most recent Uptime Check per Monitor — the state reported when
	 * there's no open Incident. {@code DISTINCT ON} walks
	 * {@code idx_uptime_check_monitor_ts} backwards per Monitor and stops, so
	 * it needs no time bound.
	 */
	public static UptimeQuery buildLastCheckQuery(List<Long> project) {
		StringBuilder sql = new StringBuilder("""
				SELECT DISTINCT ON (c.monitor_id) c.monitor_id, c.success
				FROM uptime_check c JOIN uptime_monitor m ON m.id = c.monitor_id WHERE 1=1
				""");
		List<Object> params = new ArrayList<>();
		appendProjects(sql, "m.project_id", project, params);
		sql.append(" ORDER BY c.monitor_id, c.checked_at DESC");
		return new UptimeQuery(sql.toString(), params);
	}

	/** Adds {@code AND <column> IN (…)} only when the filter is non-empty. */
	private static void appendProjects(StringBuilder sql, String column, List<Long> project, List<Object> params) {
		if (project == null || project.isEmpty()) {
			return;
		}
		sql.append(" AND ").append(column).append(" IN (");
		for (int i = 0; i < project.size(); i++) {
			sql.append(i == 0 ? "?" : ",?");
		}
		sql.append(")");
		params.addAll(project);
	}

	private static Monitor mapMonitor(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new Monitor(rs.getLong("id"), rs.getLong("project_id"), rs.getString("slug"),
				rs.getString("environment"), rs.getString("url"), rs.getInt("interval_seconds"),
				rs.getInt("timeout_seconds"), rs.getInt("consecutive_failures"),
				rs.getTimestamp("created_at").toInstant());
	}

}
