package dev.outpost.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.outpost.TestcontainersConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestTemplate;

/**
 * Admin test-send and delivery visibility end to end (issue #44). Drives the
 * Admin-scoped endpoints over HTTP: {@code POST /{id}/test} runs the full
 * pipeline (a real POST lands at the stub receiver, a history row is recorded)
 * and reports the outcome inline; {@code GET /{id}/history} lists recent
 * Notifications; the channel list carries each channel's last outcome and time.
 *
 * <p>Reuses the two prior fixtures: a local {@link HttpServer} stub receiver
 * ({@code NotificationDeliveryIntegrationTest}) and the admin-login helper
 * ({@code NotificationChannelIntegrationTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
				"outpost.notifications.retry-backoff-millis=1", "outpost.notifications.max-attempts=3" })
@Import(TestcontainersConfiguration.class)
class TestSendIntegrationTest {

	private static final String CHANNELS = "/api/internal/notifications/channels";

	private record Received(String path, String body) {
	}

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	final RestTemplate rest = new RestTemplate();

	String adminCookie;
	HttpServer stub;
	final CopyOnWriteArrayList<Received> received = new CopyOnWriteArrayList<>();

	@BeforeEach
	void setUp() throws IOException {
		rest.setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM notification_history").update();
		jdbc.sql("DELETE FROM notification_channel").update();
		jdbc.sql("DELETE FROM app_user WHERE email <> 'admin@test.local'").update();
		adminCookie = login("admin@test.local", "test-password");

		stub = HttpServer.create(new InetSocketAddress(0), 0);
		stub.createContext("/hook", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			received.add(new Received(exchange.getRequestURI().getPath(), body));
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
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
	@SuppressWarnings("unchecked")
	void testSendDeliversToReceiverAndRecordsSentRow() {
		long channelId = createChannel(hookUrl("/hook"), true);

		ResponseEntity<Map> response = exchange(HttpMethod.POST, CHANNELS + "/" + channelId + "/test", null,
				adminCookie);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().get("status")).isEqualTo("sent");
		// The full pipeline ran: a real POST arrived at the receiver, carrying the
		// documented test payload.
		assertThat(received).hasSize(1);
		Received delivery = received.get(0);
		assertThat(delivery.path()).isEqualTo("/hook");
		Map<String, Object> payload = json(delivery.body());
		assertThat(payload.get("version")).isEqualTo(1);
		assertThat(payload.get("type")).isEqualTo("test");
		assertThat(((Map<String, Object>) payload.get("channel")).get("id")).isEqualTo((int) channelId);
		// And the history row landed as 'sent' with the test trigger type.
		Map<String, Object> row = latestHistory(channelId);
		assertThat(row.get("status")).isEqualTo("sent");
		assertThat(row.get("trigger_type")).isEqualTo("test");
	}

	@Test
	void testSendToDeadReceiverReportsFailedAndRecordsFailedRow() {
		long channelId = createChannel(hookUrl("/dead"), true);

		ResponseEntity<Map> response = exchange(HttpMethod.POST, CHANNELS + "/" + channelId + "/test", null,
				adminCookie);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().get("status")).isEqualTo("failed");
		assertThat((String) response.getBody().get("error_detail")).contains("HTTP 500");
		// All three configured attempts hit the dead receiver.
		assertThat(received.stream().filter(r -> r.path().equals("/dead")).count()).isEqualTo(3);
		assertThat(latestHistory(channelId).get("status")).isEqualTo("failed");
	}

	@Test
	void testSendRefusesDisabledChannelWithoutDelivering() {
		long channelId = createChannel(hookUrl("/hook"), false);

		ResponseEntity<Map> response = exchange(HttpMethod.POST, CHANNELS + "/" + channelId + "/test", null,
				adminCookie);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(received).isEmpty();
		assertThat(historyCount(channelId)).isZero();
	}

	@Test
	void testSendOfMissingChannelIs404() {
		assertThat(exchange(HttpMethod.POST, CHANNELS + "/999999/test", null, adminCookie).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@SuppressWarnings("unchecked")
	void historyListsRecentNotificationsNewestFirst() {
		long channelId = createChannel(hookUrl("/hook"), true);
		exchange(HttpMethod.POST, CHANNELS + "/" + channelId + "/test", null, adminCookie);
		exchange(HttpMethod.POST, CHANNELS + "/" + channelId + "/test", null, adminCookie);

		ResponseEntity<Map[]> response = rest.exchange(url(CHANNELS + "/" + channelId + "/history"), HttpMethod.GET,
				new HttpEntity<>(authHeaders(adminCookie)), Map[].class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map<String, Object>[] rows = response.getBody();
		assertThat(rows).hasSize(2);
		assertThat(rows[0].get("trigger_type")).isEqualTo("test");
		assertThat(rows[0].get("status")).isEqualTo("sent");
		assertThat(rows[0].get("summary")).asString().contains("test");
		// Newest first: the two ids descend.
		assertThat(((Number) rows[0].get("id")).longValue())
			.isGreaterThan(((Number) rows[1].get("id")).longValue());
	}

	@Test
	void historyOfMissingChannelIs404() {
		assertThat(rest
			.exchange(url(CHANNELS + "/999999/history"), HttpMethod.GET, new HttpEntity<>(authHeaders(adminCookie)),
					Map[].class)
			.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@SuppressWarnings("unchecked")
	void channelListCarriesLastDeliveryOutcomeAndTime() {
		long channelId = createChannel(hookUrl("/hook"), true);
		// Before any delivery: last_status is null.
		Map<String, Object> before = listChannels()[0];
		assertThat(before.get("last_status")).isNull();
		assertThat(before.get("last_delivery_at")).isNull();

		exchange(HttpMethod.POST, CHANNELS + "/" + channelId + "/test", null, adminCookie);

		Map<String, Object> after = listChannels()[0];
		assertThat(after.get("last_status")).isEqualTo("sent");
		assertThat(after.get("last_delivery_at")).isNotNull();
	}

	@Test
	void testSendRequiresAdmin() {
		long channelId = createChannel(hookUrl("/hook"), true);
		post("/api/internal/users",
				Map.of("email", "member@test.local", "password", "member-password", "role", "member"), adminCookie);
		String memberCookie = login("member@test.local", "member-password");

		assertThat(exchange(HttpMethod.POST, CHANNELS + "/" + channelId + "/test", null, memberCookie).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(exchange(HttpMethod.GET, CHANNELS + "/" + channelId + "/history", null, memberCookie).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(received).isEmpty();
	}

	// ------------------------------------------------------------------ helpers

	private long createChannel(String url, boolean enabled) {
		return jdbc.sql("""
				INSERT INTO notification_channel (name, type, url, enabled, triggers, project_filter, environment_filter)
				VALUES (?, 'generic_json', ?, ?, '{new_issue}'::text[], '{}'::bigint[], '{}'::text[])
				RETURNING id
				""").param("ch-" + java.util.UUID.randomUUID()).param(url).param(enabled).query(Long.class).single();
	}

	private String hookUrl(String path) {
		return "http://localhost:" + stub.getAddress().getPort() + path;
	}

	private Map<String, Object> latestHistory(long channelId) {
		return jdbc.sql("""
				SELECT trigger_type, status FROM notification_history
				WHERE channel_id = ? ORDER BY id DESC LIMIT 1
				""")
			.param(channelId)
			.query((rs, i) -> Map.<String, Object>of("trigger_type", rs.getString("trigger_type"), "status",
					rs.getString("status")))
			.single();
	}

	private int historyCount(long channelId) {
		return jdbc.sql("SELECT count(*) FROM notification_history WHERE channel_id = ?")
			.param(channelId)
			.query(Integer.class)
			.single();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object>[] listChannels() {
		return rest
			.exchange(url(CHANNELS), HttpMethod.GET, new HttpEntity<>(authHeaders(adminCookie)), Map[].class)
			.getBody();
	}

	private String login(String email, String password) {
		ResponseEntity<Map> response = rest.postForEntity(url("/api/internal/auth/login"),
				Map.of("email", email, "password", password), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
	}

	private HttpHeaders authHeaders(String cookie) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, cookie);
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	private ResponseEntity<Map> post(String path, Map<String, ?> body, String cookie) {
		return exchange(HttpMethod.POST, path, body, cookie);
	}

	private ResponseEntity<Map> exchange(HttpMethod method, String path, Map<String, ?> body, String cookie) {
		return rest.exchange(url(path), method, new HttpEntity<>(body, authHeaders(cookie)), Map.class);
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

	private String url(String path) {
		return "http://localhost:" + port + path;
	}
}
