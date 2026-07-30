package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.outpost.TestcontainersConfiguration;
import dev.outpost.pipeline.ErrorPipeline;
import dev.outpost.pipeline.EventStore;
import dev.outpost.pipeline.ProcessedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
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
 * A redelivered event must not inflate the aggregates. SDKs retry on network
 * failure, so the same {@code event_id} arrives more than once; the second copy
 * stores no event row and must therefore leave {@code issue.event_count} and
 * {@code issue_env_stats.event_count} untouched.
 *
 * <p>The single-event path goes over HTTP. The batched path calls
 * {@link EventStore#store} directly: whether two envelopes land in the same
 * worker batch is a timing accident, and the invariant under test is not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
		"outpost.ingest.linger-millis=50" })
@Import(TestcontainersConfiguration.class)
class DuplicateEventIngestIntegrationTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper mapper;

	@Autowired
	ErrorPipeline pipeline;

	@Autowired
	EventStore store;

	@Autowired
	MeterRegistry registry;

	final RestTemplate rest = new RestTemplate();

	long projectId;
	String publicKey;
	String eventTimestamp;
	double baselineDuplicates;

	@BeforeEach
	void setUp() {
		baselineDuplicates = duplicates();
		// Fixed per test so a redelivery is byte-identical, and recent enough that
		// the skew clamp leaves it alone — a clamped timestamp would be rewritten to
		// each delivery's received-at and the two copies would stop being the same
		// event. Nanoseconds are deliberate: Postgres stores microseconds, so this
		// is the case where a naive in-memory key stops matching the stored one.
		eventTimestamp = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_789).toString();
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
	}

	@Test
	void redeliveredEnvelopeCountsOnce() {
		String envelope = eventEnvelope(hexId(), "prod", "TypeError");

		assertThat(postEnvelope(envelope).getStatusCode()).isEqualTo(HttpStatus.OK);
		awaitEvents(1);
		assertThat(postEnvelope(envelope).getStatusCode()).isEqualTo(HttpStatus.OK);
		// The redelivery stores nothing, so the counter is the only proof a worker
		// saw it. Without this the assertions below would also pass on an envelope
		// still sitting in the queue.
		awaitDuplicates(1);

		assertThat(eventRows()).isEqualTo(1);
		assertThat(issueEventCount()).isEqualTo(1);
		assertThat(envEventCount("prod")).isEqualTo(1);
	}

	@Test
	void batchWithOneNewAndOneDuplicateIncrementsByOne() {
		ProcessedEvent first = process(eventJson(hexId(), "prod", "TypeError"));
		store.store(List.of(first));
		assertThat(issueEventCount()).isEqualTo(1);

		ProcessedEvent second = process(eventJson(hexId(), "prod", "TypeError"));
		store.store(List.of(second, first));

		assertThat(eventRows()).isEqualTo(2);
		assertThat(issueEventCount()).isEqualTo(2);
		assertThat(envEventCount("prod")).isEqualTo(2);
		assertThat(duplicates() - baselineDuplicates).isEqualTo(1);
	}

	@Test
	void repeatedEventWithinASingleBatchCountsOnce() {
		ProcessedEvent event = process(eventJson(hexId(), "prod", "TypeError"));

		store.store(List.of(event, event));

		assertThat(eventRows()).isEqualTo(1);
		assertThat(issueEventCount()).isEqualTo(1);
		assertThat(envEventCount("prod")).isEqualTo(1);
		assertThat(duplicates() - baselineDuplicates).isEqualTo(1);
	}

	@Test
	void duplicateDoesNotResurrectAResolvedIssue() {
		ProcessedEvent event = process(eventJson(hexId(), "prod", "TypeError"));
		store.store(List.of(event));
		jdbc.sql("UPDATE issue SET status = 'resolved'").update();

		store.store(List.of(event));

		String status = jdbc.sql("SELECT status FROM issue").query(String.class).single();
		assertThat(status).isEqualTo("resolved");
		assertThat(issueEventCount()).isEqualTo(1);
	}

	// ------------------------------------------------------------------ helpers

	private ProcessedEvent process(String eventJson) {
		JsonNode event = mapper.readTree(eventJson);
		return pipeline.process(new IngestItem.ErrorEvent(projectId, Instant.now(), event, List.of()));
	}

	private ResponseEntity<String> postEnvelope(String envelope) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");
		return rest.exchange(url("/api/" + projectId + "/envelope/?sentry_key=" + publicKey), HttpMethod.POST,
				new HttpEntity<>(envelope.getBytes(StandardCharsets.UTF_8), headers), String.class);
	}

	private String eventEnvelope(String eventId, String environment, String type) {
		String event = eventJson(eventId, environment, type);
		return "{\"event_id\":\"" + eventId + "\"}\n" + "{\"type\":\"event\",\"length\":"
				+ event.getBytes(StandardCharsets.UTF_8).length + "}\n" + event + "\n";
	}

	private String eventJson(String eventId, String environment, String type) {
		return """
				{"event_id":"%s","timestamp":"%s","platform":"javascript",\
				"level":"error","environment":"%s",\
				"exception":{"values":[{"type":"%s","value":"boom","stacktrace":{"frames":[\
				{"filename":"http://localhost:4200/main.js","function":"handleClick","in_app":true,"lineno":13}\
				]}}]}}""".formatted(eventId, eventTimestamp, environment, type);
	}

	private long eventRows() {
		return jdbc.sql("SELECT count(*) FROM event").query(Long.class).single();
	}

	private long issueEventCount() {
		return jdbc.sql("SELECT coalesce(sum(event_count), 0) FROM issue").query(Long.class).single();
	}

	private long envEventCount(String environment) {
		return jdbc.sql("SELECT coalesce(sum(event_count), 0) FROM issue_env_stats WHERE environment = ?")
			.param(environment)
			.query(Long.class)
			.single();
	}

	private void awaitEvents(long expected) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline) && eventRows() < expected) {
			sleep(50);
		}
		assertThat(eventRows()).isEqualTo(expected);
	}

	private void awaitDuplicates(double expected) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline) && duplicates() < baselineDuplicates + expected) {
			sleep(50);
		}
		assertThat(duplicates() - baselineDuplicates).isEqualTo(expected);
	}

	/** The registry outlives a single test, so counts are read as a delta. */
	private double duplicates() {
		return registry.get("outpost.ingest.duplicates").counter().count();
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
