package dev.outpost.uptime;

import dev.outpost.uptime.UptimeProber.ProbeResult;
import dev.outpost.uptime.UptimeStatusService.Monitor;
import dev.outpost.uptime.UptimeStatusService.Overview;
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
 * Uptime monitor management. Mutations are admin-only; reads are open to any
 * session user and are delegated to {@link UptimeStatusService}, which the MCP
 * Surface's {@code uptime_status} Tool reads through as well. The overview is a
 * fixed 90-day UTC window by design (status-page semantics, independent of the
 * global filters) — see that service for why the window is not a parameter.
 */
@RestController
@RequestMapping("/api/internal/uptime")
public class UptimeController {

	private static final Set<Integer> ALLOWED_INTERVALS = Set.of(30, 60, 300, 900, 3600);

	public record MonitorRequest(Long projectId, String environment, String url, Integer intervalSeconds,
			Integer timeoutSeconds) {
	}

	public record TestRequest(String url, Integer timeoutSeconds) {
	}

	public record TestResult(boolean success, Integer statusCode, int latencyMs, String error) {
	}

	private final JdbcClient jdbc;
	private final UptimeProber prober;
	private final UptimeStatusService status;

	public UptimeController(JdbcClient jdbc, UptimeProber prober, UptimeStatusService status) {
		this.jdbc = jdbc;
		this.prober = prober;
		this.status = status;
	}

	@GetMapping("/monitors")
	public List<Monitor> list() {
		return status.monitors();
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
		return status.overview();
	}

	private Monitor get(long id) {
		return status.monitor(id).orElseThrow();
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
}
