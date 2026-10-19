package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.support.EnvelopeFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
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
 * Span streaming (JS SDK v11's default): v2 {@code span} items arrive in several
 * envelopes, children before their segment, and come back out of the trace API as
 * one transaction with its spans — the same shape a {@code transaction} item gives.
 */
// One worker, so envelopes are digested in the order they were posted.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
		"outpost.ingest.linger-millis=50", "outpost.ingest.workers=1" })
@Import(TestcontainersConfiguration.class)
class StreamedSpanIngestIntegrationTest {

	private static final String TRACE_ID = "c1b2c3d4e5f60718293a4b5c6d7e8f90";
	private static final String PAGELOAD = "aaaa000000000001";
	private static final String FETCH = "aaaa000000000002";
	private static final String RENDER = "aaaa000000000003";
	private static final String INP = "aaaa000000000004";

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
		jdbc.sql("DELETE FROM span").update();
		jdbc.sql("DELETE FROM txn").update();
		jdbc.sql("DELETE FROM event").update();
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM project").update();
		projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES ('shop-frontend', 'Shop FE') RETURNING id")
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
	void childrenArrivingBeforeTheirSegmentJoinItInTheTrace() {
		Instant start = Instant.now().minusSeconds(5);
		// Batch 1: two finished children, segment still open.
		postEnvelope(EnvelopeFactory.streamedSpanEnvelope(TRACE_ID, 2,
				child(FETCH, "GET api.example.com", "http.client", start.plusMillis(20), start.plusMillis(90)) + ","
						+ child(RENDER, "ProductDetail", "ui.mount", start.plusMillis(100), start.plusMillis(110))));
		await(() -> count("span") == 2);
		assertThat(count("txn")).isZero();

		// Batch 2: the pageload segment, plus an INP span sent as its own segment after it.
		postEnvelope(EnvelopeFactory.streamedSpanEnvelope(TRACE_ID, 2, EnvelopeFactory.streamedSpan(TRACE_ID,
				PAGELOAD, null, PAGELOAD, "/products/:id", "pageload", true, start, start.plusMillis(120), "prod")
				+ "," + EnvelopeFactory.streamedSpan(TRACE_ID, INP, null, INP, "Click", "ui.interaction.click", true,
						start.plusMillis(2000), start.plusMillis(2200), "prod")));
		await(() -> count("txn") == 1 && count("span") == 3);

		Map<String, Object> trace = getJson("/api/internal/traces/" + TRACE_ID);
		List<Map<String, Object>> transactions = cast(trace.get("transactions"));
		assertThat(transactions).hasSize(1);
		Map<String, Object> txn = transactions.getFirst();
		assertThat(txn.get("span_id")).isEqualTo(PAGELOAD);
		assertThat(txn.get("name")).isEqualTo("/products/:id");
		assertThat(txn.get("op")).isEqualTo("pageload");
		assertThat(txn.get("environment")).isEqualTo("prod");
		assertThat(txn.get("release")).isEqualTo("bench@1.0.0");
		assertThat((Double) txn.get("duration_ms")).isEqualTo(120.0);

		List<Map<String, Object>> spans = cast(trace.get("spans"));
		assertThat(spans).extracting(span -> span.get("span_id")).containsExactlyInAnyOrder(FETCH, RENDER, INP);
		assertThat(spans).filteredOn(span -> !INP.equals(span.get("span_id")))
			.allSatisfy(span -> {
				assertThat(span.get("txn_id")).isEqualTo(txn.get("id"));
				assertThat(span.get("parent_span_id")).isEqualTo(PAGELOAD);
			});
		Map<String, Object> fetch = spans.stream().filter(span -> FETCH.equals(span.get("span_id"))).findFirst()
			.orElseThrow();
		assertThat(fetch.get("op")).isEqualTo("http.client");
		assertThat(fetch.get("description")).isEqualTo("GET api.example.com");
		Map<String, Object> fetchRaw = cast(fetch.get("data"));
		Map<String, Object> fetchData = cast(fetchRaw.get("data"));
		assertThat(fetchData.get("sentry.sdk.version")).isEqualTo("11.8.0");

		// Only the pageload is a trace; the INP span stays out of the list.
		List<Map<String, Object>> traces = cast(getJson("/api/internal/traces?project=" + projectId).get("traces"));
		assertThat(traces).hasSize(1);
		assertThat(traces.getFirst().get("op")).isEqualTo("pageload");
		assertThat(traces.getFirst().get("span_count")).isEqualTo(3);

		assertThat(jdbc.sql("SELECT name FROM environment WHERE project_id = ?").param(projectId).query(String.class)
			.list()).containsExactly("prod");
	}

	@Test
	void redeliveredEnvelopeIsNotStoredTwice() {
		String envelope = new EnvelopeFactory().streamedSpans("prod", 3);

		postEnvelope(envelope);
		await(() -> count("txn") == 1 && count("span") == 3);
		postEnvelope(envelope);
		// A third envelope proves the redelivery has been drained before counting.
		postEnvelope(new EnvelopeFactory().streamedSpans("prod", 0));
		await(() -> count("txn") == 2);

		assertThat(count("txn")).isEqualTo(2);
		assertThat(count("span")).isEqualTo(3);
	}

	@Test
	void v1SingleSpanItemIsAcceptedButNotStored() {
		String span = """
				{"trace_id":"%s","span_id":"%s","op":"ui.interaction.click","description":"body > button",\
				"start_timestamp":%s,"timestamp":%s,"is_segment":true,"data":{}}"""
			.formatted(TRACE_ID, INP, Instant.now().getEpochSecond(), Instant.now().getEpochSecond());
		postEnvelope("{\"sent_at\":\"" + Instant.now() + "\"}\n{\"type\":\"span\"}\n" + span + "\n");
		// Follow with a v2 envelope so the v1 one has certainly been processed.
		postEnvelope(new EnvelopeFactory().streamedSpans("prod", 0));
		await(() -> count("txn") == 1);

		assertThat(count("span")).isZero();
	}

	// ------------------------------------------------------------------ helpers

	private static String child(String spanId, String name, String op, Instant start, Instant end) {
		return EnvelopeFactory.streamedSpan(TRACE_ID, spanId, PAGELOAD, PAGELOAD, name, op, false, start, end,
				"prod");
	}

	private long count(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
	}

	private void await(BooleanSupplier condition) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (!condition.getAsBoolean()) {
			assertThat(Instant.now()).as("condition not met within 10 s").isBefore(deadline);
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
	}

	private void postEnvelope(String envelope) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "text/plain;charset=UTF-8");
		ResponseEntity<String> response = rest.exchange(
				url("/api/" + projectId + "/envelope/?sentry_version=7&sentry_key=" + publicKey), HttpMethod.POST,
				new HttpEntity<>(envelope.getBytes(StandardCharsets.UTF_8), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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
