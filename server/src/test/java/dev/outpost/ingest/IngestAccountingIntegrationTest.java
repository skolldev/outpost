package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.support.EnvelopeFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Guards {@link IngestQueue#outstanding()}: every path that removes work must
 * decrement it, or {@code IngestWorkers.stop()} reports phantom residual work at
 * shutdown. One test per outcome the ingest endpoint can produce, each ending at
 * zero, driven directly through {@link IngestWorkers} for determinism.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
				"outpost.ingest.workers=1", "outpost.ingest.linger-millis=10",
				"outpost.ingest.queue-capacity=" + IngestAccountingIntegrationTest.CAPACITY,
				"outpost.ingest.shutdown-timeout=5s",
				"outpost.ingest.spool-directory=build/test-spool/accounting" })
@Import(TestcontainersConfiguration.class)
class IngestAccountingIntegrationTest {

	static final int CAPACITY = 5;

	private static final Path SPOOL_DIRECTORY = Path.of("build/test-spool/accounting");

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IngestQueue queue;

	@Autowired
	IngestWorkers workers;

	final RestTemplate rest = new RestTemplate();

	final EnvelopeFactory envelopes = new EnvelopeFactory();

	long projectId;

	String publicKey;

	@BeforeEach
	void setUp() throws IOException {
		rest.setErrorHandler(new NoOpResponseErrorHandler());
		// Stop first: draining avoids racing a worker against the project deleted below.
		workers.stop();
		SpoolTestFiles.clear(SPOOL_DIRECTORY);
		jdbc.sql("DELETE FROM event").update();
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM project").update();
		projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES ('accounting', 'Accounting') RETURNING id")
			.query(Long.class)
			.single();
		publicKey = "0123456789abcdef0123456789abcdef";
		jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, ?)")
			.param(projectId)
			.param(publicKey)
			.update();
		workers.start();
		assertThat(queue.outstanding()).isZero();
	}

	@Test
	void acceptedEnvelopesReturnAccountingToZero() throws IOException {
		for (int i = 0; i < CAPACITY; i++) {
			assertThat(post(envelopes.error("prod")).getStatusCode()).isEqualTo(HttpStatus.OK);
		}

		awaitNoOutstandingWork();
		awaitEmptySpool();
	}

	@Test
	void rejectedEnvelopeDoesNotLeakOutstandingWork() throws IOException {
		workers.stop();
		for (int i = 0; i < CAPACITY; i++) {
			assertThat(post(envelopes.error("prod")).getStatusCode()).isEqualTo(HttpStatus.OK);
		}
		assertThat(queue.outstanding()).isEqualTo(CAPACITY);

		ResponseEntity<String> rejected = post(envelopes.error("prod"));

		assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		// The rejected envelope's own increment must roll back, not just cancel against an accepted one.
		assertThat(queue.outstanding()).isEqualTo(CAPACITY);
		assertThat(SpoolTestFiles.count(SPOOL_DIRECTORY)).isEqualTo(CAPACITY);

		workers.start();

		awaitNoOutstandingWork();
		awaitEmptySpool();
	}

	@Test
	void envelopesThatNeverReachTheBufferLeaveAccountingUntouched() throws IOException {
		// Non-queued outcomes only; each deletes its spool file synchronously, so no polling is needed.
		assertThat(postRaw(envelopes.error("prod").getBytes(StandardCharsets.UTF_8), "ffffffffffffffffffffffffffffffff")
			.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(postRaw("not json\n{}".getBytes(StandardCharsets.UTF_8), publicKey).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		String clientReportOnly = """
				{}
				{"type":"client_report"}
				{"discarded_events":[{"reason":"queue_overflow","category":"error","quantity":3}]}
				""";
		assertThat(post(clientReportOnly).getStatusCode()).isEqualTo(HttpStatus.OK);
		String ignored = """
				{}
				{"type":"session"}
				{}
				""";
		assertThat(post(ignored).getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(queue.outstanding()).isZero();
		assertThat(SpoolTestFiles.count(SPOOL_DIRECTORY)).isZero();
	}

	@Test
	void shutdownDrainClearsAccountingForResidualWork() throws IOException {
		workers.stop();
		for (int i = 0; i < CAPACITY; i++) {
			assertThat(post(envelopes.error("prod")).getStatusCode()).isEqualTo(HttpStatus.OK);
		}
		assertThat(queue.outstanding()).isEqualTo(CAPACITY);

		// A second stop() with a non-empty queue mimics the drain-timeout path without a real timeout.
		workers.stop();

		assertThat(queue.outstanding()).isZero();
		assertThat(SpoolTestFiles.count(SPOOL_DIRECTORY)).isZero();
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

	private void awaitNoOutstandingWork() {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline) && queue.outstanding() > 0) {
			sleep();
		}
		assertThat(queue.outstanding()).isZero();
	}

	private void awaitEmptySpool() throws IOException {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline) && SpoolTestFiles.count(SPOOL_DIRECTORY) > 0) {
			sleep();
		}
		assertThat(SpoolTestFiles.count(SPOOL_DIRECTORY)).isZero();
	}

	private void sleep() {
		try {
			Thread.sleep(25);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

}
