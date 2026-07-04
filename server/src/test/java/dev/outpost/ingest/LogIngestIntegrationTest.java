package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
 * Phase 3 exit check (§12 P3): log envelope items from both SDK shapes go
 * through ingest → pipeline → storage and come back out of {@code GET /logs}
 * filterable; event↔log correlation works with and without a trace_id; the
 * SSE live tail streams newly stored records.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
		"outpost.ingest.linger-millis=50" })
@Import(TestcontainersConfiguration.class)
class LogIngestIntegrationTest {

	private static final String TRACE_ID = "5b8efff798038103d269b633813fc60c";

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	final RestTemplate rest = new RestTemplate();

	long projectId;
	String publicKey;
	String sessionCookie;

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM log_record").update();
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
		sessionCookie = login();
	}

	@Test
	void logsFromBothSdkShapesAreIngestedAndFilterable() {
		// Angular SDK shape: batched log item with typed attributes, prod, traced.
		postEnvelope(jsLogEnvelope());
		// Java SDK shape: separate envelope, dev environment, no trace.
		postEnvelope(javaLogEnvelope());

		List<Map<String, Object>> logs = awaitLogs("", 3);
		assertThat(logs).hasSize(3);
		// Newest first (keyset descending).
		assertThat(instant(logs.get(0))).isAfterOrEqualTo(instant(logs.get(1)));

		// Level filter.
		List<Map<String, Object>> errors = getLogs("&level=error");
		assertThat(errors).hasSize(1);
		assertThat(errors.get(0).get("body")).isEqualTo("Checkout failed for order 42");

		// Environment filter (from the sentry.environment attribute).
		assertThat(getLogs("&environment=dev")).hasSize(1);
		assertThat(getLogs("&environment=prod")).hasSize(2);

		// Body substring search (ILIKE).
		List<Map<String, Object>> search = getLogs("&query=checkout");
		assertThat(search).extracting(row -> row.get("body")).containsExactlyInAnyOrder("Checkout failed for order 42",
				"User clicked checkout");

		// trace_id filter.
		assertThat(getLogs("&trace_id=" + TRACE_ID)).hasSize(2);

		// Attribute key=value and bare-key filters; attributes are flattened.
		List<Map<String, Object>> byAttr = getLogs("&attr=cart.size=3");
		assertThat(byAttr).hasSize(1);
		Map<String, Object> attributes = cast(byAttr.get(0).get("attributes"));
		assertThat(attributes.get("cart.size")).isEqualTo(3);
		assertThat(getLogs("&attr=logger.name")).hasSize(1);

		// span_id extracted from the sentry.trace.parent_span_id attribute.
		assertThat(getLogs("&trace_id=" + TRACE_ID)).anySatisfy(row ->
				assertThat(row.get("span_id")).isEqualTo("b0e6f15b45c36285"));

		// Environments were auto-upserted for the filter bar.
		List<String> environments = jdbc.sql("SELECT name FROM environment WHERE project_id = ? ORDER BY name")
			.param(projectId)
			.query(String.class)
			.list();
		assertThat(environments).containsExactly("dev", "prod");
	}

	@Test
	void correlationWorksWithAndWithoutTraceId() {
		postEnvelope(jsLogEnvelope());
		postEnvelope(javaLogEnvelope());
		awaitLogs("", 3);

		// With a trace: the issue-detail "logs around this event" query by trace_id.
		assertThat(getLogs("&trace_id=" + TRACE_ID)).hasSize(2);

		// Without a trace: ±60 s project window around the event timestamp.
		Instant now = Instant.now();
		String window = "&from=" + now.minusSeconds(60) + "&to=" + now.plusSeconds(60);
		assertThat(getLogs(window)).hasSize(3);
		String pastWindow = "&from=" + now.minusSeconds(7200) + "&to=" + now.minusSeconds(3600);
		assertThat(getLogs(pastWindow)).isEmpty();
	}

	@Test
	void liveTailStreamsNewRecords() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(url("/api/internal/logs?live=true&project=" + projectId + "&level=error")))
			.header("Cookie", sessionCookie)
			.header("Accept", "text/event-stream")
			.build();
		HttpResponse<java.io.InputStream> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
			.get(10, TimeUnit.SECONDS);
		assertThat(response.statusCode()).isEqualTo(200);

		// Reader first so the subscription is live, then ingest.
		CompletableFuture<String> firstData = CompletableFuture.supplyAsync(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.startsWith("data:")) {
						return line;
					}
				}
				return null;
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		postEnvelope(jsLogEnvelope());

		String data = firstData.get(15, TimeUnit.SECONDS);
		// The level=error filter must let only the error record through.
		assertThat(data).contains("Checkout failed for order 42");
		assertThat(data).contains("\"project_id\":" + projectId);
	}

	@Test
	void tailRequiresSession() {
		ResponseEntity<String> unauthorized = rest
			.getForEntity(url("/api/internal/logs?live=true&project=" + projectId), String.class);
		assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ------------------------------------------------------------------ helpers

	/** Angular SDK shape: one log item, two records, typed attributes, traced. */
	private String jsLogEnvelope() {
		double now = System.currentTimeMillis() / 1000.0;
		String payload = """
				{"items":[
				{"timestamp":%s,"trace_id":"%s","level":"info","severity_number":9,"body":"User clicked checkout",\
				"attributes":{"sentry.environment":{"value":"prod","type":"string"},\
				"sentry.release":{"value":"demo-frontend@1.0.0","type":"string"},\
				"sentry.trace.parent_span_id":{"value":"b0e6f15b45c36285","type":"string"},\
				"cart.size":{"value":3,"type":"integer"}}},
				{"timestamp":%s,"trace_id":"%s","level":"error","severity_number":17,"body":"Checkout failed for order 42",\
				"attributes":{"sentry.environment":{"value":"prod","type":"string"},\
				"sentry.release":{"value":"demo-frontend@1.0.0","type":"string"}}}
				]}""".formatted(now, TRACE_ID, now, TRACE_ID);
		return "{\"sent_at\":\"" + Instant.now() + "\"}\n"
				+ "{\"type\":\"log\",\"item_count\":2,\"content_type\":\"application/vnd.sentry.items.log+json\",\"length\":"
				+ payload.getBytes(StandardCharsets.UTF_8).length + "}\n" + payload + "\n";
	}

	/** Java SDK (sentry-logback) shape: dev environment, logger attribute, no trace. */
	private String javaLogEnvelope() {
		double now = System.currentTimeMillis() / 1000.0;
		String payload = """
				{"items":[\
				{"timestamp":%s,"level":"warn","severity_number":13,"body":"Order 4711 has no customer, skipping",\
				"attributes":{"sentry.environment":{"value":"dev","type":"string"},\
				"sentry.release":{"value":"demo-backend@1.0.0","type":"string"},\
				"logger.name":{"value":"dev.demo.OrderService","type":"string"}}}]}""".formatted(now);
		return "{\"sent_at\":\"" + Instant.now() + "\"}\n"
				+ "{\"type\":\"log\",\"item_count\":1,\"content_type\":\"application/vnd.sentry.items.log+json\",\"length\":"
				+ payload.getBytes(StandardCharsets.UTF_8).length + "}\n" + payload + "\n";
	}

	private void postEnvelope(String envelope) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");
		ResponseEntity<String> response = rest.exchange(
				url("/api/" + projectId + "/envelope/?sentry_key=" + publicKey), HttpMethod.POST,
				new HttpEntity<>(envelope.getBytes(StandardCharsets.UTF_8), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	private List<Map<String, Object>> awaitLogs(String filter, int expected) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline)) {
			List<Map<String, Object>> logs = getLogs(filter);
			if (logs.size() >= expected) {
				return logs;
			}
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		return getLogs(filter);
	}

	private List<Map<String, Object>> getLogs(String filter) {
		Map<String, Object> body = getJson("/api/internal/logs?project=" + projectId + filter);
		return cast(body.get("logs"));
	}

	private Instant instant(Map<String, Object> row) {
		return Instant.parse((String) row.get("timestamp"));
	}

	private String login() {
		ResponseEntity<Map> response = rest.postForEntity(url("/api/internal/auth/login"),
				Map.of("email", "admin@test.local", "password", "test-password"), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).isNotNull();
		return setCookie.split(";")[0];
	}

	private Map<String, Object> getJson(String path) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, sessionCookie);
		ResponseEntity<Map> response = rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return cast(response.getBody());
	}

	@SuppressWarnings("unchecked")
	private <T> T cast(Object value) {
		return (T) value;
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}
}
