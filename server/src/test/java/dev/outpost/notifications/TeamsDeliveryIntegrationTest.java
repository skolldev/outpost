package dev.outpost.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.outpost.TestcontainersConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * The Teams channel type end to end (issue #46): a real Sentry envelope through
 * the ingest HTTP boundary → a new Issue → an HTTP POST to a matching
 * <em>teams</em> channel carrying an Adaptive Card in the Teams Workflows
 * envelope, plus the persisted history row. Proves the formatter seam delivers a
 * second channel type through the exact same matching, history, and delivery
 * machinery as Generic JSON — nothing in that path changed to add Teams.
 *
 * <p>Asserts external behavior only (the POST body Teams would receive, the
 * recorded row), never notify internals. Reuses the stub {@link HttpServer}
 * receiver fixture (prior art: {@code NotificationDeliveryIntegrationTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
		"outpost.ingest.linger-millis=50", "outpost.notifications.retry-backoff-millis=1",
		"outpost.notifications.max-attempts=3" })
@Import(TestcontainersConfiguration.class)
class TeamsDeliveryIntegrationTest {

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
		stub.createContext("/teams", exchange -> {
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
	void newIssueDeliversAnAdaptiveCardToATeamsChannel() {
		long channelId = createTeamsChannel(hookUrl("/teams"));

		postEnvelope(jsEventEnvelope("prod", "t.handleClick"));
		Received delivery = awaitDelivery("/teams");

		// The Teams Workflows envelope: a message wrapping an Adaptive Card.
		Map<String, Object> payload = json(delivery.body());
		assertThat(payload.get("type")).isEqualTo("message");
		Map<String, Object> attachment = cast(list(payload.get("attachments")).get(0));
		assertThat(attachment.get("contentType")).isEqualTo("application/vnd.microsoft.card.adaptive");
		Map<String, Object> card = cast(attachment.get("content"));
		assertThat(card.get("type")).isEqualTo("AdaptiveCard");

		// The same summary facts the generic payload carries, plus a deep link.
		String cardJson = delivery.body();
		assertThat(cardJson).contains("TypeError").contains("prod").contains("Demo");
		assertThat(cardJson).contains("/issues/");

		awaitHistoryStatus(channelId, "sent");
		assertThat(jdbc.sql("SELECT trigger_type FROM notification_history WHERE channel_id = ?")
			.param(channelId)
			.query(String.class)
			.single()).isEqualTo("new_issue");
	}

	// ------------------------------------------------------------------ helpers

	private long createTeamsChannel(String url) {
		return jdbc.sql("""
				INSERT INTO notification_channel (name, type, url, enabled, triggers, project_filter, environment_filter)
				VALUES (?, 'teams', ?, true, '{new_issue}'::text[], '{}'::bigint[], '{}'::text[])
				RETURNING id
				""").param("ch-" + UUID.randomUUID()).param(url).query(Long.class).single();
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

	@SuppressWarnings("unchecked")
	private List<Object> list(Object value) {
		return (List<Object>) value;
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
