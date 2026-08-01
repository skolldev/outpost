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
 * Guards {@link IngestQueue#outstanding()} against drift. The counter is
 * incremented in {@code offer} and decremented in three separate places —
 * {@code completed} after a digest, {@code offer}'s own rollback when the buffer
 * is full, and {@code drainRemaining} at shutdown — so a new path that removes
 * work without decrementing leaks a count silently. Nothing throws and no
 * envelope is lost; the damage shows up later, because {@code outstanding} is
 * what {@code IngestWorkers.stop()} reports as residual work. A counter stuck
 * above zero makes every subsequent clean shutdown log "N items still queued or
 * in flight", which is the only signal an operator has that a drain failed.
 *
 * <p>Unlike the spool files it moves in lockstep with, a drifted counter has no
 * safety net: {@code SpoolReaper} bounds leaked files to {@code spool-max-age},
 * but nothing reconciles the count. Hence a test per outcome the endpoint can
 * produce, each ending at zero.
 *
 * <p>Determinism comes from driving {@link IngestWorkers} directly rather than
 * from timing. Workers are stopped to hold the buffer still where a test needs a
 * full queue, and restarted to observe the drain.
 *
 * <p>Expect real drift to fail most of this class at once rather than one test
 * cleanly: {@code setUp} stops the workers and then asserts the counter is zero,
 * so a leak from one test surfaces as a failed precondition in the next. That is
 * deliberate — the counter is process-wide and never legitimately non-zero at
 * rest — but when diagnosing, read the test whose body failed, not the ones that
 * failed in {@code setUp}.
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
		// Stopping first drains whatever a previous test left queued, so seeding
		// below cannot race a worker writing rows for a project about to be
		// deleted. Residual entries are discarded, not stored.
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
		// The rejected envelope must roll its own increment back rather than ride
		// on the accepted ones: the count is unchanged, not merely still positive.
		assertThat(queue.outstanding()).isEqualTo(CAPACITY);
		assertThat(SpoolTestFiles.count(SPOOL_DIRECTORY)).isEqualTo(CAPACITY);

		workers.start();

		awaitNoOutstandingWork();
		awaitEmptySpool();
	}

	@Test
	void envelopesThatNeverReachTheBufferLeaveAccountingUntouched() throws IOException {
		// Every non-queued outcome the endpoint can produce. None of these should
		// touch outstanding at all, and each deletes its spool file before
		// responding, so no polling is needed.
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

		// A second stop() with no live workers and a non-empty queue is the state
		// the drain timeout leaves behind, reached without a wall clock: the join
		// loop finds nothing to wait for and the residual path runs immediately.
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
