package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.web.util.UriComponentsBuilder;

/**
 * What an attribute equality filter matches (ADR-0018): the filter text against
 * the value an SDK sent, whatever JSON type it arrived as, with the same answer
 * from the history query and the live tail. Records are ingested through a real
 * envelope rather than inserted directly, since the type is what the pipeline's
 * flattening produces, not what a hand-written jsonb literal would assume.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
		"outpost.ingest.linger-millis=50" })
@Import(TestcontainersConfiguration.class)
class LogAttributeFilterIntegrationTest {

	private static final String PUBLIC_KEY = "0123456789abcdef0123456789abcdef";

	/**
	 * Four records whose attributes disagree about type. Each body names the record so
	 * a case can say which ones it expects back.
	 */
	private static final List<String> RECORDS = List.of(record("integer", """
			"amount":{"value":3,"type":"integer"},"enabled":{"value":true,"type":"boolean"},\
			"user.id":{"value":"42","type":"string"},"http.route":{"value":"/health","type":"string"}"""),
			record("double", """
					"amount":{"value":3.0,"type":"double"},"enabled":{"value":"true","type":"string"},\
					"user.id":{"value":42,"type":"integer"},"ctx":{"value":{"a":1},"type":"object"}"""),
			record("string", """
					"amount":{"value":"3","type":"string"},"enabled":{"value":"True","type":"string"},\
					"tags":{"value":["x"],"type":"array"},"nothing":{"value":null,"type":"string"}"""),
			record("other number", """
					"amount":{"value":30,"type":"integer"},"enabled":{"value":false,"type":"boolean"},\
					"user.id":{"value":"042","type":"string"}"""));

	private static final Pattern BODY = Pattern.compile("\"body\":\"([^\"]*)\"");

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	final RestTemplate rest = new RestTemplate();

	long projectId;

	String sessionCookie;

	/** A filter set and the bodies of the records it must return. */
	record Case(List<String> attr, List<String> bodies) {

		@Override
		public String toString() {
			return String.join(" & ", attr);
		}
	}

	static List<Case> cases() {
		return List.of(new Case(List.of("amount=3"), List.of("integer", "double", "string")),
				new Case(List.of("enabled=true"), List.of("integer", "double")),
				new Case(List.of("user.id=42"), List.of("integer", "double")),
				new Case(List.of("amount=03"), List.of()), new Case(List.of("http.route=/health"), List.of("integer")),
				new Case(List.of("ctx={\"a\":1}"), List.of()), new Case(List.of("ctx={\"a\": 1}"), List.of()),
				new Case(List.of("tags=[\"x\"]"), List.of()), new Case(List.of("nothing=null"), List.of()),
				// Beyond what Postgres numeric/BigDecimal can hold, so these never match as numbers.
				new Case(List.of("amount=1e200000"), List.of()), new Case(List.of("amount=1e9999999999"), List.of()),
				new Case(List.of("amount=3", "enabled=true"), List.of("integer", "double")),
				new Case(List.of("amount=3", "enabled=true", "user.id=42"), List.of("integer", "double")));
	}

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM log_record").update();
		jdbc.sql("DELETE FROM project").update();
		projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES ('demo', 'Demo') RETURNING id")
			.query(Long.class)
			.single();
		jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, ?)")
			.param(projectId)
			.param(PUBLIC_KEY)
			.update();
		sessionCookie = login();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("cases")
	void theHistoryQueryMatchesByText(Case filter) {
		ingest();

		assertThat(bodies(filter.attr())).containsExactlyInAnyOrderElementsOf(filter.bodies());
	}

	/** The chart counts the rows the list returns, so it has to apply the same rule. */
	@Test
	void theTimelineCountsWhatTheListReturns() {
		ingest();

		Map<String, Object> timeline = get(uri("/api/internal/logs/timeline", List.of("amount=3"))
			.queryParam("from", Instant.now().minus(Duration.ofHours(1))));
		List<Map<String, Map<String, Number>>> buckets = cast(timeline.get("buckets"));
		long counted = buckets.stream()
			.flatMap(bucket -> bucket.get("counts").values().stream())
			.mapToLong(Number::longValue)
			.sum();
		assertThat(counted).isEqualTo(3);
	}

	/**
	 * Runs every case at once, each against its own tail, because a record streamed
	 * now and the same record read back later must be matched by the same rule (#132).
	 */
	@Test
	void theLiveTailMatchesWhatTheHistoryQueryReturns() throws Exception {
		List<Case> cases = cases();
		List<List<String>> streamed = cases.stream().map(c -> (List<String>) new CopyOnWriteArrayList<String>()).toList();
		HttpClient client = HttpClient.newHttpClient();
		for (int i = 0; i < cases.size(); i++) {
			tail(client, cases.get(i).attr(), streamed.get(i));
		}

		ingest();

		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline) && !allArrived(cases, streamed)) {
			Thread.sleep(50);
		}
		// A last beat for a record the tail should not have sent, which would arrive with the rest.
		Thread.sleep(500);
		for (int i = 0; i < cases.size(); i++) {
			assertThat(streamed.get(i)).as("streamed under %s", cases.get(i))
				.containsExactlyInAnyOrderElementsOf(bodies(cases.get(i).attr()));
		}
	}

	// ------------------------------------------------------------------ helpers

	private static boolean allArrived(List<Case> cases, List<List<String>> streamed) {
		for (int i = 0; i < cases.size(); i++) {
			if (streamed.get(i).size() < cases.get(i).bodies().size()) {
				return false;
			}
		}
		return true;
	}

	/** Opens a live tail under the filters and collects the body of every record it streams. */
	private void tail(HttpClient client, List<String> attr, List<String> into) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(uri("/api/internal/logs", attr).queryParam("live", "true").build().encode().toUri())
			.header("Cookie", sessionCookie)
			.header("Accept", "text/event-stream")
			.build();
		HttpResponse<InputStream> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
			.get(10, TimeUnit.SECONDS);
		assertThat(response.statusCode()).isEqualTo(200);
		CompletableFuture<Void> subscribed = new CompletableFuture<>();
		Thread.ofVirtual().start(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					subscribed.complete(null);
					Matcher body = BODY.matcher(line);
					if (line.startsWith("data:") && body.find()) {
						into.add(body.group(1));
					}
				}
			}
			catch (Exception e) {
				subscribed.completeExceptionally(e);
			}
		});
		// Waits for the tail's "connected" comment so the subscription is registered before ingest.
		subscribed.get(10, TimeUnit.SECONDS);
	}

	/** One Log Record whose body names it, carrying the given attribute JSON members. */
	private static String record(String body, String attributes) {
		return """
				{"timestamp":%%s,"level":"info","body":"%s","attributes":{%s}}""".formatted(body, attributes);
	}

	/** Posts {@link #RECORDS} in one envelope and waits until all of them are queryable. */
	private void ingest() {
		double now = System.currentTimeMillis() / 1000.0;
		String payload = "{\"items\":[" + String.join(",", RECORDS.stream().map(r -> r.formatted(now)).toList())
				+ "]}";
		String envelope = "{\"sent_at\":\"" + Instant.now() + "\"}\n"
				+ "{\"type\":\"log\",\"item_count\":" + RECORDS.size()
				+ ",\"content_type\":\"application/vnd.sentry.items.log+json\",\"length\":"
				+ payload.getBytes(StandardCharsets.UTF_8).length + "}\n" + payload + "\n";
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");
		ResponseEntity<String> response = rest.exchange(
				url("/api/" + projectId + "/envelope/?sentry_key=" + PUBLIC_KEY), HttpMethod.POST,
				new HttpEntity<>(envelope.getBytes(StandardCharsets.UTF_8), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (bodies(List.of()).size() < RECORDS.size() && Instant.now().isBefore(deadline)) {
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		assertThat(bodies(List.of())).hasSize(RECORDS.size());
	}

	/** The bodies of the records {@code GET /logs} returns under the given attribute filters. */
	private List<String> bodies(List<String> attr) {
		List<Map<String, Object>> logs = cast(get(uri("/api/internal/logs", attr)).get("logs"));
		return logs.stream().map(row -> (String) row.get("body")).toList();
	}

	private UriComponentsBuilder uri(String path, List<String> attr) {
		UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(url(path)).queryParam("project", projectId);
		attr.forEach(filter -> uri.queryParam("attr", filter));
		return uri;
	}

	private Map<String, Object> get(UriComponentsBuilder uri) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, sessionCookie);
		ResponseEntity<Map> response = rest.exchange(uri.build().encode().toUri(), HttpMethod.GET,
				new HttpEntity<>(headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return cast(response.getBody());
	}

	private String login() {
		ResponseEntity<Map> response = rest.postForEntity(url("/api/internal/auth/login"),
				Map.of("email", "admin@test.local", "password", "test-password"), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).isNotNull();
		return setCookie.split(";")[0];
	}

	@SuppressWarnings("unchecked")
	private <T> T cast(Object value) {
		return (T) value;
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}
}
