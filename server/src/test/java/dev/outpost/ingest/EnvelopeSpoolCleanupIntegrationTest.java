package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.support.EnvelopeFactory;
import java.io.IOException;
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
import org.springframework.web.client.RestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.ingest.workers=1", "outpost.ingest.linger-millis=10",
				"outpost.ingest.spool-directory=build/test-spool/cleanup" })
@Import(TestcontainersConfiguration.class)
class EnvelopeSpoolCleanupIntegrationTest {

	private static final Path SPOOL_DIRECTORY = Path.of("build/test-spool/cleanup");

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	final RestTemplate rest = new RestTemplate();

	final EnvelopeFactory envelopes = new EnvelopeFactory();

	long projectId;

	String publicKey;

	@BeforeEach
	void setUp() throws IOException {
		SpoolTestFiles.clear(SPOOL_DIRECTORY);
		jdbc.sql("DELETE FROM event").update();
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM project").update();
		projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES ('cleanup', 'Cleanup') RETURNING id")
			.query(Long.class)
			.single();
		publicKey = "0123456789abcdef0123456789abcdef";
		jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, ?)")
			.param(projectId)
			.param(publicKey)
			.update();
	}

	@Test
	void successfulDigestRemovesItsSpoolFile() throws IOException {
		ResponseEntity<String> response = post(envelopes.error("prod"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		awaitStoredEvent();
		awaitEmptySpool();
		assertThat(spoolFiles()).isZero();
	}

	private ResponseEntity<String> post(String envelope) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");
		return rest.exchange("http://localhost:" + port + "/api/" + projectId + "/envelope/?sentry_key=" + publicKey,
				HttpMethod.POST, new HttpEntity<>(envelope, headers), String.class);
	}

	private void awaitStoredEvent() {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
		while (Instant.now().isBefore(deadline)) {
			if (jdbc.sql("SELECT count(*) FROM event").query(Long.class).single() == 1) {
				return;
			}
			sleep();
		}
	}

	private void awaitEmptySpool() throws IOException {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
		while (Instant.now().isBefore(deadline) && spoolFiles() > 0) {
			sleep();
		}
	}

	private long spoolFiles() throws IOException {
		return SpoolTestFiles.count(SPOOL_DIRECTORY);
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
