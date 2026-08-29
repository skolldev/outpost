package dev.outpost.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * An MCP client, as far as the tests need one: the streamable-HTTP handshake and
 * one JSON-RPC round trip at a time, over a bearer token.
 *
 * <p>The exchange is what a real client's transport does — {@code initialize},
 * {@code notifications/initialized}, then {@code tools/list} and
 * {@code tools/call}, carrying the {@code Mcp-Session-Id} the server issued — so
 * the tests fail for the reasons a client would rather than for reasons only a
 * test can hit. Responses come back as JSON or as a one-event SSE stream
 * depending on what the transport chooses, which is why {@link #unwrap} handles
 * both.
 *
 * <p>Shared rather than copied because two suites drive the same surface: the one
 * that proves the transport and the auth work at all, and the one that exercises
 * each Tool. A second copy of this handshake would let them disagree about what
 * a client does, which is the one thing neither is testing.
 */
public final class McpTestClient {

	/** The protocol revision the SDK on the classpath speaks. */
	public static final String PROTOCOL_VERSION = "2025-06-18";

	/**
	 * {@code JdkClientHttpRequestFactory} rather than the default: the streamable
	 * transport answers a POST with a chunked {@code text/event-stream} body, and
	 * {@code HttpURLConnection} reports "Premature EOF" reading one. A real client is
	 * on a modern HTTP stack, so this is the honest one to test against.
	 */
	private final RestTemplate rest = new RestTemplate(new JdkClientHttpRequestFactory());

	private final String baseUrl;

	private final ObjectMapper mapper;

	private String sessionId;

	public McpTestClient(int port, ObjectMapper mapper) {
		this.baseUrl = "http://localhost:" + port;
		this.mapper = mapper;
		this.rest.setErrorHandler(new NoOpResponseErrorHandler());
	}

	/** The template, for tests that also drive the ingest or REST surfaces. */
	public RestTemplate rest() {
		return rest;
	}

	public String url(String path) {
		return baseUrl + path;
	}

	/** {@code initialize} + {@code notifications/initialized}, as a client does. */
	public McpTestClient connect(String token) {
		ResponseEntity<String> response = post(initialize(), token);
		assertThat(response.getStatusCode()).as("initialize: %s", response.getBody()).isEqualTo(HttpStatus.OK);
		sessionId = response.getHeaders().getFirst("Mcp-Session-Id");

		ResponseEntity<String> initialized = post(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"),
				token);
		assertThat(initialized.getStatusCode().is2xxSuccessful()).isTrue();
		return this;
	}

	/** The {@code initialize} request body, exposed so an auth test can send it unauthenticated. */
	public Map<String, Object> initialize() {
		return Map.of("jsonrpc", "2.0", "id", "init", "method", "initialize", "params",
				Map.of("protocolVersion", PROTOCOL_VERSION, "capabilities", Map.of(), "clientInfo",
						Map.of("name", "outpost-integration-test", "version", "1.0.0")));
	}

	/** One JSON-RPC round trip, returning the {@code result} object. */
	public JsonNode rpc(String token, String method, Map<String, Object> params) {
		ResponseEntity<String> response = post(
				Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", method, "params", params),
				token);
		assertThat(response.getStatusCode()).as("%s: %s", method, response.getBody()).isEqualTo(HttpStatus.OK);
		JsonNode body = unwrap(response.getBody());
		assertThat(body.has("error")).as("JSON-RPC error from %s: %s", method, response.getBody()).isFalse();
		return body.path("result");
	}

	/** {@code tools/call}, asserting the Tool succeeded, and returning its structured result. */
	public JsonNode callTool(String token, String name, Map<String, Object> arguments) {
		JsonNode result = rpc(token, "tools/call", Map.of("name", name, "arguments", arguments));
		assertThat(result.path("isError").asBoolean(false)).as("%s failed: %s", name, result).isFalse();
		assertThat(result.path("structuredContent").isObject()).as("%s returned no structured result: %s", name,
				result).isTrue();
		return result.path("structuredContent");
	}

	/** {@code tools/call} that is expected to fail, returning the Tool's error text. */
	public String callToolExpectingError(String token, String name, Map<String, Object> arguments) {
		JsonNode result = rpc(token, "tools/call", Map.of("name", name, "arguments", arguments));
		assertThat(result.path("isError").asBoolean()).as("%s was expected to fail: %s", name, result).isTrue();
		return result.path("content").get(0).path("text").asString();
	}

	public ResponseEntity<String> post(Map<String, Object> body, String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		// Both, exactly as the streamable-HTTP transport requires: the server may answer
		// a POST with a JSON body or with a single-event SSE stream.
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
	public JsonNode unwrap(String body) {
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

}
