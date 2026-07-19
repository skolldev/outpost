package dev.outpost.uptime;

import dev.outpost.uptime.UptimeProber.ProbeResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Uptime monitor management + status-page overview. Mutations are admin-only;
 * reads are open to any session user. The overview is a fixed 90-day UTC
 * window by design (status-page semantics, independent of the global filters).
 */
@RestController
@RequestMapping("/api/internal/uptime")
public class UptimeController {

	private static final Set<Integer> ALLOWED_INTERVALS = Set.of(30, 60, 300, 900, 3600);
	private static final int WINDOW_DAYS = 90;

	public record MonitorRequest(Long projectId, String environment, String url, Integer intervalSeconds,
			Integer timeoutSeconds) {
	}

	public record Monitor(long id, long projectId, String projectSlug, String environment, String url,
			int intervalSeconds, int timeoutSeconds, int consecutiveFailures, Instant createdAt) {
	}

	public record TestRequest(String url, Integer timeoutSeconds) {
	}

	public record TestResult(boolean success, Integer statusCode, int latencyMs, String error) {
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
	private final UptimeProber prober;

	public UptimeController(JdbcClient jdbc, UptimeProber prober) {
		this.jdbc = jdbc;
		this.prober = prober;
	}

	@GetMapping("/monitors")
	public List<Monitor> list() {
		return jdbc.sql("""
				SELECT m.id, m.project_id, p.slug, m.environment, m.url, m.interval_seconds, m.timeout_seconds,
					m.consecutive_failures, m.created_at
				FROM uptime_monitor m JOIN project p ON p.id = m.project_id
				ORDER BY p.slug, m.url
				""").query(this::mapMonitor).list();
	}

	@PostMapping("/monitors")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<?> create(@RequestBody MonitorRequest request) {
		String problem = validate(request);
		if (problem != null) {
			return ResponseEntity.badRequest().body(Map.of("detail", problem));
		}
		boolean projectExists = jdbc.sql("SELECT count(*) FROM project WHERE id = ?")
			.param(request.projectId())
			.query(Long.class)
			.single() > 0;
		if (!projectExists) {
			return ResponseEntity.badRequest().body(Map.of("detail", "project does not exist"));
		}
		long id = jdbc.sql("""
				INSERT INTO uptime_monitor (project_id, environment, url, interval_seconds, timeout_seconds)
				VALUES (?, ?, ?, ?, ?) RETURNING id
				""")
			.param(request.projectId())
			.param(request.environment().trim())
			.param(request.url().trim())
			.param(request.intervalSeconds())
			.param(timeoutOrDefault(request))
			.query(Long.class)
			.single();
		return ResponseEntity.status(HttpStatus.CREATED).body(get(id));
	}

	@PatchMapping("/monitors/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<?> update(@PathVariable long id, @RequestBody MonitorRequest request) {
		String problem = validate(request);
		if (problem != null) {
			return ResponseEntity.badRequest().body(Map.of("detail", problem));
		}
		boolean projectExists = jdbc.sql("SELECT count(*) FROM project WHERE id = ?")
			.param(request.projectId())
			.query(Long.class)
			.single() > 0;
		if (!projectExists) {
			return ResponseEntity.badRequest().body(Map.of("detail", "project does not exist"));
		}
		// Any edit restarts an in-progress failure streak and re-checks
		// immediately — the simplest coherent behavior after a config change.
		int updated = jdbc.sql("""
				UPDATE uptime_monitor SET project_id = ?, environment = ?, url = ?, interval_seconds = ?, timeout_seconds = ?,
					consecutive_failures = 0, next_check_at = now()
				WHERE id = ?
				""")
			.param(request.projectId())
			.param(request.environment().trim())
			.param(request.url().trim())
			.param(request.intervalSeconds())
			.param(timeoutOrDefault(request))
			.param(id)
			.update();
		return updated > 0 ? ResponseEntity.ok(get(id)) : ResponseEntity.notFound().build();
	}

	@DeleteMapping("/monitors/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		int deleted = jdbc.sql("DELETE FROM uptime_monitor WHERE id = ?").param(id).update();
		return deleted > 0 ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	/** One-shot synchronous probe; never recorded, never counts toward incidents. */
	@PostMapping("/monitors/test")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<?> test(@RequestBody TestRequest request) {
		String problem = UptimeProber.validateUrl(request.url());
		if (problem != null) {
			return ResponseEntity.badRequest().body(Map.of("detail", problem));
		}
		int timeout = request.timeoutSeconds() == null ? 10 : request.timeoutSeconds();
		if (timeout < 1 || timeout > 30) {
			return ResponseEntity.badRequest().body(Map.of("detail", "timeout_seconds must be between 1 and 30"));
		}
		ProbeResult result = prober.probe(request.url().trim(), timeout);
		return ResponseEntity
			.ok(new TestResult(result.success(), result.statusCode(), result.latencyMs(), result.error()));
	}

	@GetMapping("/overview")
	public Overview overview() {
		List<Monitor> monitors = list();

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
		}).list().forEach(entry -> days.computeIfAbsent(entry.getKey(), k -> new java.util.ArrayList<>()).add(entry.getValue()));

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

	private Monitor get(long id) {
		return jdbc.sql("""
				SELECT m.id, m.project_id, p.slug, m.environment, m.url, m.interval_seconds, m.timeout_seconds,
					m.consecutive_failures, m.created_at
				FROM uptime_monitor m JOIN project p ON p.id = m.project_id WHERE m.id = ?
				""").param(id).query(this::mapMonitor).single();
	}

	private String validate(MonitorRequest request) {
		if (request.projectId() == null) {
			return "project_id is required";
		}
		if (request.environment() == null || request.environment().isBlank()) {
			return "environment is required";
		}
		String urlProblem = UptimeProber.validateUrl(request.url());
		if (urlProblem != null) {
			return urlProblem;
		}
		if (request.intervalSeconds() == null || !ALLOWED_INTERVALS.contains(request.intervalSeconds())) {
			return "interval_seconds must be one of 30, 60, 300, 900, 3600";
		}
		int timeout = timeoutOrDefault(request);
		if (timeout < 1 || timeout > 30) {
			return "timeout_seconds must be between 1 and 30";
		}
		return null;
	}

	private int timeoutOrDefault(MonitorRequest request) {
		return request.timeoutSeconds() == null ? 10 : request.timeoutSeconds();
	}

	private Monitor mapMonitor(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new Monitor(rs.getLong("id"), rs.getLong("project_id"), rs.getString("slug"),
				rs.getString("environment"), rs.getString("url"), rs.getInt("interval_seconds"),
				rs.getInt("timeout_seconds"), rs.getInt("consecutive_failures"),
				rs.getTimestamp("created_at").toInstant());
	}
}
