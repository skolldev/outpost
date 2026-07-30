package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.support.EnvelopeFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Guards the only load-shedding mechanism ingest has: when the bounded buffer
 * fills, the endpoint must 429 with the headers Sentry SDKs actually read, and
 * the buffer must be observable while it happens.
 *
 * <p>Deterministic by construction rather than by timing — {@code
 * outpost.ingest.workers=0} makes {@code IngestWorkers.start()} spawn no threads,
 * so nothing drains and the queue holds exactly what was posted. That keeps this
 * in the spirit of {@code TraceSearchPerformanceTest}: a perf-adjacent assertion
 * with no wall clock in it, so a loaded CI box cannot flake it. The throughput
 * question — how many envelopes per second before this fires — is answered by the
 * opt-in benchmark in {@code dev.outpost.bench}, which is excluded from CI.
 *
 * <p>Note this property set is unique across the suite, so it gets its own Spring
 * context and Postgres container. That is the cost of holding the queue still.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
				"outpost.ingest.workers=0", "outpost.ingest.queue-capacity=" + IngestBackpressureIntegrationTest.CAPACITY,
				"outpost.ingest.shutdown-timeout=10ms",
				"outpost.ingest.spool-directory=build/test-spool/backpressure" })
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class IngestBackpressureIntegrationTest {

	static final int CAPACITY = 5;

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IngestQueue queue;

	@Autowired
	IngestWorkers workers;

	@Autowired
	MeterRegistry meters;

	@Autowired
	ClientReportCounters clientReports;

	final RestTemplate rest = new RestTemplate();

	final EnvelopeFactory envelopes = new EnvelopeFactory();

	long projectId;

	String publicKey;

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM event").update();
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM project").update();
		projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES ('bench', 'Bench') RETURNING id")
			.query(Long.class)
			.single();
		publicKey = "0123456789abcdef0123456789abcdef";
		jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, ?)")
			.param(projectId)
			.param(publicKey)
			.update();
		drainQueue();
	}

	@AfterEach
	void tearDown() {
		drainQueue();
	}

	@Test
	void acceptsUpToCapacityThenRejectsWithSdkBackoffHeaders() {
		List<ResponseEntity<String>> accepted = new ArrayList<>();
		for (int i = 0; i < CAPACITY; i++) {
			accepted.add(post(envelopes.error("prod")));
		}
		assertThat(accepted).allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK));

		ResponseEntity<String> rejected = post(envelopes.error("prod"));
		assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(rejected.getBody()).contains("ingest buffer full");
		// The two headers the SDKs honour. Retry-After is the generic fallback;
		// X-Sentry-Rate-Limits is what sentry SDKs parse to suppress sends.
		assertThat(rejected.getHeaders().getFirst("Retry-After")).isEqualTo("30");
		assertThat(rejected.getHeaders().getFirst("X-Sentry-Rate-Limits")).isEqualTo("30:all:organization");
	}

	@Test
	void queueDepthIsObservableWhileTheBufferFills() {
		assertThat(gauge("outpost.ingest.queue.capacity")).isEqualTo(CAPACITY);
		assertThat(gauge("outpost.ingest.queue.depth")).isZero();

		post(envelopes.error("prod"));
		post(envelopes.error("prod"));

		assertThat(queue.size()).isEqualTo(2);
		assertThat(gauge("outpost.ingest.queue.depth")).isEqualTo(2);
	}

	@Test
	void everyEnvelopeOutcomeIsCounted() {
		// Counters are cumulative and the context is shared across tests in this
		// class, so every assertion here is a delta.
		double acceptedBefore = envelopeCount("accepted");
		double rejectedBefore = envelopeCount("rejected");
		double forbiddenBefore = envelopeCount("forbidden");
		double malformedBefore = envelopeCount("malformed");
		double itemsRejectedBefore = itemCount("error", "rejected");

		for (int i = 0; i < CAPACITY; i++) {
			post(envelopes.error("prod"));
		}
		post(envelopes.error("prod"));
		postRaw(envelopes.error("prod").getBytes(StandardCharsets.UTF_8), "ffffffffffffffffffffffffffffffff");
		postRaw("not json\n{}".getBytes(StandardCharsets.UTF_8), publicKey);

		assertThat(envelopeCount("accepted") - acceptedBefore).isEqualTo(CAPACITY);
		assertThat(envelopeCount("rejected") - rejectedBefore).isEqualTo(1);
		assertThat(envelopeCount("forbidden") - forbiddenBefore).isEqualTo(1);
		assertThat(envelopeCount("malformed") - malformedBefore).isEqualTo(1);
		// Items carry the signal tag the envelope counter lacks.
		assertThat(itemCount("error", "rejected") - itemsRejectedBefore).isEqualTo(1);
	}

	@Test
	void shutdownTimeoutLogsTheNumberOfOutstandingItems(CapturedOutput output) throws IOException {
		assertThat(post(envelopes.error("prod")).getStatusCode()).isEqualTo(HttpStatus.OK);

		workers.stop();

		assertThat(output).contains("ingest shutdown timed out with 1 item still queued or in flight");
		assertThat(SpoolTestFiles.count(Path.of("build/test-spool/backpressure"))).isZero();
	}

	@Test
	void rejectedEnvelopeStillRecordsItsClientReport() {
		for (int i = 0; i < CAPACITY; i++) {
			post(envelopes.error("prod"));
		}
		String key = projectId + ":queue_overflow:error";
		long before = clientReports.snapshot().getOrDefault(key, 0L);
		String report = """
				{}
				{"type":"client_report"}
				{"discarded_events":[{"reason":"queue_overflow","category":"error","quantity":3}]}
				{"type":"event"}
				{"event_id":"0123456789abcdef0123456789abcdef"}
				""";

		ResponseEntity<String> response = post(report);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(clientReports.snapshot().getOrDefault(key, 0L) - before).isEqualTo(3);
	}

	@Test
	void clientReportOnlyEnvelopeDoesNotUseTheQueue() {
		String key = projectId + ":queue_overflow:error";
		long before = clientReports.snapshot().getOrDefault(key, 0L);
		String report = """
				{}
				{"type":"client_report"}
				{"discarded_events":[{"reason":"queue_overflow","category":"error","quantity":3}]}
				""";

		ResponseEntity<String> response = post(report);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(queue.size()).isZero();
		assertThat(clientReports.snapshot().getOrDefault(key, 0L) - before).isEqualTo(3);
	}

	@Test
	void ignoredEnvelopeDoesNotUseTheQueue() {
		String session = """
				{}
				{"type":"session"}
				{}
				""";

		ResponseEntity<String> response = post(session);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(queue.size()).isZero();
	}

	// ------------------------------------------------------------------ helpers

	private ResponseEntity<String> post(String envelope) {
		return postRaw(envelope.getBytes(StandardCharsets.UTF_8), publicKey);
	}

	private ResponseEntity<String> postRaw(byte[] body, String key) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");
		return rest.exchange("http://localhost:" + port + "/api/" + projectId + "/envelope/?sentry_key=" + key,
				HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
	}

	private double gauge(String name) {
		return meters.get(name).gauge().value();
	}

	private double envelopeCount(String outcome) {
		return meters.get("outpost.ingest.envelopes").tag("outcome", outcome).counter().count();
	}

	private double itemCount(String signal, String outcome) {
		return meters.get("outpost.ingest.items").tag("signal", signal).tag("outcome", outcome).counter().count();
	}

	/** No workers run, so the queue survives between tests in the shared context. */
	private void drainQueue() {
		try {
			List<QueuedEnvelope> batch;
			while (!(batch = queue.nextBatch(CAPACITY, 0)).isEmpty()) {
				for (QueuedEnvelope envelope : batch) {
					try {
						Files.deleteIfExists(envelope.spoolFile().path());
					}
					catch (java.io.IOException e) {
						throw new AssertionError(e);
					}
				}
				queue.completed(batch.size());
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
