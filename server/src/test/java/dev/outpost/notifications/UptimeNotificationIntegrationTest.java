package dev.outpost.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.outpost.TestcontainersConfiguration;
import dev.outpost.uptime.UptimeCheckService;
import dev.outpost.uptime.UptimeProber.ProbeResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Uptime incident notifications end to end (issue #45): driving
 * {@link UptimeCheckService} directly (prior art: {@code UptimeIntegrationTest}),
 * three consecutive failures produce exactly one {@code incident_started}
 * delivery and a recovery produces exactly one {@code incident_resolved}
 * delivery carrying the downtime. Asserts external behavior only — the POST that
 * arrives at a stub webhook receiver and the persisted history rows — never the
 * notify module's internals.
 *
 * <p>Monitors are seeded with a future {@code next_check_at} so the live uptime
 * scheduler never probes them; every check here is issued by the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
				"outpost.notifications.retry-backoff-millis=1", "outpost.notifications.max-attempts=3" })
@Import(TestcontainersConfiguration.class)
class UptimeNotificationIntegrationTest {

	private record Received(String path, String body) {
	}

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UptimeCheckService checkService;

	long projectId;
	HttpServer stub;
	final CopyOnWriteArrayList<Received> received = new CopyOnWriteArrayList<>();

	@BeforeEach
	void setUp() throws IOException {
		jdbc.sql("DELETE FROM notification_history").update();
		jdbc.sql("DELETE FROM notification_channel").update();
		jdbc.sql("DELETE FROM uptime_monitor").update();
		jdbc.sql("DELETE FROM project").update();
		projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES ('shop', 'Shop') RETURNING id")
			.query(Long.class)
			.single();

		stub = HttpServer.create(new InetSocketAddress(0), 0);
		stub.createContext("/hook", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			received.add(new Received(exchange.getRequestURI().getPath(), body));
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		stub.start();
	}

	@AfterEach
	void tearDown() {
		if (stub != null) {
			stub.stop(0);
		}
	}

	@Test
	void threeFailuresFireOneStartedRecoveryFiresOneResolvedWithDowntime() {
		long channelId = createChannel(hookUrl("/hook"), true, "{incident_started,incident_resolved}", "{}", "{}");
		long monitorId = insertMonitor("https://shop.example/health", "prod");

		// Two failures: below threshold, nothing published.
		checkService.recordResult(monitorId, 3600, failure("HTTP 500"));
		checkService.recordResult(monitorId, 3600, failure("HTTP 500"));
		sleep(300);
		assertThat(received).isEmpty();

		// Third failure opens the incident and fires exactly one started delivery.
		checkService.recordResult(monitorId, 3600, failure("HTTP 503"));
		Received started = awaitDelivery("incident_started");
		Map<String, Object> startedPayload = json(started.body());
		assertThat(startedPayload.get("version")).isEqualTo(1);
		assertThat(cast(startedPayload.get("project")).get("slug")).isEqualTo("shop");
		Map<String, Object> monitor = cast(startedPayload.get("monitor"));
		assertThat(monitor.get("url")).isEqualTo("https://shop.example/health");
		assertThat(monitor.get("environment")).isEqualTo("prod");
		assertThat(monitor.get("link")).asString().endsWith("/uptime");
		assertThat(cast(startedPayload.get("incident")).get("failure_reason")).isEqualTo("HTTP 503");

		// A fourth failure only refreshes last_error — no second started delivery.
		checkService.recordResult(monitorId, 3600, failure("HTTP 504"));
		sleep(300);
		assertThat(deliveries("incident_started")).isEqualTo(1);

		// Recovery closes the incident and fires exactly one resolved delivery.
		checkService.recordResult(monitorId, 3600, success());
		Received resolved = awaitDelivery("incident_resolved");
		Map<String, Object> incident = cast(json(resolved.body()).get("incident"));
		assertThat(incident.get("opened_at")).isNotNull();
		assertThat(incident.get("closed_at")).isNotNull();
		assertThat((Integer) incident.get("downtime_seconds")).isNotNegative();
		assertThat(incident.get("downtime_human")).asString().endsWith("s");

		sleep(300);
		assertThat(deliveries("incident_started")).isEqualTo(1);
		assertThat(deliveries("incident_resolved")).isEqualTo(1);

		awaitHistoryStatus(channelId, "incident_started", "sent");
		awaitHistoryStatus(channelId, "incident_resolved", "sent");
	}

	@Test
	void successWhileHealthyPublishesNothing() {
		createChannel(hookUrl("/hook"), true, "{incident_started,incident_resolved}", "{}", "{}");
		long monitorId = insertMonitor("https://shop.example/health", "prod");

		checkService.recordResult(monitorId, 3600, success());
		checkService.recordResult(monitorId, 3600, success());

		sleep(400);
		assertThat(received).isEmpty();
		assertThat(historyCount()).isZero();
	}

	@Test
	void channelNotSubscribedToStartedDoesNotFire() {
		// Subscribed only to resolved: the open transition must not reach it.
		createChannel(hookUrl("/hook"), true, "{incident_resolved}", "{}", "{}");
		long monitorId = insertMonitor("https://shop.example/health", "prod");

		openIncidentViaFailures(monitorId);
		sleep(400);
		assertThat(deliveries("incident_started")).isZero();

		checkService.recordResult(monitorId, 3600, success());
		awaitDelivery("incident_resolved");
	}

	@Test
	void environmentFilterExcludesOtherEnvironments() {
		createChannel(hookUrl("/hook"), true, "{incident_started,incident_resolved}", "{}", "{staging}");
		long monitorId = insertMonitor("https://shop.example/health", "prod");

		openIncidentViaFailures(monitorId);
		checkService.recordResult(monitorId, 3600, success());

		sleep(400);
		assertThat(received).isEmpty();
		assertThat(historyCount()).isZero();
	}

	@Test
	void projectFilterExcludesOtherProjects() {
		long otherProject = jdbc.sql("INSERT INTO project (slug, name) VALUES ('other', 'Other') RETURNING id")
			.query(Long.class)
			.single();
		createChannel(hookUrl("/hook"), true, "{incident_started,incident_resolved}", "{" + otherProject + "}", "{}");
		long monitorId = insertMonitor("https://shop.example/health", "prod");

		openIncidentViaFailures(monitorId);
		sleep(400);
		assertThat(received).isEmpty();
		assertThat(historyCount()).isZero();
	}

	// ------------------------------------------------------------------ helpers

	private void openIncidentViaFailures(long monitorId) {
		checkService.recordResult(monitorId, 3600, failure("HTTP 500"));
		checkService.recordResult(monitorId, 3600, failure("HTTP 500"));
		checkService.recordResult(monitorId, 3600, failure("HTTP 503"));
	}

	private ProbeResult failure(String error) {
		return new ProbeResult(false, null, 5, error);
	}

	private ProbeResult success() {
		return new ProbeResult(true, 200, 5, null);
	}

	/** Seeded one hour out so the live scheduler never probes it; the test drives checks. */
	private long insertMonitor(String url, String environment) {
		return jdbc.sql("""
				INSERT INTO uptime_monitor (project_id, environment, url, interval_seconds, next_check_at)
				VALUES (?, ?, ?, 3600, now() + interval '1 hour') RETURNING id
				""").param(projectId).param(environment).param(url).query(Long.class).single();
	}

	private long createChannel(String url, boolean enabled, String triggers, String projectFilter,
			String environmentFilter) {
		return jdbc.sql("""
				INSERT INTO notification_channel (name, type, url, enabled, triggers, project_filter, environment_filter)
				VALUES (?, 'generic_json', ?, ?, ?::text[], ?::bigint[], ?::text[])
				RETURNING id
				""")
			.param("ch-" + UUID.randomUUID())
			.param(url)
			.param(enabled)
			.param(triggers)
			.param(projectFilter)
			.param(environmentFilter)
			.query(Long.class)
			.single();
	}

	private String hookUrl(String path) {
		return "http://localhost:" + stub.getAddress().getPort() + path;
	}

	/** Awaits a delivery whose payload {@code type} matches, returning it. */
	private Received awaitDelivery(String type) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline)) {
			for (Received r : received) {
				if (type.equals(json(r.body()).get("type"))) {
					return r;
				}
			}
			sleep(100);
		}
		throw new AssertionError("no " + type + " delivery arrived");
	}

	private long deliveries(String type) {
		return received.stream().filter(r -> type.equals(json(r.body()).get("type"))).count();
	}

	private void awaitHistoryStatus(long channelId, String triggerType, String status) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline)) {
			String actual = jdbc
				.sql("SELECT status FROM notification_history WHERE channel_id = ? AND trigger_type = ? ORDER BY id DESC LIMIT 1")
				.param(channelId)
				.param(triggerType)
				.query(String.class)
				.optional()
				.orElse(null);
			if (status.equals(actual)) {
				return;
			}
			sleep(100);
		}
		throw new AssertionError("history row for " + triggerType + " never reached " + status);
	}

	private int historyCount() {
		return jdbc.sql("SELECT count(*) FROM notification_history").query(Integer.class).single();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> json(String body) {
		try {
			return new tools.jackson.databind.ObjectMapper().readValue(body, Map.class);
		}
		catch (Exception e) {
			throw new AssertionError("payload not JSON: " + body, e);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> cast(Object value) {
		return (Map<String, Object>) value;
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
