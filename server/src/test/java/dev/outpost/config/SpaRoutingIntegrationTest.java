package dev.outpost.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Pins the boundary between client-side routes, which get the app shell, and
 * server-owned paths, which keep their own status. The bundle under test is the
 * stand-in in {@code src/test/resources/static/}; the real one only exists after
 * an image build.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class SpaRoutingIntegrationTest {

	@LocalServerPort
	int port;

	final RestTemplate rest = new RestTemplate();

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new NoOpResponseErrorHandler());
	}

	private ResponseEntity<String> get(String path) {
		return rest.getForEntity("http://localhost:" + port + path, String.class);
	}

	@Test
	void servesTheAppShellAtTheRoot() {
		ResponseEntity<String> response = get("/");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("<app-root>");
	}

	@Test
	void coldLoadOfAClientRouteFallsBackToTheShell() {
		ResponseEntity<String> response = get("/issues/42");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("<app-root>");
	}

	@Test
	void hashedBundlesAreCachedForever() {
		ResponseEntity<String> response = get("/main-TESTHASH.js");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getCacheControl()).contains("max-age=31536000").contains("immutable");
	}

	@Test
	void unhashedAssetsAndTheShellRevalidate() {
		assertThat(get("/favicon.ico").getHeaders().getCacheControl()).contains("no-cache");
		assertThat(get("/").getHeaders().getCacheControl()).contains("no-cache");
	}

	@Test
	void theBundleIsCompressed() {
		// Served as text/javascript, not application/javascript — a config listing only the latter would silently skip it.
		HttpHeaders request = new HttpHeaders();
		request.set(HttpHeaders.ACCEPT_ENCODING, "gzip");

		ResponseEntity<byte[]> response = rest.exchange("http://localhost:" + port + "/main-TESTHASH.js",
				HttpMethod.GET, new HttpEntity<>(request), byte[].class);

		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
	}

	@Test
	void aMissingBundleIs404RatherThanHtml() {
		// Returning the shell here would surface as an opaque MIME-type error in the browser instead of a missing file.
		ResponseEntity<String> response = get("/main-GONE.js");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void unmappedApiPathsStay404() {
		// Ingest paths are permitAll; without the resolver's prefix guard this would 200 with the shell instead of 404.
		ResponseEntity<String> response = get("/api/99999/does-not-exist");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getHeaders().getContentType()).isNotEqualTo(MediaType.TEXT_HTML);
	}

	@Test
	void theInternalApiStillRequiresASession() {
		// The catch-all must not shadow the security chain.
		ResponseEntity<String> response = get("/api/internal/projects");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}
}
