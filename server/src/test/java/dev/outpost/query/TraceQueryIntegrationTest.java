package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Phase 4 exit check (§12 P4, G3): a trace started in the browser
 * (pageload transaction + fetch span) continues into Spring Boot (request
 * transaction whose parent_span_id is the browser fetch span) with an error and
 * a log on the same trace_id. {@code GET /traces/{id}} returns all four signals
 * in one cross-project payload; {@code GET /traces} search honors the common
 * filters, duration range, "has errors", and keyset cursor.
 *
 * <p>Interim, like P1–P3: SDK-<b>shaped</b> transaction envelopes, both
 * families; swap for real demo apps once they exist.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
		"outpost.ingest.linger-millis=50" })
@Import(TestcontainersConfiguration.class)
class TraceQueryIntegrationTest {

	private static final String TRACE_ID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
	private static final String BROWSER_SPAN = "aaaa000000000001"; // pageload root span
	private static final String FETCH_SPAN = "aaaa000000000002"; // browser fetch → backend parent
	private static final String BACKEND_SPAN = "bbbb000000000001"; // backend request root span

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	final RestTemplate rest = new RestTemplate();

	long frontendProject;
	long backendProject;
	String frontendKey;
	String backendKey;
	String sessionCookie;

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM span").update();
		jdbc.sql("DELETE FROM txn").update();
		jdbc.sql("DELETE FROM log_record").update();
		jdbc.sql("DELETE FROM event").update();
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM project").update();
		frontendProject = jdbc.sql("INSERT INTO project (slug, name) VALUES ('shop-frontend', 'Shop FE') RETURNING id")
			.query(Long.class)
			.single();
		backendProject = jdbc.sql("INSERT INTO project (slug, name) VALUES ('shop-backend', 'Shop BE') RETURNING id")
			.query(Long.class)
			.single();
		frontendKey = "0123456789abcdef0123456789abcdef";
		backendKey = "fedcba9876543210fedcba9876543210";
		jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, ?)")
			.param(frontendProject)
			.param(frontendKey)
			.update();
		jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, ?)")
			.param(backendProject)
			.param(backendKey)
			.update();
		sessionCookie = login();
	}

	@Test
	void traceWaterfallSpansBothServicesWithErrorsAndLogs() {
		// Browser pageload transaction (frontend project) with a child fetch span.
		postEnvelope(frontendProject, frontendKey, browserTransactionEnvelope());
		// Backend request transaction (backend project) continuing the browser fetch span.
		postEnvelope(backendProject, backendKey, backendTransactionEnvelope());
		// An error and a log on the same trace, from the backend.
		postEnvelope(backendProject, backendKey, errorEnvelope());
		postEnvelope(backendProject, backendKey, logEnvelope());

		Map<String, Object> trace = awaitTrace(2);

		// Two transactions across two projects.
		List<Map<String, Object>> transactions = cast(trace.get("transactions"));
		assertThat(transactions).hasSize(2);
		assertThat(transactions).extracting(t -> t.get("project_id"))
			.containsExactlyInAnyOrder((int) frontendProject, (int) backendProject);
		// The backend txn continues the browser fetch span (cross-service nesting).
		assertThat(transactions).anySatisfy(t -> {
			if (BACKEND_SPAN.equals(t.get("span_id"))) {
				assertThat(t.get("parent_span_id")).isEqualTo(FETCH_SPAN);
			}
		});

		// Spans: the browser fetch span + a backend JDBC span.
		List<Map<String, Object>> spans = cast(trace.get("spans"));
		assertThat(spans).extracting(s -> s.get("op")).contains("http.client", "db.sql.query");
		assertThat(spans).anySatisfy(s -> assertThat(s.get("span_id")).isEqualTo(FETCH_SPAN));

		// Error and log pinned to the trace.
		List<Map<String, Object>> errors = cast(trace.get("errors"));
		assertThat(errors).hasSize(1);
		assertThat(errors.get(0).get("exception_type")).isEqualTo("IllegalStateException");
		List<Map<String, Object>> logs = cast(trace.get("logs"));
		assertThat(logs).hasSize(1);
		assertThat(logs.get(0).get("body")).isEqualTo("handling checkout");
	}

	@Test
	void traceSearchHonorsFilters() {
		postEnvelope(frontendProject, frontendKey, browserTransactionEnvelope());
		postEnvelope(backendProject, backendKey, backendTransactionEnvelope());
		postEnvelope(backendProject, backendKey, errorEnvelope());
		awaitTrace(2);

		// One distributed trace, even though it has two transactions across two
		// services. It is represented by its root (browser pageload).
		List<Map<String, Object>> all = searchTraces("");
		assertThat(all).hasSize(1);
		assertThat(all.get(0).get("name")).isEqualTo("/checkout");
		// Counts span the whole trace, not just the representative transaction.
		assertThat((int) all.get(0).get("span_count")).isEqualTo(2);
		assertThat((int) all.get(0).get("error_count")).isEqualTo(1);

		// The trace is reachable by filtering on EITHER project — the backend
		// transaction is a continuation (non-root), so this is the property that a
		// naive "roots only" query would break.
		assertThat(searchTraces("&project=" + frontendProject)).hasSize(1);
		List<Map<String, Object>> viaBackend = searchTraces("&project=" + backendProject);
		assertThat(viaBackend).hasSize(1);
		assertThat(viaBackend.get(0).get("name")).isEqualTo("GET /api/checkout");

		// Root-name substring search (matches the browser root).
		assertThat(searchTraces("&query=checkout")).hasSize(1);

		// has_errors: the trace has an error event.
		assertThat(searchTraces("&has_errors=true")).hasSize(1);

		// Duration range filters per transaction, then dedupes to the trace: only the
		// browser txn is ≥300ms; only the backend txn is ≤300ms.
		assertThat(searchTraces("&min_duration=300")).extracting(t -> t.get("name")).containsExactly("/checkout");
		assertThat(searchTraces("&max_duration=300")).extracting(t -> t.get("name")).containsExactly("GET /api/checkout");
	}

	@Test
	void unknownTraceIs404() {
		ResponseEntity<Map> response = getEntity("/api/internal/traces/deadbeefdeadbeefdeadbeefdeadbeef");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void tracesRequireSession() {
		ResponseEntity<String> unauthorized = rest.getForEntity(url("/api/internal/traces"), String.class);
		assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ------------------------------------------------------------------ payloads

	/** Browser pageload transaction with a child fetch span (the cross-service seam). */
	private String browserTransactionEnvelope() {
		Instant start = Instant.now().minusMillis(500);
		Instant end = Instant.now();
		double s = start.toEpochMilli() / 1000.0;
		double e = end.toEpochMilli() / 1000.0;
		double fetchStart = start.plusMillis(50).toEpochMilli() / 1000.0;
		String txn = """
				{"type":"transaction","transaction":"/checkout","platform":"javascript","environment":"prod",\
				"release":"shop-frontend@1.0.0","start_timestamp":%s,"timestamp":%s,\
				"contexts":{"trace":{"trace_id":"%s","span_id":"%s","op":"pageload","status":"ok"}},\
				"spans":[{"trace_id":"%s","span_id":"%s","parent_span_id":"%s","op":"http.client",\
				"description":"GET /api/checkout","start_timestamp":%s,"timestamp":%s,"status":"ok"}]}"""
			.formatted(s, e, TRACE_ID, BROWSER_SPAN, TRACE_ID, FETCH_SPAN, BROWSER_SPAN, fetchStart, e);
		return envelope("transaction", txn);
	}

	/** Backend request transaction continuing the browser fetch span, with a JDBC child span. */
	private String backendTransactionEnvelope() {
		Instant start = Instant.now().minusMillis(400);
		Instant end = start.plusMillis(120);
		double s = start.toEpochMilli() / 1000.0;
		double e = end.toEpochMilli() / 1000.0;
		double dbStart = start.plusMillis(20).toEpochMilli() / 1000.0;
		double dbEnd = start.plusMillis(90).toEpochMilli() / 1000.0;
		String txn = """
				{"type":"transaction","transaction":"GET /api/checkout","platform":"java","environment":"prod",\
				"release":"shop-backend@1.0.0","start_timestamp":%s,"timestamp":%s,\
				"contexts":{"trace":{"trace_id":"%s","span_id":"%s","parent_span_id":"%s","op":"http.server",\
				"status":"ok"}},\
				"spans":[{"trace_id":"%s","span_id":"bbbb000000000002","parent_span_id":"%s","op":"db.sql.query",\
				"description":"SELECT * FROM orders WHERE id = ?","start_timestamp":%s,"timestamp":%s,"status":"ok"}]}"""
			.formatted(s, e, TRACE_ID, BACKEND_SPAN, FETCH_SPAN, TRACE_ID, BACKEND_SPAN, dbStart, dbEnd);
		return envelope("transaction", txn);
	}

	/** Backend error event on the same trace. */
	private String errorEnvelope() {
		String event = """
				{"event_id":"%s","timestamp":"%s","platform":"java","level":"error","environment":"prod",\
				"release":"shop-backend@1.0.0",\
				"contexts":{"trace":{"trace_id":"%s","span_id":"%s"}},\
				"exception":{"values":[{"type":"IllegalStateException","value":"order 4711 has no customer",\
				"module":"java.lang","stacktrace":{"frames":[\
				{"module":"dev.demo.OrderService","function":"loadCustomer","in_app":true,"lineno":88}]}}]}}"""
			.formatted(hexId(), Instant.now(), TRACE_ID, BACKEND_SPAN);
		return envelope("event", event);
	}

	/** Backend log record on the same trace. */
	private String logEnvelope() {
		double now = System.currentTimeMillis() / 1000.0;
		String payload = """
				{"items":[{"timestamp":%s,"trace_id":"%s","span_id":"%s","level":"info","severity_number":9,\
				"body":"handling checkout","attributes":{"sentry.environment":{"value":"prod","type":"string"},\
				"sentry.release":{"value":"shop-backend@1.0.0","type":"string"}}}]}""".formatted(now, TRACE_ID,
				BACKEND_SPAN);
		return "{\"sent_at\":\"" + Instant.now() + "\"}\n"
				+ "{\"type\":\"log\",\"item_count\":1,\"content_type\":\"application/vnd.sentry.items.log+json\",\"length\":"
				+ payload.getBytes(StandardCharsets.UTF_8).length + "}\n" + payload + "\n";
	}

	private String envelope(String type, String payload) {
		return "{\"sent_at\":\"" + Instant.now() + "\"}\n" + "{\"type\":\"" + type + "\",\"length\":"
				+ payload.getBytes(StandardCharsets.UTF_8).length + "}\n" + payload + "\n";
	}

	// ------------------------------------------------------------------ helpers

	private void postEnvelope(long projectId, String key, String envelope) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");
		ResponseEntity<String> response = rest.exchange(url("/api/" + projectId + "/envelope/?sentry_key=" + key),
				HttpMethod.POST, new HttpEntity<>(envelope.getBytes(StandardCharsets.UTF_8), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	private Map<String, Object> awaitTrace(int expectedTransactions) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline)) {
			ResponseEntity<Map> response = getEntity("/api/internal/traces/" + TRACE_ID);
			if (response.getStatusCode() == HttpStatus.OK) {
				Map<String, Object> body = cast(response.getBody());
				if (((List<?>) body.get("transactions")).size() >= expectedTransactions) {
					return body;
				}
			}
			sleep();
		}
		return cast(getEntity("/api/internal/traces/" + TRACE_ID).getBody());
	}

	private List<Map<String, Object>> searchTraces(String filter) {
		Map<String, Object> body = getJson("/api/internal/traces?" + filter.replaceFirst("^&", ""));
		return cast(body.get("traces"));
	}

	private void sleep() {
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private String hexId() {
		return java.util.UUID.randomUUID().toString().replace("-", "");
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
		ResponseEntity<Map> response = getEntity(path);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return cast(response.getBody());
	}

	private ResponseEntity<Map> getEntity(String path) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, sessionCookie);
		return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
	}

	@SuppressWarnings("unchecked")
	private <T> T cast(Object value) {
		return (T) value;
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}
}
