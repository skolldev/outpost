package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.outpost.TestcontainersConfiguration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestTemplate;

/**
 * Request-size limits are enforced at the public envelope endpoint. The small
 * limits make it practical to exercise both layers without large test heaps.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.ingest.max-envelope-wire-bytes=65536",
		"outpost.ingest.max-envelope-decompressed-bytes=8388608" })
@Import(TestcontainersConfiguration.class)
class EnvelopeRequestLimitsIntegrationTest {

	private static final int WIRE_LIMIT = 64 * 1024;
	private static final int DECOMPRESSED_LIMIT = 8 * 1024 * 1024;
	private static final int ZIP_BOMB_INFLATED_SIZE = 128 * 1024 * 1024;
	private static final byte[] ZIP_BOMB = gzipZeros(ZIP_BOMB_INFLATED_SIZE);

	@LocalServerPort
	int port;

	@Autowired
	EnvelopeController controller;

	final RestTemplate rest = new RestTemplate();

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
	}

	@Test
	void zipBombIsRejectedByWireLimitBeforeInflation() throws IOException, InterruptedException {
		assertThat(ZIP_BOMB.length).isGreaterThan(WIRE_LIMIT);
		assertThat(ZIP_BOMB_INFLATED_SIZE).isGreaterThan(DECOMPRESSED_LIMIT);

		HttpResponse<String> response = postChunked(ZIP_BOMB);

		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(response.body()).contains("wire size limit");
	}

	@Test
	void zipBombAllocationStaysBoundedByWireLimit() {
		com.sun.management.ThreadMXBean threads =
				(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		if (!threads.isThreadAllocatedMemoryEnabled()) {
			threads.setThreadAllocatedMemoryEnabled(true);
		}
		// Warm class loading and the exception path outside the measurement.
		assertThatThrownBy(() -> controller.envelope(999999, null, chunkedGzipRequest(ZIP_BOMB)))
			.isInstanceOf(EnvelopeParser.OversizeException.class);

		long threadId = Thread.currentThread().threadId();
		long before = threads.getThreadAllocatedBytes(threadId);
		assertThatThrownBy(() -> controller.envelope(999999, null, chunkedGzipRequest(ZIP_BOMB)))
			.isInstanceOf(EnvelopeParser.OversizeException.class);
		long allocated = threads.getThreadAllocatedBytes(threadId) - before;

		assertThat(allocated).isLessThan(WIRE_LIMIT * 4L);
	}

	@Test
	void uncompressedSenderIsHeldToWireLimitBeforeAuthentication() {
		byte[] body = new byte[WIRE_LIMIT + 1];

		ResponseEntity<String> response = post(body, false);

		assertThat(response.getStatusCode().value()).isEqualTo(413);
		assertThat(response.getBody()).contains("wire size limit");
	}

	@Test
	void compressedBodyUnderWireLimitUsesConfiguredDecompressedLimit() {
		byte[] body = gzipZeros(DECOMPRESSED_LIMIT + 1);
		assertThat(body.length).isLessThanOrEqualTo(WIRE_LIMIT);

		ResponseEntity<String> response = post(body, true);

		assertThat(response.getStatusCode().value()).isEqualTo(413);
		assertThat(response.getBody()).contains("decompressed size limit");
	}

	@Test
	void truncatedGzipBodyIsMalformedRatherThanAnInternalError() {
		byte[] gzip = gzipZeros(128);
		byte[] truncated = Arrays.copyOf(gzip, gzip.length - 4);

		ResponseEntity<String> response = post(truncated, true);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody()).contains("invalid gzip body");
	}

	@Test
	void nonPositiveLimitsAreRejectedAtStartup() {
		assertThatThrownBy(() -> new EnvelopeSpool(Path.of("build/test-spool/limits"), 0, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("max-envelope-wire-bytes");
		assertThatThrownBy(() -> new EnvelopeSpool(Path.of("build/test-spool/limits"), 1, -1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("max-envelope-decompressed-bytes");
	}

	@Test
	void bodyWithinBothLimitsReachesAuthentication() {
		byte[] body = "{}\n".getBytes(StandardCharsets.UTF_8);

		ResponseEntity<String> response = post(body, false);

		assertThat(response.getStatusCode().value()).isEqualTo(403);
	}

	private ResponseEntity<String> post(byte[] body, boolean gzip) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");
		if (gzip) {
			headers.set("Content-Encoding", "gzip");
		}
		return rest.exchange("http://localhost:" + port + "/api/999999/envelope/", HttpMethod.POST,
				new HttpEntity<>(body, headers), String.class);
	}

	private HttpResponse<String> postChunked(byte[] body) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + "/api/999999/envelope/"))
			.header("Content-Type", "application/x-sentry-envelope")
			.header("Content-Encoding", "gzip")
			.POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
			.build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}

	private MockHttpServletRequest chunkedGzipRequest(byte[] body) {
		MockHttpServletRequest request = new MockHttpServletRequest() {
			@Override
			public long getContentLengthLong() {
				return -1;
			}
		};
		request.addHeader("Content-Encoding", "gzip");
		request.setContent(body);
		return request;
	}

	private static byte[] gzipZeros(int uncompressedSize) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
				for (int remaining = uncompressedSize; remaining > 0; remaining -= chunk.length) {
					gzip.write(chunk, 0, Math.min(remaining, chunk.length));
				}
			}
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
