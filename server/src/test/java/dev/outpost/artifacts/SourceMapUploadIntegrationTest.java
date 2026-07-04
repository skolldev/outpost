package dev.outpost.artifacts;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.outpost.TestcontainersConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Phase 2 exit check (§12 P2), CLI side simulated wire-exactly: bearer-token
 * chunk upload → assemble → a minified event ingests into an original-source
 * stack trace. Uses the same real Angular build fixtures as
 * {@code SourceMapConsumerTest}, so the expected positions come from Node's
 * reference implementation, not from our own decoder.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
		"outpost.ingest.linger-millis=50" })
@Import(TestcontainersConfiguration.class)
class SourceMapUploadIntegrationTest {

	static final String DEBUG_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
	static final String RELEASE = "demo-frontend@1.0.0";
	static final String MINIFIED_URL = "http://localhost:4200/chunk-X.js";

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	final RestTemplate rest = new RestTemplate();
	final ObjectMapper mapper = new ObjectMapper();

	long projectId;
	String publicKey;
	String sessionCookie;
	String apiToken;

	@BeforeEach
	void setUp() {
		rest.setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM event").update();
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM artifact_bundle").update();
		jdbc.sql("DELETE FROM upload_chunk").update();
		jdbc.sql("DELETE FROM api_token").update();
		jdbc.sql("DELETE FROM project").update();
		projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES ('demo', 'Demo') RETURNING id")
			.query(Long.class)
			.single();
		publicKey = "0123456789abcdef0123456789abcdef";
		jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, ?)")
			.param(projectId)
			.param(publicKey)
			.update();
		sessionCookie = login();
		apiToken = createToken();
	}

	@Test
	void uploadAssembleSymbolicateEndToEnd() {
		// Capability discovery steers the CLI to the debug-ID artifact-bundle flow.
		Map<String, Object> capabilities = getWithBearer("/api/0/organizations/outpost/chunk-upload/");
		assertThat(this.<List<String>>cast(capabilities.get("accept"))).contains("artifact_bundles");
		assertThat(capabilities.get("hashAlgorithm")).isEqualTo("sha1");

		byte[] bundle = buildBundle(DEBUG_ID, "chunk-X.js");
		String checksum = sha1(bundle);

		// Assemble before uploading: the server must list the missing chunk.
		Map<String, Object> notFound = assemble(checksum, RELEASE);
		assertThat(notFound.get("state")).isEqualTo("not_found");
		assertThat(this.<List<String>>cast(notFound.get("missingChunks"))).containsExactly(checksum);

		uploadChunk(bundle);
		Map<String, Object> assembled = assemble(checksum, RELEASE);
		assertThat(assembled.get("state")).isEqualTo("ok");
		// Idempotent re-assemble (CLI retries, re-runs of the same CI job).
		assertThat(assemble(checksum, RELEASE).get("state")).isEqualTo("ok");

		// A minified error event now comes out with original TS source (§12 P2 exit).
		JsonNode probe = namedProbe();
		postEnvelope(jsEventEnvelope(DEBUG_ID, probe.get("generatedLine").asInt() + 1,
				probe.get("generatedColumn").asInt() + 1));
		Map<String, Object> event = awaitEvent("symbolicated");

		Map<String, Object> frame = topFrame(event);
		assertThat(frame.get("filename")).isEqualTo("src/app/pages/issue-detail.ts");
		assertThat(frame.get("lineno")).isEqualTo(probe.get("originalLine").asInt() + 1);
		assertThat(frame.get("colno")).isEqualTo(probe.get("originalColumn").asInt() + 1);
		assertThat(frame.get("function")).isEqualTo(probe.get("name").asText());
		assertThat(frame.get("in_app")).isEqualTo(true);
		assertThat(frame.get("context_line")).isNotNull();
		assertThat((List<?>) frame.get("pre_context")).isNotEmpty();

		// The pre-symbolication frames stay available.
		Map<String, Object> exception = primaryException(event);
		Map<String, Object> raw = cast(exception.get("raw_stacktrace"));
		List<Map<String, Object>> rawFrames = cast(raw.get("frames"));
		assertThat(rawFrames.get(rawFrames.size() - 1).get("abs_path")).isEqualTo(MINIFIED_URL);

		// Releases API reflects the uploaded bundle.
		List<Map<String, Object>> releases = cast(getJson("/api/internal/releases?project=" + projectId));
		assertThat(releases).anySatisfy(release -> {
			assertThat(release.get("version")).isEqualTo(RELEASE);
			assertThat(((Number) release.get("bundle_count")).intValue()).isEqualTo(1);
			assertThat(((Number) release.get("artifact_count")).intValue()).isEqualTo(2);
		});
		List<Map<String, Object>> artifacts = cast(getJson("/api/internal/releases/"
				+ java.net.URLEncoder.encode(RELEASE, StandardCharsets.UTF_8) + "/artifacts?project=" + projectId));
		assertThat(artifacts).hasSize(2);
		assertThat(artifacts).allSatisfy(artifact -> assertThat(artifact.get("debug_id")).isEqualTo(DEBUG_ID));
	}

	@Test
	void lateUploadTriggersResymbolication() {
		String debugId = "11111111-2222-3333-4444-555555555555";
		JsonNode probe = namedProbe();

		// Event arrives before its source map: flagged, with banner data.
		postEnvelope(jsEventEnvelope(debugId, probe.get("generatedLine").asInt() + 1,
				probe.get("generatedColumn").asInt() + 1));
		Map<String, Object> flagged = awaitEvent("missing_sourcemap");
		Map<String, Object> data = cast(flagged.get("data"));
		Map<String, Object> warning = cast(data.get("_outpost_symbolication"));
		List<Map<String, Object>> missing = cast(warning.get("missing"));
		assertThat(missing.get(0).get("debug_id")).isEqualTo(debugId);
		Map<String, Object> minifiedFrame = topFrame(flagged);
		assertThat(minifiedFrame.get("abs_path")).isEqualTo(MINIFIED_URL);

		// The late bundle upload re-processes the flagged event in place.
		byte[] bundle = buildBundle(debugId, "chunk-X.js");
		uploadChunk(bundle);
		assertThat(assemble(sha1(bundle), RELEASE).get("state")).isEqualTo("ok");

		Map<String, Object> event = awaitEvent("symbolicated");
		assertThat(topFrame(event).get("filename")).isEqualTo("src/app/pages/issue-detail.ts");
		assertThat(cast(event.get("data"), Map.class).get("_outpost_symbolication")).isNull();
	}

	@Test
	void uploadSurfaceRequiresToken() {
		ResponseEntity<String> anonymous = rest
			.getForEntity(url("/api/0/organizations/outpost/chunk-upload/"), String.class);
		assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth("outpost_" + "0".repeat(48));
		ResponseEntity<String> badToken = rest.exchange(url("/api/0/organizations/outpost/chunk-upload/"),
				HttpMethod.GET, new HttpEntity<>(headers), String.class);
		assertThat(badToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void legacyPerProjectAssemblePathAndReleaseUpsertsWork() {
		byte[] bundle = buildBundle("99999999-8888-7777-6666-555555555555", "other.js");
		uploadChunk(bundle);
		Map<String, Object> response = postJsonWithBearer(
				"/api/0/projects/outpost/demo/artifactbundle/assemble/",
				Map.of("checksum", sha1(bundle), "chunks", List.of(sha1(bundle)), "version", "demo-frontend@2.0.0"));
		assertThat(response.get("state")).isEqualTo("ok");

		Map<String, Object> created = postJsonWithBearer("/api/0/organizations/outpost/releases/",
				Map.of("version", "demo-frontend@3.0.0", "projects", List.of("demo")));
		assertThat(created.get("version")).isEqualTo("demo-frontend@3.0.0");
		Long releases = jdbc.sql("SELECT count(*) FROM release WHERE project_id = ?")
			.param(projectId)
			.query(Long.class)
			.single();
		assertThat(releases).isEqualTo(2);
	}

	// ------------------------------------------------------------------ helpers

	/** First probe of the real-Angular fixture that maps to issue-detail.ts with a name. */
	private JsonNode namedProbe() {
		JsonNode probes = readResource("/sourcemap/angular-chunk.expected.json");
		for (JsonNode probe : probes) {
			if (probe.hasNonNull("name")
					&& "src/app/pages/issue-detail.ts".equals(probe.path("originalSource").asText(null))) {
				return probe;
			}
		}
		throw new IllegalStateException("fixture has no named probe for issue-detail.ts");
	}

	/**
	 * Source bundle like sentry-cli builds: an 8-byte {@code SYSB} + version
	 * prefix, then a zip with manifest.json + minified file + source map.
	 */
	private byte[] buildBundle(String debugId, String fileName) {
		byte[] minified = rawResource("/sourcemap/angular-chunk.js");
		byte[] map = rawResource("/sourcemap/angular-chunk.js.map");
		String manifest = """
				{"org":"outpost","release":"%s","files":{
				 "files/_/_/%s":{"url":"~/%s","type":"minified_source",
				   "headers":{"debug-id":"%s","sourcemap":"%s.map"}},
				 "files/_/_/%s.map":{"url":"~/%s.map","type":"source_map","headers":{"debug-id":"%s"}}
				}}""".formatted(RELEASE, fileName, fileName, debugId, fileName, fileName, fileName, debugId);
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.write(new byte[] { 'S', 'Y', 'S', 'B', 2, 0, 0, 0 });
			try (ZipOutputStream zip = new ZipOutputStream(out)) {
				zip.putNextEntry(new ZipEntry("manifest.json"));
				zip.write(manifest.getBytes(StandardCharsets.UTF_8));
				zip.putNextEntry(new ZipEntry("files/_/_/" + fileName));
				zip.write(minified);
				zip.putNextEntry(new ZipEntry("files/_/_/" + fileName + ".map"));
				zip.write(map);
			}
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Multipart part named file_gzip, filename = sha1 of the uncompressed chunk — the CLI wire format. */
	private void uploadChunk(byte[] chunk) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(apiToken);
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
		form.add("file_gzip", new ByteArrayResource(gzip(chunk)) {
			@Override
			public String getFilename() {
				return sha1(chunk);
			}
		});
		ResponseEntity<String> response = rest.postForEntity(url("/api/0/organizations/outpost/chunk-upload/"),
				new HttpEntity<>(form, headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	private Map<String, Object> assemble(String checksum, String release) {
		return postJsonWithBearer("/api/0/organizations/outpost/artifactbundle/assemble/", Map.of(
				"checksum", checksum, "chunks", List.of(checksum), "projects", List.of("demo"), "version", release));
	}

	private String jsEventEnvelope(String debugId, int lineno, int colno) {
		String event = """
				{"event_id":"%s","timestamp":"%s","platform":"javascript","level":"error","environment":"prod",\
				"release":"%s",\
				"debug_meta":{"images":[{"type":"sourcemap","code_file":"%s","debug_id":"%s"}]},\
				"exception":{"values":[{"type":"TypeError","value":"boom",\
				"stacktrace":{"frames":[\
				{"abs_path":"%s","filename":"/chunk-X.js","function":"Ct","lineno":%d,"colno":%d,"in_app":true}\
				]}}]}}""".formatted(hexId(), Instant.now(), RELEASE, MINIFIED_URL, debugId.toUpperCase(), MINIFIED_URL,
				lineno, colno);
		return "{\"event_id\":\"" + hexId() + "\",\"sent_at\":\"" + Instant.now() + "\"}\n"
				+ "{\"type\":\"event\",\"length\":" + event.getBytes(StandardCharsets.UTF_8).length + "}\n" + event
				+ "\n";
	}

	private void postEnvelope(String envelope) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");
		ResponseEntity<String> response = rest.exchange(
				url("/api/" + projectId + "/envelope/?sentry_key=" + publicKey), HttpMethod.POST,
				new HttpEntity<>(envelope.getBytes(StandardCharsets.UTF_8), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	/** Waits for exactly one stored event to reach the wanted symbolication status. */
	private Map<String, Object> awaitEvent(String status) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		Map<String, Object> last = null;
		while (Instant.now().isBefore(deadline)) {
			List<UUID> ids = jdbc.sql("SELECT id FROM event WHERE project_id = ?")
				.param(projectId)
				.query(UUID.class)
				.list();
			if (ids.size() == 1) {
				last = cast(getJson("/api/internal/events/" + ids.get(0)));
				if (status.equals(last.get("symbolication_status"))) {
					return last;
				}
			}
			sleep();
		}
		assertThat(last).as("event with symbolication_status=" + status).isNotNull();
		assertThat(last.get("symbolication_status")).isEqualTo(status);
		return last;
	}

	private Map<String, Object> primaryException(Map<String, Object> event) {
		Map<String, Object> data = cast(event.get("data"));
		Map<String, Object> exception = cast(data.get("exception"));
		List<Map<String, Object>> values = cast(exception.get("values"));
		return values.get(values.size() - 1);
	}

	private Map<String, Object> topFrame(Map<String, Object> event) {
		Map<String, Object> stacktrace = cast(primaryException(event).get("stacktrace"));
		List<Map<String, Object>> frames = cast(stacktrace.get("frames"));
		return frames.get(frames.size() - 1);
	}

	private String login() {
		ResponseEntity<Map> response = rest.postForEntity(url("/api/internal/auth/login"),
				Map.of("email", "admin@test.local", "password", "test-password"), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
	}

	private String createToken() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, sessionCookie);
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map> response = rest.postForEntity(url("/api/internal/tokens"),
				new HttpEntity<>(Map.of("name", "ci"), headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return (String) response.getBody().get("token");
	}

	private Map<String, Object> getWithBearer(String path) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(apiToken);
		ResponseEntity<Map> response = rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return cast(response.getBody());
	}

	private Map<String, Object> postJsonWithBearer(String path, Map<String, Object> body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(apiToken);
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map> response = rest.postForEntity(url(path), new HttpEntity<>(body, headers), Map.class);
		assertThat(response.getStatusCode().is2xxSuccessful()).as("POST " + path + " → " + response.getBody())
			.isTrue();
		return cast(response.getBody());
	}

	private Object getJson(String path) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, sessionCookie);
		ResponseEntity<Object> response = rest.exchange(java.net.URI.create(url(path)), HttpMethod.GET,
				new HttpEntity<>(headers), Object.class);
		assertThat(response.getStatusCode()).as("GET " + path + " → " + response.getBody())
			.isEqualTo(HttpStatus.OK);
		return response.getBody();
	}

	private JsonNode readResource(String path) {
		try (InputStream in = getClass().getResourceAsStream(path)) {
			return mapper.readTree(in);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private byte[] rawResource(String path) {
		try (InputStream in = getClass().getResourceAsStream(path)) {
			return in.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private <T> T cast(Object value) {
		return (T) value;
	}

	@SuppressWarnings("unchecked")
	private <T> T cast(Object value, Class<T> type) {
		return (T) value;
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

	private static String hexId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private static void sleep() {
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static String sha1(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] gzip(byte[] data) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
				gz.write(data);
			}
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
