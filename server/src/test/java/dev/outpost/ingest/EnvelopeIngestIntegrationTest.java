package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
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
 * End-to-end compat test (§13.1 skeleton): real SDK-shaped envelopes from both
 * supported SDK families go through ingest → pipeline → storage and come back
 * out of the query API grouped and env-filterable. This is the Phase 1 exit
 * check and the future gate for SDK version bumps.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
		"outpost.ingest.linger-millis=50" })
@Import(TestcontainersConfiguration.class)
class EnvelopeIngestIntegrationTest {

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
		// JDK client factory: the default one cannot send PATCH requests.
		rest.setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
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
	void angularAndSpringEventsAreIngestedGroupedAndEnvFilterable() {
		// Angular SDK: two identical prod errors (auth via query param), one with a
		// different stacktrace → separate issue.
		postEnvelope(jsEventEnvelope("prod", "t.handleClick"), null, "?sentry_key=" + publicKey, false);
		postEnvelope(jsEventEnvelope("prod", "t.handleClick"), null, "?sentry_key=" + publicKey, false);
		postEnvelope(jsEventEnvelope("prod", "t.otherHandler"), null, "?sentry_key=" + publicKey, false);
		// Java SDK: gzipped body, X-Sentry-Auth header, dev environment.
		postEnvelope(javaEventEnvelope("dev"),
				"Sentry sentry_version=7, sentry_client=sentry.java/8.44.0, sentry_key=" + publicKey, "", true);

		List<Map<String, Object>> issues = awaitIssues(3, "");
		assertThat(issues).hasSize(3);
		assertThat(issues).anySatisfy(issue -> {
			assertThat(issue.get("title")).isEqualTo("TypeError: Cannot read properties of undefined (reading 'user')");
			assertThat(this.<List<String>>cast(issue.get("environments"))).containsExactly("prod");
		});
		assertThat(issues).anySatisfy(issue -> {
			assertThat(issue.get("title")).asString().startsWith("IllegalStateException");
			assertThat(this.<List<String>>cast(issue.get("environments"))).containsExactly("dev");
		});
		// Grouping: the two identical JS errors share one issue with count 2.
		Map<String, Object> grouped = issues.stream()
			.filter(i -> ((Number) i.get("event_count")).longValue() == 2)
			.findFirst()
			.orElseThrow();
		assertThat(grouped.get("level")).isEqualTo("error");
		assertThat(grouped.get("sparkline")).isNotNull();

		// Environment filter: dev shows only the Java issue.
		List<Map<String, Object>> devIssues = getIssues("&environment=dev");
		assertThat(devIssues).hasSize(1);
		assertThat(devIssues.get(0).get("title")).asString().startsWith("IllegalStateException");

		// Issue detail + events + event payload round-trip.
		long issueId = ((Number) grouped.get("id")).longValue();
		Map<String, Object> detail = getJson("/api/internal/issues/" + issueId);
		assertThat((List<?>) detail.get("env_stats")).hasSize(1);

		Map<String, Object> events = getJson("/api/internal/issues/" + issueId + "/events");
		List<Map<String, Object>> eventList = cast(events.get("events"));
		assertThat(eventList).hasSize(2);

		Map<String, Object> event = getJson("/api/internal/events/" + eventList.get(0).get("id"));
		assertThat(event.get("environment")).isEqualTo("prod");
		assertThat(event.get("prev_event_id")).isNotNull();
		Map<String, Object> data = cast(event.get("data"));
		assertThat(data.get("platform")).isEqualTo("javascript");

		// Resolve → new event → regression back to unresolved.
		patchIssue(issueId, "resolved");
		postEnvelope(jsEventEnvelope("prod", "t.handleClick"), null, "?sentry_key=" + publicKey, false);
		awaitEventCount(issueId, 3);
		Map<String, Object> regressed = getJson("/api/internal/issues/" + issueId);
		assertThat(regressed.get("status")).isEqualTo("unresolved");
	}

	@Test
	void rejectsInvalidKeyAndMalformedEnvelopes() {
		ResponseEntity<String> forbidden = postEnvelope(jsEventEnvelope("prod", "x"), null,
				"?sentry_key=ffffffffffffffffffffffffffffffff", false);
		assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		ResponseEntity<String> noKey = postEnvelope(jsEventEnvelope("prod", "x"), null, "", false);
		assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");
		ResponseEntity<String> malformed = rest.exchange(
				url("/api/" + projectId + "/envelope/?sentry_key=" + publicKey), HttpMethod.POST,
				new HttpEntity<>("not json\n{}".getBytes(StandardCharsets.UTF_8), headers), String.class);
		assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void skipsUnknownItemTypesSilently() {
		String envelope = """
				{"event_id":"%s"}
				{"type":"sessions"}
				{"aggregates":[]}
				{"type":"some_future_type"}
				{"whatever":true}
				""".formatted(hexId());
		ResponseEntity<String> response = postEnvelope(envelope, null, "?sentry_key=" + publicKey, false);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("id");
	}

	@Test
	void authViaDsnInEnvelopeHeader() {
		String envelope = "{\"dsn\":\"http://" + publicKey + "@localhost:" + port + "/" + projectId + "\"}\n"
				+ "{\"type\":\"event\"}\n"
				+ "{\"event_id\":\"" + hexId() + "\",\"platform\":\"javascript\",\"level\":\"error\",\"message\":\"dsn auth works\"}\n";
		ResponseEntity<String> response = postEnvelope(envelope, null, "", false);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void queryApiRequiresSession() {
		ResponseEntity<String> unauthorized = rest.getForEntity(url("/api/internal/issues"), String.class);
		assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ------------------------------------------------------------------ helpers

	private String login() {
		ResponseEntity<Map> response = rest.postForEntity(url("/api/internal/auth/login"),
				Map.of("email", "admin@test.local", "password", "test-password"), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).isNotNull();
		return setCookie.split(";")[0];
	}

	private ResponseEntity<String> postEnvelope(String envelope, String sentryAuth, String query, boolean gzip) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");
		if (sentryAuth != null) {
			headers.set("X-Sentry-Auth", sentryAuth);
		}
		byte[] body = envelope.getBytes(StandardCharsets.UTF_8);
		if (gzip) {
			headers.set("Content-Encoding", "gzip");
			body = gzip(body);
		}
		return rest.exchange(url("/api/" + projectId + "/envelope/" + query), HttpMethod.POST,
				new HttpEntity<>(body, headers), String.class);
	}

	private String jsEventEnvelope(String environment, String function) {
		String event = """
				{"event_id":"%s","timestamp":"%s","platform":"javascript","level":"error","environment":"%s",\
				"release":"demo-frontend@1.0.0","user":{"id":"user-1"},\
				"contexts":{"trace":{"trace_id":"771a43a4192642f0b136d5159a501700","span_id":"b0e6f15b45c36285"}},\
				"exception":{"values":[{"type":"TypeError","value":"Cannot read properties of undefined (reading 'user')",\
				"stacktrace":{"frames":[\
				{"filename":"http://localhost:4200/polyfills.js","function":"invokeTask","in_app":false,"lineno":10,"colno":5},\
				{"filename":"http://localhost:4200/main.js","function":"%s","in_app":true,"lineno":13,"colno":42061}\
				]}}]}}""".formatted(hexId(), Instant.now(), environment, function);
		return "{\"event_id\":\"" + hexId() + "\",\"sent_at\":\"" + Instant.now() + "\"}\n"
				+ "{\"type\":\"event\",\"length\":" + event.getBytes(StandardCharsets.UTF_8).length + "}\n" + event
				+ "\n";
	}

	private String javaEventEnvelope(String environment) {
		String event = """
				{"event_id":"%s","timestamp":"%s","platform":"java","level":"error","environment":"%s",\
				"release":"demo-backend@1.0.0","logentry":{"formatted":"Unhandled exception in request"},\
				"exception":{"values":[{"type":"IllegalStateException","value":"order 4711 has no customer",\
				"module":"java.lang","stacktrace":{"frames":[\
				{"module":"org.apache.catalina.core.StandardEngineValve","function":"invoke","in_app":false,"lineno":74},\
				{"module":"dev.demo.OrderService$$Lambda$123/0x00007f","function":"lambda$process$2","in_app":true,"lineno":42},\
				{"module":"dev.demo.OrderService","function":"loadCustomer","in_app":true,"lineno":88}\
				]}}]}}""".formatted(hexId(), Instant.now(), environment);
		return "{\"event_id\":\"" + hexId() + "\",\"sent_at\":\"" + Instant.now() + "\"}\n"
				+ "{\"type\":\"event\",\"length\":" + event.getBytes(StandardCharsets.UTF_8).length + "}\n" + event
				+ "\n";
	}

	private List<Map<String, Object>> awaitIssues(int expected, String filter) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline)) {
			List<Map<String, Object>> issues = getIssues(filter);
			if (issues.size() >= expected) {
				return issues;
			}
			sleep();
		}
		return getIssues(filter);
	}

	private void awaitEventCount(long issueId, long expected) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline)) {
			Map<String, Object> issue = getJson("/api/internal/issues/" + issueId);
			if (((Number) issue.get("event_count")).longValue() >= expected) {
				return;
			}
			sleep();
		}
	}

	private List<Map<String, Object>> getIssues(String filter) {
		Map<String, Object> body = getJson("/api/internal/issues?project=" + projectId + filter);
		return cast(body.get("issues"));
	}

	@SuppressWarnings("unchecked")
	private <T> T cast(Object value) {
		return (T) value;
	}

	private Map<String, Object> getJson(String path) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, sessionCookie);
		ResponseEntity<Map> response = rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return cast(response.getBody());
	}

	private void patchIssue(long issueId, String status) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, sessionCookie);
		headers.set("Content-Type", "application/json");
		ResponseEntity<String> response = rest.exchange(url("/api/internal/issues/" + issueId), HttpMethod.PATCH,
				new HttpEntity<>("{\"status\":\"" + status + "\"}", headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

	private static String hexId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private static void sleep() {
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static byte[] gzip(byte[] data) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
				gz.write(data);
			}
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
