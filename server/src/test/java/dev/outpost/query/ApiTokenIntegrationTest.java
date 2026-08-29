package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.auth.ApiTokenService;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * The API Token ownership and Scope model of ADR-0017: a Member may mint a
 * Personal Token carrying {@code telemetry:read} and nothing else, sees only
 * their own tokens, and deleting their account revokes those tokens while
 * leaving Installation Tokens working.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
				"outpost.public-url=https://outpost.test.local/o" })
@Import(TestcontainersConfiguration.class)
class ApiTokenIntegrationTest {

	private static final String TOKENS = "/api/internal/tokens";

	private static final String USERS = "/api/internal/users";

	/** A capability-discovery GET, the cheapest probe that a bearer token still works. */
	private static final String CLI_PROBE = "/api/0/organizations/outpost/chunk-upload/";

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	final RestTemplate rest = new RestTemplate();

	String adminCookie;

	String memberCookie;

	long memberId;

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM api_token").update();
		jdbc.sql("DELETE FROM app_user WHERE email <> 'admin@test.local'").update();
		adminCookie = login("admin@test.local", "test-password");
		memberId = createUser("member@test.local", "member-password", "member");
		memberCookie = login("member@test.local", "member-password");
	}

	// ------------------------------------------------------------------ lifetime

	/**
	 * The acceptance criterion of ADR-0017, both halves in one test: the cascade on
	 * {@code owner_user_id} revokes a departed Member's Personal Token immediately,
	 * and the Installation Token that has no owner keeps authenticating.
	 */
	@Test
	void deletingAnOutpostUserRevokesTheirPersonalTokensAndSparesInstallationTokens() {
		String personal = create(memberCookie,
				Map.of("name", "agent", "scopes", List.of(ApiTokenService.SCOPE_TELEMETRY_READ), "personal", true));
		String installation = create(adminCookie, Map.of("name", "ci", "scopes",
				List.of(ApiTokenService.SCOPE_ARTIFACTS_WRITE), "personal", false));
		assertThat(bearerStatus(CLI_PROBE, installation)).isEqualTo(HttpStatus.OK);

		assertThat(delete(USERS + "/" + memberId, adminCookie)).isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(tokenNames(list(adminCookie))).containsExactly("ci");
		assertThat(bearerStatus("/mcp", personal)).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(bearerStatus(CLI_PROBE, installation)).isEqualTo(HttpStatus.OK);
	}

	/** Rows that predate the migration have no owner and go on working unchanged. */
	@Test
	void aTokenCreatedBeforeOwnershipExistedStillAuthenticates() {
		String secret = "outpost_" + java.util.UUID.randomUUID().toString().replace("-", "");
		jdbc.sql("INSERT INTO api_token (name, token_hash, scopes) VALUES ('legacy', ?, string_to_array(?, ','))")
			.param(sha256(secret))
			.param(ApiTokenService.SCOPE_ARTIFACTS_WRITE)
			.update();

		assertThat(bearerStatus(CLI_PROBE, secret)).isEqualTo(HttpStatus.OK);
		assertThat(list(adminCookie).get(0).get("owner_user_id")).isNull();
	}

	// --------------------------------------------------------------------- scopes

	@Test
	void aMemberCannotGrantArtifactsWrite() {
		ResponseEntity<Map> response = post(TOKENS, memberCookie,
				Map.of("name", "sneaky", "scopes", List.of(ApiTokenService.SCOPE_ARTIFACTS_WRITE)));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat((String) response.getBody().get("detail")).contains(ApiTokenService.SCOPE_ARTIFACTS_WRITE);
		assertThat(list(adminCookie)).isEmpty();
	}

	@Test
	void aMemberCannotCreateAnInstallationToken() {
		ResponseEntity<Map> response = post(TOKENS, memberCookie, Map.of("name", "shared", "scopes",
				List.of(ApiTokenService.SCOPE_TELEMETRY_READ), "personal", false));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(list(adminCookie)).isEmpty();
	}

	@Test
	void rejectsAnEmptyOrUnknownScopeList() {
		assertThat(post(TOKENS, adminCookie, Map.of("name", "none", "scopes", List.of())).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(post(TOKENS, adminCookie, Map.of("name", "typo", "scopes", List.of("telemetry:write")))
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(list(adminCookie)).isEmpty();
	}

	/** An Admin creating a token for their own agent gets one that is theirs. */
	@Test
	void anAdminChoosesBetweenPersonalAndInstallation() {
		create(adminCookie, Map.of("name", "my-agent", "scopes", List.of(ApiTokenService.SCOPE_TELEMETRY_READ),
				"personal", true));
		create(adminCookie,
				Map.of("name", "ci", "scopes", List.of(ApiTokenService.SCOPE_ARTIFACTS_WRITE), "personal", false));

		List<Map<String, Object>> tokens = list(adminCookie);

		assertThat(tokens).anySatisfy(token -> {
			assertThat(token.get("name")).isEqualTo("my-agent");
			assertThat(token.get("owner_email")).isEqualTo("admin@test.local");
		});
		assertThat(tokens).anySatisfy(token -> {
			assertThat(token.get("name")).isEqualTo("ci");
			assertThat(token.get("owner_user_id")).isNull();
			assertThat(token.get("owner_email")).isNull();
		});
	}

	/**
	 * The reveal carries the MCP Surface URL from {@code outpost.public-url},
	 * sub-path and all — the browser has no way to reconstruct it.
	 */
	@Test
	void theCreationResponseCarriesTheMcpUrl() {
		ResponseEntity<Map> response = post(TOKENS, memberCookie,
				Map.of("name", "agent", "scopes", List.of(ApiTokenService.SCOPE_TELEMETRY_READ)));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().get("mcp_url")).isEqualTo("https://outpost.test.local/o/mcp");
		assertThat(response.getBody().get("owner_email")).isEqualTo("member@test.local");
	}

	// ------------------------------------------------------------- visibility

	/**
	 * The two tokens a Member must not reach are a second Member's Personal Token —
	 * which the {@code owner_user_id} predicate is what excludes — and an
	 * Installation Token, which has no owner at all.
	 */
	@Test
	void aMemberSeesAndRevokesOnlyTheirOwnTokens() {
		createUser("other@test.local", "other-password", "member");
		String otherCookie = login("other@test.local", "other-password");
		create(memberCookie, Map.of("name", "mine", "scopes", List.of(ApiTokenService.SCOPE_TELEMETRY_READ)));
		create(otherCookie, Map.of("name", "theirs", "scopes", List.of(ApiTokenService.SCOPE_TELEMETRY_READ)));
		create(adminCookie, Map.of("name", "ci", "scopes", List.of(ApiTokenService.SCOPE_ARTIFACTS_WRITE)));

		assertThat(tokenNames(list(memberCookie))).containsExactly("mine");
		assertThat(tokenNames(list(adminCookie))).containsExactlyInAnyOrder("mine", "theirs", "ci");

		// Someone else's token is a 404, not a 403 — a 403 would confirm the id.
		List<Map<String, Object>> all = list(adminCookie);
		assertThat(delete(TOKENS + "/" + idOf(all, "theirs"), memberCookie)).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(delete(TOKENS + "/" + idOf(all, "ci"), memberCookie)).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(tokenNames(list(adminCookie))).contains("theirs", "ci");

		assertThat(delete(TOKENS + "/" + idOf(list(memberCookie), "mine"), memberCookie))
			.isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(list(memberCookie)).isEmpty();
	}

	@Test
	void anAdminRevokesAnyToken() {
		create(memberCookie, Map.of("name", "mine", "scopes", List.of(ApiTokenService.SCOPE_TELEMETRY_READ)));

		assertThat(delete(TOKENS + "/" + idOf(list(adminCookie), "mine"), adminCookie))
			.isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(list(adminCookie)).isEmpty();
	}

	// ------------------------------------------------------------------ helpers

	private String create(String cookie, Map<String, Object> body) {
		ResponseEntity<Map> response = post(TOKENS, cookie, body);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return (String) response.getBody().get("token");
	}

	@SuppressWarnings("rawtypes")
	private ResponseEntity<Map> post(String path, String cookie, Map<String, Object> body) {
		return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, authHeaders(cookie)), Map.class);
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> list(String cookie) {
		ResponseEntity<List> response = rest.exchange(url(TOKENS), HttpMethod.GET,
				new HttpEntity<>(authHeaders(cookie)), List.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getBody();
	}

	private List<String> tokenNames(List<Map<String, Object>> tokens) {
		return tokens.stream().map(token -> (String) token.get("name")).toList();
	}

	private long idOf(List<Map<String, Object>> tokens, String name) {
		return tokens.stream()
			.filter(token -> name.equals(token.get("name")))
			.map(token -> ((Number) token.get("id")).longValue())
			.findFirst()
			.orElseThrow();
	}

	private HttpStatusCode delete(String path, String cookie) {
		return rest.exchange(url(path), HttpMethod.DELETE, new HttpEntity<>(authHeaders(cookie)), Void.class)
			.getStatusCode();
	}

	/** Presents a bearer token to a surface that requires one and reports the status. */
	private HttpStatusCode bearerStatus(String path, String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
		return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode();
	}

	private long createUser(String email, String password, String role) {
		ResponseEntity<Map> response = post(USERS, adminCookie,
				Map.of("email", email, "password", password, "role", role));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return ((Number) response.getBody().get("id")).longValue();
	}

	private String login(String email, String password) {
		ResponseEntity<Map> response = rest.postForEntity(url("/api/internal/auth/login"),
				Map.of("email", email, "password", password), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
	}

	private HttpHeaders authHeaders(String cookie) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, cookie);
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

	private static String sha256(String value) {
		try {
			return java.util.HexFormat.of()
				.formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
