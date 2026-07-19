package dev.outpost.uptime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.outpost.TestcontainersConfiguration;
import dev.outpost.uptime.UptimeProber.ProbeResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestTemplate;

/**
 * Uptime monitoring end to end: monitor CRUD with validation and admin
 * gating, the synchronous test-connection probe against a local stub server,
 * incident transitions (3 consecutive failures open, one success closes), the
 * scheduler picking up due monitors, the 90-day overview aggregation, and
 * retention sweeps.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
		"outpost.uptime.tick-millis=100" })
@Import(TestcontainersConfiguration.class)
class UptimeIntegrationTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UptimeCheckService checkService;

	@Autowired
	UptimeScheduler scheduler;

	final RestTemplate rest = new RestTemplate();

	long projectId;
	String adminCookie;
	HttpServer stub;
	volatile int stubStatus = 200;

	@BeforeEach
	void setUp() throws IOException {
		// The default HttpURLConnection factory can't send PATCH.
		rest.setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM uptime_monitor").update();
		jdbc.sql("DELETE FROM app_user WHERE email <> 'admin@test.local'").update();
		jdbc.sql("DELETE FROM project").update();
		projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES ('shop', 'Shop') RETURNING id")
			.query(Long.class)
			.single();
		adminCookie = login("admin@test.local", "test-password");
		stub = HttpServer.create(new InetSocketAddress(0), 0);
		stub.createContext("/health", exchange -> {
			exchange.sendResponseHeaders(stubStatus, -1);
			exchange.close();
		});
		stub.createContext("/slow", exchange -> {
			try {
				Thread.sleep(5_000);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		stub.start();
	}

	@AfterEach
	void tearDown() {
		stub.stop(0);
	}

	// ------------------------------------------------------------------ CRUD

	@Test
	void createValidatesInput() {
		assertThat(post("/api/internal/uptime/monitors", monitorBody("not-a-url", 60, 10), adminCookie)
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(post("/api/internal/uptime/monitors", monitorBody(stubUrl("/health"), 45, 10), adminCookie)
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(post("/api/internal/uptime/monitors", monitorBody(stubUrl("/health"), 60, 0), adminCookie)
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(post("/api/internal/uptime/monitors",
				Map.of("project_id", 999_999, "environment", "prod", "url", stubUrl("/health"), "interval_seconds", 60),
				adminCookie)
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void crudRoundTrip() {
		ResponseEntity<Map> created = post("/api/internal/uptime/monitors",
				monitorBody(stubUrl("/health"), 3600, 10), adminCookie);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		Map<String, Object> body = created.getBody();
		assertThat(body.get("project_slug")).isEqualTo("shop");
		assertThat(body.get("environment")).isEqualTo("prod");
		assertThat(body.get("interval_seconds")).isEqualTo(3600);
		long id = ((Number) body.get("id")).longValue();

		// Seed a failure streak, then PATCH — streak and schedule reset.
		jdbc.sql("UPDATE uptime_monitor SET consecutive_failures = 2 WHERE id = ?").param(id).update();
		ResponseEntity<Map> updated = exchange(HttpMethod.PATCH, "/api/internal/uptime/monitors/" + id,
				monitorBody(stubUrl("/health"), 900, 5), adminCookie);
		assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updated.getBody().get("interval_seconds")).isEqualTo(900);
		assertThat(updated.getBody().get("timeout_seconds")).isEqualTo(5);
		assertThat(updated.getBody().get("consecutive_failures")).isEqualTo(0);

		// DELETE cascades check + incident rows.
		checkService.recordResult(id, 900, failure());
		jdbc.sql("INSERT INTO uptime_incident (monitor_id) VALUES (?)").param(id).update();
		ResponseEntity<Map> deleted = exchange(HttpMethod.DELETE, "/api/internal/uptime/monitors/" + id, null,
				adminCookie);
		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(count("uptime_check")).isZero();
		assertThat(count("uptime_incident")).isZero();
	}

	@Test
	void mutationsRequireAdmin() {
		post("/api/internal/users", Map.of("email", "member@test.local", "password", "member-password", "role",
				"member"), adminCookie);
		String memberCookie = login("member@test.local", "member-password");

		assertThat(post("/api/internal/uptime/monitors", monitorBody(stubUrl("/health"), 60, 10), memberCookie)
			.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(post("/api/internal/uptime/monitors/test", Map.of("url", stubUrl("/health")), memberCookie)
			.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		// Reads are open to members. (/monitors returns a JSON array — fetch as String.)
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, memberCookie);
		assertThat(rest.exchange(url("/api/internal/uptime/monitors"), HttpMethod.GET, new HttpEntity<>(headers),
				String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(exchange(HttpMethod.GET, "/api/internal/uptime/overview", null, memberCookie).getStatusCode())
			.isEqualTo(HttpStatus.OK);
	}

	// ------------------------------------------------------------------ test connection

	@Test
	void testConnectionReportsResultWithoutRecording() {
		stubStatus = 200;
		ResponseEntity<Map> ok = post("/api/internal/uptime/monitors/test", Map.of("url", stubUrl("/health")),
				adminCookie);
		assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ok.getBody().get("success")).isEqualTo(true);
		assertThat(ok.getBody().get("status_code")).isEqualTo(200);
		assertThat((Integer) ok.getBody().get("latency_ms")).isNotNegative();

		stubStatus = 500;
		ResponseEntity<Map> fail = post("/api/internal/uptime/monitors/test", Map.of("url", stubUrl("/health")),
				adminCookie);
		assertThat(fail.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fail.getBody().get("success")).isEqualTo(false);
		assertThat(fail.getBody().get("status_code")).isEqualTo(500);
		assertThat((String) fail.getBody().get("error")).contains("500");

		ResponseEntity<Map> timeout = post("/api/internal/uptime/monitors/test",
				Map.of("url", stubUrl("/slow"), "timeout_seconds", 1), adminCookie);
		assertThat(timeout.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(timeout.getBody().get("success")).isEqualTo(false);
		assertThat(timeout.getBody().get("status_code")).isNull();

		// Ad-hoc probes are never recorded.
		assertThat(count("uptime_check")).isZero();
	}

	// ------------------------------------------------------------------ incidents

	@Test
	void thirdConsecutiveFailureOpensIncidentAndSuccessClosesIt() {
		long id = insertMonitor(stubUrl("/health"), 3600);

		checkService.recordResult(id, 3600, failure());
		checkService.recordResult(id, 3600, failure());
		assertThat(openIncidents(id)).isZero();

		checkService.recordResult(id, 3600, failure());
		assertThat(openIncidents(id)).isEqualTo(1);
		// Further failures keep the same incident (and refresh last_error).
		checkService.recordResult(id, 3600, new ProbeResult(false, 503, 12, "HTTP 503"));
		assertThat(openIncidents(id)).isEqualTo(1);
		assertThat(jdbc.sql("SELECT last_error FROM uptime_incident WHERE monitor_id = ? AND closed_at IS NULL")
			.param(id)
			.query(String.class)
			.single()).isEqualTo("HTTP 503");

		checkService.recordResult(id, 3600, success());
		assertThat(openIncidents(id)).isZero();
		assertThat(jdbc.sql("SELECT consecutive_failures FROM uptime_monitor WHERE id = ?")
			.param(id)
			.query(Integer.class)
			.single()).isZero();

		// A fresh streak opens a second incident row.
		checkService.recordResult(id, 3600, failure());
		checkService.recordResult(id, 3600, failure());
		checkService.recordResult(id, 3600, failure());
		assertThat(openIncidents(id)).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM uptime_incident WHERE monitor_id = ?")
			.param(id)
			.query(Long.class)
			.single()).isEqualTo(2);
	}

	// ------------------------------------------------------------------ scheduler

	@Test
	void schedulerRunsDueMonitors() {
		stubStatus = 200;
		long id = insertMonitor(stubUrl("/health"), 3600);
		jdbc.sql("UPDATE uptime_monitor SET next_check_at = now() WHERE id = ?").param(id).update();

		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline) && checkCount(id) == 0) {
			sleep();
		}
		assertThat(checkCount(id)).isGreaterThanOrEqualTo(1);
		// Re-armed one interval out — not due again.
		assertThat(jdbc.sql("SELECT next_check_at > now() + interval '30 minutes' FROM uptime_monitor WHERE id = ?")
			.param(id)
			.query(Boolean.class)
			.single()).isTrue();
	}

	// ------------------------------------------------------------------ overview + retention

	@Test
	void overviewAggregatesDailyBucketsAndRetentionSweeps() {
		long id = insertMonitor(stubUrl("/health"), 3600);
		// Today: 3 ok + 1 fail; yesterday: 2 ok; 91 days ago: 1 fail (past retention).
		insertCheck(id, Instant.now(), true, 200, 100);
		insertCheck(id, Instant.now(), true, 200, 200);
		insertCheck(id, Instant.now(), true, 200, 300);
		insertCheck(id, Instant.now(), false, 503, 40);
		insertCheck(id, Instant.now().minus(Duration.ofDays(1)), true, 200, 150);
		insertCheck(id, Instant.now().minus(Duration.ofDays(91)), false, null, 10);

		Map<String, Object> overview = exchange(HttpMethod.GET, "/api/internal/uptime/overview", null, adminCookie)
			.getBody();
		List<Map<String, Object>> monitors = (List<Map<String, Object>>) overview.get("monitors");
		assertThat(monitors).hasSize(1);
		Map<String, Object> monitor = monitors.get(0);
		assertThat(monitor.get("status")).isEqualTo("down"); // last check failed
		assertThat(monitor.get("open_incident")).isNull();

		List<Map<String, Object>> days = (List<Map<String, Object>>) monitor.get("days");
		// Sparse: only the two days inside the window that have data.
		assertThat(days).hasSize(2);
		Map<String, Object> today = days.get(1);
		assertThat(today.get("total")).isEqualTo(4);
		assertThat(today.get("failures")).isEqualTo(1);
		assertThat((Double) today.get("uptime_pct")).isEqualTo(75.0);
		assertThat(today.get("avg_latency_ms")).isEqualTo(160);

		// Open incident flips status and appears in the payload.
		jdbc.sql("INSERT INTO uptime_incident (monitor_id, last_error) VALUES (?, 'HTTP 503')").param(id).update();
		Map<String, Object> withIncident = ((List<Map<String, Object>>) exchange(HttpMethod.GET,
				"/api/internal/uptime/overview", null, adminCookie).getBody().get("monitors")).get(0);
		assertThat(withIncident.get("status")).isEqualTo("down");
		assertThat(((Map<String, Object>) withIncident.get("open_incident")).get("last_error")).isEqualTo("HTTP 503");

		// Retention removes the 91-day-old row only.
		scheduler.sweepRetention();
		assertThat(checkCount(id)).isEqualTo(5);
	}

	// ------------------------------------------------------------------ helpers

	private ProbeResult failure() {
		return new ProbeResult(false, null, 5, "java.net.ConnectException: refused");
	}

	private ProbeResult success() {
		return new ProbeResult(true, 200, 5, null);
	}

	/** First check is scheduled one interval out so the live scheduler doesn't interfere. */
	private long insertMonitor(String url, int intervalSeconds) {
		return jdbc.sql("""
				INSERT INTO uptime_monitor (project_id, environment, url, interval_seconds, next_check_at)
				VALUES (?, 'prod', ?, ?, now() + make_interval(secs => ?)) RETURNING id
				""")
			.param(projectId)
			.param(url)
			.param(intervalSeconds)
			.param(intervalSeconds)
			.query(Long.class)
			.single();
	}

	private void insertCheck(long monitorId, Instant at, boolean success, Integer status, int latencyMs) {
		jdbc.sql("""
				INSERT INTO uptime_check (monitor_id, checked_at, success, status_code, latency_ms)
				VALUES (?, ?, ?, ?, ?)
				""")
			.param(monitorId)
			.param(java.sql.Timestamp.from(at))
			.param(success)
			.param(status)
			.param(latencyMs)
			.update();
	}

	private long checkCount(long monitorId) {
		return jdbc.sql("SELECT count(*) FROM uptime_check WHERE monitor_id = ?")
			.param(monitorId)
			.query(Long.class)
			.single();
	}

	private long openIncidents(long monitorId) {
		return jdbc.sql("SELECT count(*) FROM uptime_incident WHERE monitor_id = ? AND closed_at IS NULL")
			.param(monitorId)
			.query(Long.class)
			.single();
	}

	private long count(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
	}

	private Map<String, Object> monitorBody(String url, int intervalSeconds, int timeoutSeconds) {
		return Map.of("project_id", projectId, "environment", "prod", "url", url, "interval_seconds", intervalSeconds,
				"timeout_seconds", timeoutSeconds);
	}

	private String stubUrl(String path) {
		return "http://localhost:" + stub.getAddress().getPort() + path;
	}

	private String login(String email, String password) {
		ResponseEntity<Map> response = rest.postForEntity(url("/api/internal/auth/login"),
				Map.of("email", email, "password", password), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
	}

	private ResponseEntity<Map> post(String path, Map<String, ?> body, String cookie) {
		return exchange(HttpMethod.POST, path, body, cookie);
	}

	private ResponseEntity<Map> exchange(HttpMethod method, String path, Map<String, ?> body, String cookie) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, cookie);
		headers.setContentType(MediaType.APPLICATION_JSON);
		return rest.exchange(url(path), method, new HttpEntity<>(body, headers), Map.class);
	}

	private void sleep() {
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}
}
