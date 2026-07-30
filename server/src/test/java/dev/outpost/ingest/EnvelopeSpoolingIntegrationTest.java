package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.support.EnvelopeFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.ingest.workers=0", "outpost.ingest.shutdown-timeout=10ms",
				"outpost.ingest.queue-capacity=1",
				"outpost.ingest.spool-directory=build/test-spool/reference" })
@Import(TestcontainersConfiguration.class)
class EnvelopeSpoolingIntegrationTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IngestQueue queue;

	final RestTemplate rest = new RestTemplate();

	final EnvelopeFactory envelopes = new EnvelopeFactory();

	long projectId;

	String publicKey;

	@BeforeEach
	void setUp() throws IOException, InterruptedException {
		rest.setErrorHandler(new NoOpResponseErrorHandler());
		clearQueue();
		SpoolTestFiles.clear(spoolDirectory());
		jdbc.sql("DELETE FROM project").update();
		projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES ('spool', 'Spool') RETURNING id")
			.query(Long.class)
			.single();
		publicKey = "0123456789abcdef0123456789abcdef";
		jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, ?)")
			.param(projectId)
			.param(publicKey)
			.update();
	}

	@AfterEach
	void tearDown() throws IOException, InterruptedException {
		for (QueuedEnvelope queued : clearQueue()) {
			Files.deleteIfExists(queued.spoolFile().path());
		}
		SpoolTestFiles.clear(spoolDirectory());
	}

	@Test
	void acceptedEnvelopeQueuesAReferenceToItsRawWireBody() throws IOException, InterruptedException {
		byte[] body = envelopes.error("prod").getBytes(StandardCharsets.UTF_8);

		ResponseEntity<String> response = post(body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<QueuedEnvelope> queued = queue.nextBatch(10, 0);
		assertThat(queued).singleElement().satisfies(item -> {
			assertThat(item.projectId()).isEqualTo(projectId);
			assertThat(item.spoolFile().path()).exists();
			assertThat(read(item.spoolFile().path())).containsExactly(body);
		});
		queue.completed(queued.size());
	}

	@Test
	void gzippedEnvelopeKeepsTheOriginalWireBodyInItsSpool() throws IOException, InterruptedException {
		byte[] wireBody = gzip(envelopes.error("prod").getBytes(StandardCharsets.UTF_8));

		ResponseEntity<String> response = post(wireBody, true);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<QueuedEnvelope> queued = queue.nextBatch(10, 0);
		assertThat(queued).singleElement().satisfies(item -> {
			assertThat(item.spoolFile().gzip()).isTrue();
			assertThat(read(item.spoolFile().path())).containsExactly(wireBody);
		});
		queue.completed(queued.size());
	}

	@Test
	void rejectedEnvelopeDoesNotLeaveAnUnqueuedSpoolFile() throws IOException {
		assertThat(post(envelopes.error("prod").getBytes(StandardCharsets.UTF_8)).getStatusCode())
			.isEqualTo(HttpStatus.OK);

		ResponseEntity<String> rejected = post(envelopes.error("prod").getBytes(StandardCharsets.UTF_8));

		assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(SpoolTestFiles.count(spoolDirectory())).isEqualTo(1);
	}

	private ResponseEntity<String> post(byte[] body) {
		return post(body, false);
	}

	private ResponseEntity<String> post(byte[] body, boolean gzip) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");
		if (gzip) {
			headers.set("Content-Encoding", "gzip");
		}
		return rest.exchange("http://localhost:" + port + "/api/" + projectId + "/envelope/?sentry_key=" + publicKey,
				HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
	}

	private List<QueuedEnvelope> clearQueue() throws InterruptedException {
		List<QueuedEnvelope> queued = queue.nextBatch(100, 0);
		if (!queued.isEmpty()) {
			queue.completed(queued.size());
		}
		return queued;
	}

	private Path spoolDirectory() {
		return Path.of("build/test-spool/reference");
	}

	private byte[] read(Path path) {
		try {
			return Files.readAllBytes(path);
		}
		catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private byte[] gzip(byte[] body) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
				gzip.write(body);
			}
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
