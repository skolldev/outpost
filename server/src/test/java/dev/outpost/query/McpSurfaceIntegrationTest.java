package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.auth.ApiTokenService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The vertical slice #178 exists to prove: a real MCP client reaches {@code /mcp}
 * over streamable HTTP with an {@code Authorization: Bearer} header, past the
 * hand-rolled {@code SecurityConfig} chain, and gets one Tool back that returns
 * an Issue's context.
 *
 * <p>The exchange below is what the MCP streamable-HTTP transport actually does —
 * {@code initialize}, {@code notifications/initialized}, then {@code tools/list}
 * and {@code tools/call}, carrying the {@code Mcp-Session-Id} the server issued —
 * so it fails for the same reasons a client would rather than for reasons only a
 * test can hit. Responses come back either as JSON or as a one-event SSE stream
 * depending on what the transport chooses, which is why {@link #rpc} unwraps both.
 *
 * <p>Telemetry is posted through the real ingest surface, as
 * {@code TraceQueryIntegrationTest} does: the Tool reads {@code event.data} the
 * pipeline wrote, and a hand-inserted row would let a projection drift away from
 * the payload shape ingestion actually produces.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
				"outpost.ingest.linger-millis=50" })
@Import(TestcontainersConfiguration.class)
class McpSurfaceIntegrationTest {

	private static final String TRACE_ID = "c0ffee00c0ffee00c0ffee00c0ffee00";
	private static final String BACKEND_SPAN = "cccc000000000001";

	/** The protocol revision the SDK on the classpath speaks. */
	private static final String PROTOCOL_VERSION = "2025-06-18";

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper mapper;

	/**
	 * {@code JdkClientHttpRequestFactory} rather than the default: the streamable
	 * transport answers a POST with a chunked {@code text/event-stream} body, and
	 * {@code HttpURLConnection} reports "Premature EOF" reading one. A real client
	 * is on a modern HTTP stack, so this is the honest one to test against.
	 */
	final RestTemplate rest = new RestTemplate(new JdkClientHttpRequestFactory());

	/**
	 * One base instant for the whole fixture, so the Log Record can be placed
	 * <em>before</em> the Event deliberately rather than by winning a race with the
	 * next HTTP round trip. The window the Tool reads ends at the Event, so "before"
	 * is the behaviour under test, not an incidental detail.
	 */
	Instant base;

	long projectId;
	String projectKey;
	String readToken;
	String writeOnlyToken;
	String sessionId;

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM span").update();
		jdbc.sql("DELETE FROM txn").update();
		jdbc.sql("DELETE FROM log_record").update();
		jdbc.sql("DELETE FROM event").update();
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM project").update();
		jdbc.sql("DELETE FROM api_token").update();
		projectId = jdbc.sql("INSERT INTO project (slug, name, platform) VALUES ('shop', 'Shop', 'java') RETURNING id")
			.query(Long.class)
			.single();
		projectKey = "0123456789abcdef0123456789abcdef";
		jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, ?)")
			.param(projectId)
			.param(projectKey)
			.update();
		// No migration and no UI in this slice (#178): the token rows go in by hand.
		readToken = insertToken("agent", ApiTokenService.SCOPE_TELEMETRY_READ);
		writeOnlyToken = insertToken("ci", ApiTokenService.SCOPE_ARTIFACTS_WRITE);
		sessionId = null;
		base = Instant.now();
	}

	// --------------------------------------------------------------------- auth

	/**
	 * 401 rather than 403, and the challenge names the Scope: an MCP client reads a
	 * 401 as "present a credential" and a 403 as final, so a token minted before
	 * {@code telemetry:read} existed has to look fixable rather than fatal.
	 */
	@Test
	void mcpRefusesATokenWithoutTelemetryRead() {
		ResponseEntity<String> response = post(initialize(), writeOnlyToken);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().getFirst("WWW-Authenticate")).contains("telemetry:read");
	}

	@Test
	void mcpRefusesAnUnknownTokenAndNoTokenAtAll() {
		assertThat(post(initialize(), "outpost_deadbeef").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(post(initialize(), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	/**
	 * The bearer surfaces stay separate: widening the filter to {@code /mcp} must not
	 * have widened what a Scope buys. 403 rather than the 401 {@code /mcp} answers —
	 * sentry-cli's surface keeps the status it has always returned, and
	 * {@code SecurityConfig.denyAccess} says why the two differ.
	 */
	@Test
	void telemetryReadDoesNotOpenTheUploadSurface() {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(readToken);
		ResponseEntity<String> response = rest.exchange(url("/api/0/organizations/outpost/chunk-upload/"),
				HttpMethod.GET, new HttpEntity<>(headers), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	// -------------------------------------------------------------------- tools

	@Test
	void aClientListsExactlyTheOneToolThisSliceShips() {
		connect();

		JsonNode tools = rpc("tools/list", Map.of()).path("tools");

		assertThat(tools.size()).isEqualTo(1);
		JsonNode tool = tools.get(0);
		assertThat(tool.path("name").asString()).isEqualTo("get_issue_context");
		JsonNode properties = tool.path("inputSchema").path("properties");
		assertThat(properties.propertyNames()).containsExactlyInAnyOrder("issue_id", "log_window_minutes");
		assertThat(tool.path("inputSchema").path("required")).singleElement()
			.satisfies(required -> assertThat(required.asString()).isEqualTo("issue_id"));

		JsonNode outputSchema = tool.path("outputSchema");
		assertThat(outputSchema.path("type").asString()).isEqualTo("object");
		assertThat(outputSchema.path("properties").propertyNames()).containsExactlyInAnyOrder("issue", "latest_event",
				"exception", "breadcrumbs", "log_window", "log_records", "trace", "caveats");
		assertThat(outputSchema.path("required").valueStream().map(JsonNode::asString).toList())
			.containsExactlyInAnyOrder("issue", "breadcrumbs", "log_records", "caveats");
		assertThat(outputSchema.toString()).contains("events_received", "symbolication_status", "frames_in_stack",
				"minutes_before_event", "transactions_received", "error_events_received");
	}

	@Test
	void getIssueContextReturnsTheIssueItsEventStackLogsAndTrace() {
		postEnvelope(errorEnvelope());
		postEnvelope(logEnvelope());
		postEnvelope(transactionEnvelope());
		long issueId = awaitIssue();
		connect();

		JsonNode context = callTool(Map.of("issue_id", issueId));

		assertThat(context.path("issue").path("id").asLong()).isEqualTo(issueId);
		assertThat(context.path("issue").path("project_slug").asString()).isEqualTo("shop");
		// Named for the units it reports in, per ADR-0014 — not `event_count`.
		assertThat(context.path("issue").path("events_received").asLong()).isEqualTo(1);

		assertThat(context.path("latest_event").path("trace_id").asString()).isEqualTo(TRACE_ID);
		assertThat(context.path("exception").path("type").asString()).isEqualTo("IllegalStateException");
		assertThat(context.path("exception").path("frames")).isNotEmpty();
		// Frames arrive newest-first: the throw site is what a reader starts from.
		assertThat(context.path("exception").path("frames").get(0).path("function").asString())
			.isEqualTo("loadCustomer");
		assertThat(context.path("breadcrumbs")).singleElement()
			.satisfies(crumb -> assertThat(crumb.path("message").asString()).isEqualTo("POST /api/checkout"));

		// The Log Record was written before the Event, which is the half of the window
		// the Tool keeps — see IssueContextTool.Window.
		assertThat(context.path("log_records")).isNotEmpty();
		assertThat(context.path("log_records").get(0).path("body").asString()).isEqualTo("handling checkout");
		assertThat(context.path("log_window").path("minutes_before_event").asInt())
			.isEqualTo(IssueContextTool.DEFAULT_LOG_WINDOW_MINUTES);

		assertThat(context.path("trace").path("trace_id").asString()).isEqualTo(TRACE_ID);
		assertThat(context.path("trace").path("root_name").asString()).isEqualTo("POST /api/checkout");
		assertThat(context.path("trace").path("transactions_received").asLong()).isEqualTo(1);
		assertThat(context.path("trace").path("spans_received").asLong()).isEqualTo(1);
		assertThat(context.path("trace").path("error_events_received").asLong()).isEqualTo(1);
	}

	/**
	 * The ADR-0014 disclosures, which are the reason this Tool is worth having over
	 * three thinner ones: the raw payload is not returned, and what was left out is
	 * named rather than left to be inferred.
	 */
	@Test
	void theRawEventPayloadIsNotReturnedAndTheOmissionIsNamed() {
		postEnvelope(errorEnvelope());
		long issueId = awaitIssue();
		connect();

		JsonNode context = callTool(Map.of("issue_id", issueId));

		assertThat(context.has("data")).isFalse();
		assertThat(caveats(context)).anySatisfy(caveat -> assertThat(caveat)
			.contains("The raw event payload is not returned whole")
			.contains("contexts")
			.contains("tags"));
		assertThat(caveats(context)).anySatisfy(
				caveat -> assertThat(caveat).contains("log_window_minutes was not supplied"));
	}

	/**
	 * The failure mode ADR-0014 names outright: handing a model a minified frame
	 * without saying it is minified. The status rides on the Event and the warning
	 * rides in a caveat, so neither truncation nor re-summarization loses it.
	 */
	@Test
	void anUnsymbolicatedStackIsDisclosedOnTheEventAndInACaveat() {
		postEnvelope(errorEnvelope());
		long issueId = awaitIssue();
		jdbc.sql("UPDATE event SET symbolication_status = 'missing_sourcemap' WHERE issue_id = ?")
			.param(issueId)
			.update();
		connect();

		JsonNode context = callTool(Map.of("issue_id", issueId));

		assertThat(context.path("latest_event").path("symbolication_status").asString())
			.isEqualTo("missing_sourcemap");
		assertThat(caveats(context)).anySatisfy(caveat -> assertThat(caveat)
			.contains("not symbolicated")
			.contains("missing_sourcemap"));
	}

	@Test
	void anUnknownIssueIsAToolErrorRatherThanAProtocolError() {
		connect();

		JsonNode result = rpc("tools/call",
				Map.of("name", "get_issue_context", "arguments", Map.of("issue_id", 987654321L)));

		assertThat(result.path("isError").asBoolean()).isTrue();
		assertThat(textContent(result)).contains("987654321");
	}

	@Test
	void anOversizedLogWindowIsClampedAndTheClampDisclosed() {
		postEnvelope(errorEnvelope());
		long issueId = awaitIssue();
		connect();

		JsonNode context = callTool(Map.of("issue_id", issueId, "log_window_minutes", 10_000));

		assertThat(context.path("log_window").path("minutes_before_event").asInt())
			.isEqualTo(IssueContextTool.MAX_LOG_WINDOW_MINUTES);
		assertThat(caveats(context))
			.anySatisfy(caveat -> assertThat(caveat).contains("clamped from 10000"));
	}

	// ----------------------------------------------------------------- protocol

	/** {@code initialize} + {@code notifications/initialized}, as a client does. */
	private void connect() {
		ResponseEntity<String> response = post(initialize(), readToken);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		sessionId = response.getHeaders().getFirst("Mcp-Session-Id");
		JsonNode result = unwrap(response.getBody()).path("result");
		assertThat(result.path("serverInfo").path("name").asString()).isEqualTo("outpost");
		assertThat(result.path("capabilities").path("tools")).isNotEmpty();

		ResponseEntity<String> initialized = post(
				Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), readToken);
		assertThat(initialized.getStatusCode().is2xxSuccessful()).isTrue();
	}

	/** One JSON-RPC round trip, returning the {@code result} object. */
	private JsonNode rpc(String method, Map<String, Object> params) {
		ResponseEntity<String> response = post(
				Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", method, "params", params),
				readToken);
		assertThat(response.getStatusCode()).as("%s: %s", method, response.getBody()).isEqualTo(HttpStatus.OK);
		JsonNode body = unwrap(response.getBody());
		assertThat(body.has("error")).as("JSON-RPC error from %s: %s", method, response.getBody()).isFalse();
		return body.path("result");
	}

	/** {@code tools/call} for {@code get_issue_context}, read from its typed MCP result. */
	private JsonNode callTool(Map<String, Object> arguments) {
		JsonNode result = rpc("tools/call", Map.of("name", "get_issue_context", "arguments", arguments));
		assertThat(result.path("isError").asBoolean(false)).as("tool error: %s", result).isFalse();
		assertThat(result.path("structuredContent").isObject()).as("structured tool result: %s", result).isTrue();
		return result.path("structuredContent");
	}

	private String textContent(JsonNode result) {
		return result.path("content").get(0).path("text").asString();
	}

	private List<String> caveats(JsonNode context) {
		return context.path("caveats").valueStream().map(JsonNode::asString).toList();
	}

	private Map<String, Object> initialize() {
		return Map.of("jsonrpc", "2.0", "id", "init", "method", "initialize", "params",
				Map.of("protocolVersion", PROTOCOL_VERSION, "capabilities", Map.of(), "clientInfo",
						Map.of("name", "outpost-integration-test", "version", "1.0.0")));
	}

	private ResponseEntity<String> post(Map<String, Object> body, String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		// Both, exactly as the streamable-HTTP transport requires: the server may
		// answer a POST with a JSON body or with a single-event SSE stream.
		headers.set(HttpHeaders.ACCEPT, "application/json, text/event-stream");
		if (token != null) {
			headers.setBearerAuth(token);
		}
		if (sessionId != null) {
			headers.set("Mcp-Session-Id", sessionId);
		}
		return rest.exchange(url("/mcp"), HttpMethod.POST,
				new HttpEntity<>(mapper.writeValueAsString(body), headers), String.class);
	}

	/** Reads a JSON-RPC message out of either a plain body or an SSE frame. */
	private JsonNode unwrap(String body) {
		assertThat(body).isNotBlank();
		if (!body.stripLeading().startsWith("{")) {
			String data = body.lines()
				.filter(line -> line.startsWith("data:"))
				.map(line -> line.substring("data:".length()).strip())
				.findFirst()
				.orElseThrow(() -> new AssertionError("no SSE data frame in: " + body));
			return mapper.readTree(data);
		}
		return mapper.readTree(body);
	}

	// ------------------------------------------------------------------ fixture

	private String insertToken(String name, String scope) {
		String secret = "outpost_" + UUID.randomUUID().toString().replace("-", "");
		jdbc.sql("INSERT INTO api_token (name, token_hash, scopes) VALUES (?, ?, string_to_array(?, ','))")
			.param(name)
			.param(sha256(secret))
			.param(scope)
			.update();
		return secret;
	}

	/**
	 * The same SHA-256 {@code ApiTokenService} hashes with. Duplicated rather than
	 * called because the point of the fixture is a row inserted by hand, exactly as
	 * an operator does until the token UI lands in the next slice.
	 */
	private static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private String errorEnvelope() {
		String event = """
				{"event_id":"%s","timestamp":"%s","platform":"java","level":"error","environment":"prod",\
				"release":"shop@1.0.0","tags":{"handled":"no"},\
				"contexts":{"runtime":{"name":"OpenJDK"},"trace":{"trace_id":"%s","span_id":"%s"}},\
				"breadcrumbs":{"values":[{"timestamp":"%s","type":"http","category":"request",\
				"level":"info","message":"POST /api/checkout"}]},\
				"exception":{"values":[{"type":"IllegalStateException","value":"order 4711 has no customer",\
				"module":"java.lang","stacktrace":{"frames":[\
				{"module":"dev.demo.CheckoutController","function":"checkout","in_app":true,"lineno":41},\
				{"module":"dev.demo.OrderService","function":"loadCustomer","in_app":true,"lineno":88}]}}]}}"""
			.formatted(hexId(), base, TRACE_ID, BACKEND_SPAN, base.minusSeconds(3));
		return envelope("event", event);
	}

	private String transactionEnvelope() {
		Instant start = base.minusMillis(200);
		Instant end = base;
		String txn = """
				{"type":"transaction","transaction":"POST /api/checkout","platform":"java","environment":"prod",\
				"release":"shop@1.0.0","start_timestamp":%s,"timestamp":%s,\
				"contexts":{"trace":{"trace_id":"%s","span_id":"%s","op":"http.server","status":"internal_error"}},\
				"spans":[{"trace_id":"%s","span_id":"cccc000000000002","parent_span_id":"%s","op":"db.sql.query",\
				"description":"SELECT * FROM orders WHERE id = ?","start_timestamp":%s,"timestamp":%s,\
				"status":"ok"}]}"""
			.formatted(start.toEpochMilli() / 1000.0, end.toEpochMilli() / 1000.0, TRACE_ID, BACKEND_SPAN, TRACE_ID,
					BACKEND_SPAN, start.toEpochMilli() / 1000.0, end.toEpochMilli() / 1000.0);
		return envelope("transaction", txn);
	}

	/** Two seconds before the Event: a Log Record leading up to the failure. */
	private String logEnvelope() {
		String payload = """
				{"items":[{"timestamp":%s,"trace_id":"%s","span_id":"%s","level":"info","severity_number":9,\
				"body":"handling checkout","attributes":{"sentry.environment":{"value":"prod","type":"string"}}}]}"""
			.formatted(base.minusSeconds(2).toEpochMilli() / 1000.0, TRACE_ID, BACKEND_SPAN);
		return "{\"sent_at\":\"" + Instant.now() + "\"}\n"
				+ "{\"type\":\"log\",\"item_count\":1,\"content_type\":\"application/vnd.sentry.items.log+json\",\"length\":"
				+ payload.getBytes(StandardCharsets.UTF_8).length + "}\n" + payload + "\n";
	}

	private String envelope(String type, String payload) {
		return "{\"sent_at\":\"" + Instant.now() + "\"}\n" + "{\"type\":\"" + type + "\",\"length\":"
				+ payload.getBytes(StandardCharsets.UTF_8).length + "}\n" + payload + "\n";
	}

	private void postEnvelope(String envelope) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");
		ResponseEntity<String> response = rest.exchange(url("/api/" + projectId + "/envelope/?sentry_key=" + projectKey),
				HttpMethod.POST, new HttpEntity<>(envelope.getBytes(StandardCharsets.UTF_8), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	/** Ingestion is asynchronous; wait for the pipeline to open the Issue. */
	private long awaitIssue() {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline)) {
			List<Long> ids = jdbc.sql("SELECT id FROM issue ORDER BY id").query(Long.class).list();
			if (!ids.isEmpty()) {
				return ids.get(0);
			}
			sleep();
		}
		throw new AssertionError("no issue was created within 10s");
	}

	private void sleep() {
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private String hexId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}
}
