package dev.outpost.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.outpost.TestcontainersConfiguration;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestTemplate;

/**
 * The publisher-seam tracer bullet end to end (issue #43): a real Sentry
 * envelope through the ingest HTTP boundary → a new Issue → an HTTP POST to a
 * matching Generic JSON channel, plus the persisted history row. Asserts
 * external behavior only (POST arrives, row recorded), never notify internals.
 *
 * <p>The webhook receiver is a local {@link HttpServer} stub — the one new test
 * fixture (prior art: {@code UptimeIntegrationTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
		"outpost.ingest.linger-millis=50", "outpost.notifications.retry-backoff-millis=1",
		"outpost.notifications.max-attempts=3" })
@Import(TestcontainersConfiguration.class)
class NotificationDeliveryIntegrationTest {

	private record Received(String path, String body) {
	}

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	final RestTemplate rest = new RestTemplate();

	long projectId;
	String publicKey;
	HttpServer stub;
	final CopyOnWriteArrayList<Received> received = new CopyOnWriteArrayList<>();

	@BeforeEach
	void setUp() throws IOException {
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM notification_history").update();
		jdbc.sql("DELETE FROM notification_channel").update();
		jdbc.sql("DELETE FROM event").update();
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM project").update();
		projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES ('demo', 'Demo') RETURNING id")
			.query(Long.class)
			.single();
		publicKey = "0123456789abcdef0123456789abcdef";
		jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, ?)")
			.param(projectId)
			.param(publicKey)
			.update();

		stub = HttpServer.create(new InetSocketAddress(0), 0);
		// A receiver that records what it got and 200s.
		stub.createContext("/hook", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			received.add(new Received(exchange.getRequestURI().getPath(), body));
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		// A receiver that always fails, to exercise retries → failed row.
		stub.createContext("/dead", exchange -> {
			received.add(new Received(exchange.getRequestURI().getPath(), ""));
			exchange.sendResponseHeaders(500, -1);
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
	void firstEventOfAFingerprintFiresExactlyOnce() {
		long channelId = createChannel("generic_json", hookUrl("/hook"), true, "{new_issue}", "{}", "{}");

		postEnvelope(jsEventEnvelope("prod", "t.handleClick"));
		Received delivery = awaitDelivery("/hook");

		Map<String, Object> payload = json(delivery.body());
		assertThat(payload.get("version")).isEqualTo(1);
		assertThat(payload.get("type")).isEqualTo("new_issue");
		assertThat(cast(payload.get("project")).get("id")).isEqualTo((int) projectId);
		Map<String, Object> issue = cast(payload.get("issue"));
		assertThat(issue.get("title")).asString().startsWith("TypeError");
		assertThat(issue.get("environment")).isEqualTo("prod");
		assertThat(issue.get("link")).asString().endsWith("/issues/" + issue.get("id"));

		awaitHistoryStatus(channelId, "sent");
	}

	@Test
	void secondEventOfSameFingerprintDoesNotFire() {
		createChannel("generic_json", hookUrl("/hook"), true, "{new_issue}", "{}", "{}");

		postEnvelope(jsEventEnvelope("prod", "t.handleClick"));
		awaitDelivery("/hook");
		// Same fingerprint again: an update, not an insert — no second notification.
		postEnvelope(jsEventEnvelope("prod", "t.handleClick"));

		sleep(500);
		assertThat(received.stream().filter(r -> r.path().equals("/hook"))).hasSize(1);
		assertThat(historyCount()).isEqualTo(1);
	}

	@Test
	void projectFilterExcludesOtherProjects() {
		long otherProject = jdbc.sql("INSERT INTO project (slug, name) VALUES ('other', 'Other') RETURNING id")
			.query(Long.class)
			.single();
		createChannel("generic_json", hookUrl("/hook"), true, "{new_issue}", "{" + otherProject + "}", "{}");

		postEnvelope(jsEventEnvelope("prod", "t.handleClick"));

		sleep(500);
		assertThat(received).isEmpty();
		assertThat(historyCount()).isZero();
	}

	@Test
	void environmentFilterExcludesOtherEnvironments() {
		createChannel("generic_json", hookUrl("/hook"), true, "{new_issue}", "{}", "{staging}");

		postEnvelope(jsEventEnvelope("prod", "t.handleClick"));

		sleep(500);
		assertThat(received).isEmpty();
		assertThat(historyCount()).isZero();
	}

	@Test
	void environmentFilterMatchesListedEnvironment() {
		long channelId = createChannel("generic_json", hookUrl("/hook"), true, "{new_issue}", "{}", "{staging,prod}");

		postEnvelope(jsEventEnvelope("prod", "t.handleClick"));

		awaitDelivery("/hook");
		awaitHistoryStatus(channelId, "sent");
	}

	@Test
	void disabledChannelDoesNotFire() {
		createChannel("generic_json", hookUrl("/hook"), false, "{new_issue}", "{}", "{}");

		postEnvelope(jsEventEnvelope("prod", "t.handleClick"));

		sleep(500);
		assertThat(received).isEmpty();
		assertThat(historyCount()).isZero();
	}

	@Test
	void deadReceiverRecordsFailedRowAfterRetries() {
		long channelId = createChannel("generic_json", hookUrl("/dead"), true, "{new_issue}", "{}", "{}");

		postEnvelope(jsEventEnvelope("prod", "t.handleClick"));
		awaitHistoryStatus(channelId, "failed");

		// Three configured attempts all hit the dead receiver.
		assertThat(received.stream().filter(r -> r.path().equals("/dead")).count()).isEqualTo(3);
		String error = jdbc.sql("SELECT error_detail FROM notification_history WHERE channel_id = ?")
			.param(channelId)
			.query(String.class)
			.single();
		assertThat(error).contains("HTTP 500");
	}

	@Test
	void floodBeyondTheCapDeliversTenAndSuppressesTheRest() {
		long channelId = createChannel("generic_json", hookUrl("/hook"), true, "{new_issue}", "{}", "{}");

		// 15 never-seen fingerprints in a burst: each is a new Issue, one publish each.
		int flood = 15;
		for (int i = 0; i < flood; i++) {
			postEnvelope(jsEventEnvelope("prod", "flood.fn" + i));
		}

		// The cap is a fixed 10/minute: 10 delivered, the remaining 5 suppressed.
		awaitStatusCount(channelId, "suppressed", flood - 10);
		awaitStatusCount(channelId, "sent", 10);
		assertThat(statusCount(channelId, "pending")).isZero();
		assertThat(received.stream().filter(r -> r.path().equals("/hook")).count()).isEqualTo(10);

		// Suppressed rows carry a reason and never hit the network.
		String reason = jdbc.sql(
				"SELECT error_detail FROM notification_history WHERE channel_id = ? AND status = 'suppressed' LIMIT 1")
			.param(channelId)
			.query(String.class)
			.single();
		assertThat(reason).contains("rate limit");
	}

	@Test
	void rateCapIsPerChannelSoOneFloodedChannelDoesNotSuppressAnother() {
		long channelA = createChannel("generic_json", hookUrl("/hook"), true, "{new_issue}", "{}", "{}");
		long channelB = createChannel("generic_json", hookUrl("/hook"), true, "{new_issue}", "{}", "{}");

		// Every new Issue matches both channels; each channel has its own cap.
		int flood = 15;
		for (int i = 0; i < flood; i++) {
			postEnvelope(jsEventEnvelope("prod", "flood.fn" + i));
		}

		// Both channels independently deliver 10 and suppress 5 — neither starves the other.
		for (long channelId : new long[] { channelA, channelB }) {
			awaitStatusCount(channelId, "sent", 10);
			awaitStatusCount(channelId, "suppressed", flood - 10);
			assertThat(statusCount(channelId, "pending")).isZero();
		}
	}

	// ------------------------------------------------------------------ helpers

	private long createChannel(String type, String url, boolean enabled, String triggers, String projectFilter,
			String environmentFilter) {
		return jdbc.sql("""
				INSERT INTO notification_channel (name, type, url, enabled, triggers, project_filter, environment_filter)
				VALUES (?, ?, ?, ?, ?::text[], ?::bigint[], ?::text[])
				RETURNING id
				""")
			.param("ch-" + UUID.randomUUID())
			.param(type)
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

	private Received awaitDelivery(String path) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline)) {
			for (Received r : received) {
				if (r.path().equals(path)) {
					return r;
				}
			}
			sleep(100);
		}
		throw new AssertionError("no delivery to " + path + " arrived");
	}

	private void awaitHistoryStatus(long channelId, String status) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline)) {
			String actual = jdbc.sql("SELECT status FROM notification_history WHERE channel_id = ? ORDER BY id DESC LIMIT 1")
				.param(channelId)
				.query(String.class)
				.optional()
				.orElse(null);
			if (status.equals(actual)) {
				return;
			}
			sleep(100);
		}
		throw new AssertionError("history row for channel " + channelId + " never reached " + status);
	}

	private int historyCount() {
		return jdbc.sql("SELECT count(*) FROM notification_history").query(Integer.class).single();
	}

	private int statusCount(long channelId, String status) {
		return jdbc.sql("SELECT count(*) FROM notification_history WHERE channel_id = ? AND status = ?")
			.param(channelId)
			.param(status)
			.query(Integer.class)
			.single();
	}

	/** Waits until a channel has exactly {@code expected} rows in {@code status}, failing if it overshoots. */
	private void awaitStatusCount(long channelId, String status, int expected) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		int actual = 0;
		while (Instant.now().isBefore(deadline)) {
			actual = statusCount(channelId, status);
			if (actual == expected) {
				return;
			}
			if (actual > expected) {
				throw new AssertionError(
						"channel " + channelId + " has " + actual + " " + status + " rows, expected " + expected);
			}
			sleep(100);
		}
		throw new AssertionError(
				"channel " + channelId + " reached only " + actual + " " + status + " rows, expected " + expected);
	}

	private void postEnvelope(String envelope) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");
		ResponseEntity<String> response = rest.exchange(
				url("/api/" + projectId + "/envelope/?sentry_key=" + publicKey), HttpMethod.POST,
				new HttpEntity<>(envelope.getBytes(StandardCharsets.UTF_8), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	private String jsEventEnvelope(String environment, String function) {
		String event = """
				{"event_id":"%s","timestamp":"%s","platform":"javascript","level":"error","environment":"%s",\
				"exception":{"values":[{"type":"TypeError","value":"Cannot read properties of undefined (reading 'user')",\
				"stacktrace":{"frames":[\
				{"filename":"http://localhost:4200/main.js","function":"%s","in_app":true,"lineno":13,"colno":42061}\
				]}}]}}""".formatted(hexId(), Instant.now(), environment, function);
		return "{\"event_id\":\"" + hexId() + "\",\"sent_at\":\"" + Instant.now() + "\"}\n"
				+ "{\"type\":\"event\",\"length\":" + event.getBytes(StandardCharsets.UTF_8).length + "}\n" + event
				+ "\n";
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

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

	private static String hexId() {
		return UUID.randomUUID().toString().replace("-", "");
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
