package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.ingest.spool-directory=build/test-spool/not-a-directory" })
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class EnvelopeSpoolFailureIntegrationTest {

	private static final Path SPOOL_DIRECTORY = Path.of("build/test-spool/not-a-directory");

	@LocalServerPort
	int port;

	final RestTemplate rest = new RestTemplate();

	@BeforeEach
	void setUp() throws IOException {
		rest.setErrorHandler(new NoOpResponseErrorHandler());
		Files.createDirectories(SPOOL_DIRECTORY.getParent());
		Files.deleteIfExists(SPOOL_DIRECTORY);
		Files.writeString(SPOOL_DIRECTORY, "this path is deliberately a file");
	}

	@AfterEach
	void tearDown() throws IOException {
		Files.deleteIfExists(SPOOL_DIRECTORY);
	}

	@Test
	void spoolWriteFailureReturns500AndLogsTheCause(CapturedOutput output) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");

		ResponseEntity<String> response = rest.exchange("http://localhost:" + port + "/api/1/envelope/",
				HttpMethod.POST, new HttpEntity<>("{}\n".getBytes(StandardCharsets.UTF_8), headers), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).contains("unable to spool envelope");
		assertThat(output).contains("failed to spool envelope for project 1");
	}
}
