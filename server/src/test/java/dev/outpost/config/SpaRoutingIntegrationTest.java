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
 * The UI and the API share one origin and one port now that nginx is gone, so
 * this pins the boundary between them: client-side routes get the app shell,
 * everything the server owns keeps its own status. The bundle under test is the
 * stand-in in {@code src/test/resources/static/} — the real one only exists
 * after an image build.
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
		// No file backs /issues/42 — without the fallback a deep link or a
		// refresh anywhere but "/" would 404.
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
		// The bundle is served as text/javascript, not application/javascript.
		// Listing only the latter leaves the single largest asset uncompressed,
		// which is invisible short of reading response headers.
		HttpHeaders request = new HttpHeaders();
		request.set(HttpHeaders.ACCEPT_ENCODING, "gzip");

		ResponseEntity<byte[]> response = rest.exchange("http://localhost:" + port + "/main-TESTHASH.js",
				HttpMethod.GET, new HttpEntity<>(request), byte[].class);

		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
	}

	@Test
	void aMissingBundleIs404RatherThanHtml() {
		// Returning the shell here would surface as an opaque MIME-type error in
		// the browser instead of a missing file.
		ResponseEntity<String> response = get("/main-GONE.js");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void unmappedApiPathsStay404() {
		// The ingest surface is permitAll, so this reaches the resource handler
		// the way an unmapped path does. Without the resolver's prefix guard it
		// would come back as a 200 carrying the app shell, and an SDK posting to
		// a mistyped endpoint would see success.
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
