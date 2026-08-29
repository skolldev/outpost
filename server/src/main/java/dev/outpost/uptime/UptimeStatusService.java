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
 * statements that produce them — {@code server/CLAUDE.md} records the amended
 * rule. The JSON on the wire is unchanged: these are the same records under the
 * same component names.
 *
 * <p><b>This type is public and reached from another package, which ADR-0016
 * declined to do for {@code dev.outpost.query}'s own {@code build…Query}
 * factories.</b> The difference is that those factories are the seam the
 * performance guards {@code EXPLAIN} through and widening them would have been
 * for a test's convenience; this is a second production consumer, which is the
 * case that ADR names as the reason to reconsider. The statements below stay in
 * one place either way.
 *
 * <p><b>Both callers pass their own {@link JdbcClient}, and that is the point.</b>
 * The MCP Surface's query path carries a statement timeout that must not reach
 * the UI's queries, so the caller decides which template runs the statement while
 * the statement itself stays shared. The alternative — the Tool holding its own
 * copy of this SQL so it could bind its own timeout — is exactly what this class
 * exists to prevent.
 */
@Service
public class UptimeStatusService {

	/**
	 * The widest span the daily rollup covers, in whole UTC days including today,
	 * and what the status page always asks for: a status page shows a fixed span,
	 * independent of the global range filter.
	 */
	public static final int WINDOW_DAYS = 90;

	/**
	 * A built statement and its ordered bind params. The same shape as
	 * {@code dev.outpost.query.SearchQuery} and deliberately a separate type: that
	 * one is package-private, and ADR-0016 records that the boundary was held even
	 * for the test harness.
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
	 * Every Uptime Monitor in scope with its current state, its open Incident if it
	 * has one, and its daily Uptime Check rollup over the last {@code days} days.
	 *
	 * <p>Both narrowings are <b>predicates, not post-filters</b>. Reading every
	 * Monitor's ninety days and discarding most of it in Java would make the two
	 * parameters trim the payload while the work stayed the same — and
	 * {@code uptime_check} is a plain table holding one row per probe per interval,
	 * so that work grows with retention. The MCP Surface is where an unattended
	 * caller reaches this.
	 *
	 * <p>A Monitor with no Uptime Check recorded yet reads {@code unknown} rather
	 * than {@code up}: nothing has been observed, which is not the same as having
	 * observed success. The Incident is read regardless of {@code days}, because an
	 * Incident that opened before the window is still open now.
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

	// ------------------------------------------------------------------ queries

	/**
	 * The Monitors in scope, optionally one by id. Extracted as a factory for the
	 * reason the query controllers extract theirs: a guard has to {@code EXPLAIN}
	 * the statement this runs rather than a copy of it.
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
	 * The Incidents still open, which is what makes a Monitor read {@code down}.
	 * Answered from the partial unique index on open Incidents, so it costs one
	 * index scan whatever the history holds.
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
	 * Uptime Checks rolled up per Monitor per UTC day over the last {@code days}
	 * days.
	 *
	 * <p><b>This is the one statement here whose cost is O(matching rows) rather
	 * than O(monitors), and no index changes that</b> — an aggregate cannot stop
	 * early. What bounds it is the window and the Project filter, which is why both
	 * are predicates: at a 30-second interval one Monitor writes ~2 880 rows a day,
	 * so the difference between ninety days of every Monitor and seven days of one
	 * is the difference between the two questions being asked.
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
	 * The most recent Uptime Check per Monitor, which is the state a Monitor with no
	 * open Incident reports. {@code DISTINCT ON} walks
	 * {@code idx_uptime_check_monitor_ts} backwards per Monitor and stops, so it
	 * carries no time bound and does not need one.
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
