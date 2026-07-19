package dev.outpost.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestTemplate;

/**
 * Notification Channel management end to end: CRUD with validation and
 * admin-only gating, and the empty-filter-means-all default. Configuration
 * only — no delivery is exercised (that arrives in a later slice of #41).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class NotificationChannelIntegrationTest {

	private static final String CHANNELS = "/api/internal/notifications/channels";

	@org.springframework.boot.test.web.server.LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	final RestTemplate rest = new RestTemplate();

	long projectId;
	String adminCookie;

	@BeforeEach
	void setUp() {
		// The default HttpURLConnection factory can't send PATCH.
		rest.setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM notification_channel").update();
		jdbc.sql("DELETE FROM app_user WHERE email <> 'admin@test.local'").update();
		jdbc.sql("DELETE FROM project").update();
		projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES ('shop', 'Shop') RETURNING id")
			.query(Long.class)
			.single();
		adminCookie = login("admin@test.local", "test-password");
	}

	// ------------------------------------------------------------------ validation

	@Test
	void createRejectsInvalidInput() {
		assertThat(post(CHANNELS, body(Map.of("name", "", "type", "teams", "url", "https://example.com",
				"triggers", List.of("new_issue"))), adminCookie).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(post(CHANNELS, body(Map.of("name", "n", "type", "carrier-pigeon", "url", "https://example.com",
				"triggers", List.of("new_issue"))), adminCookie).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(post(CHANNELS, body(Map.of("name", "n", "type", "teams", "url", "not-a-url",
				"triggers", List.of("new_issue"))), adminCookie).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		// ftp is well-formed but not http(s).
		assertThat(post(CHANNELS, body(Map.of("name", "n", "type", "teams", "url", "ftp://example.com/x",
				"triggers", List.of("new_issue"))), adminCookie).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		// No triggers selected.
		assertThat(post(CHANNELS, body(Map.of("name", "n", "type", "teams", "url", "https://example.com",
				"triggers", List.of())), adminCookie).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		// Unknown trigger.
		assertThat(post(CHANNELS, body(Map.of("name", "n", "type", "teams", "url", "https://example.com",
				"triggers", List.of("deploy_started"))), adminCookie).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	// ------------------------------------------------------------------ CRUD

	@Test
	@SuppressWarnings("unchecked")
	void crudRoundTrip() {
		// Create: localhost URL is accepted (ADR 0006 — no egress filtering).
		ResponseEntity<Map> created = post(CHANNELS, body(Map.of("name", "Team alerts", "type", "teams",
				"url", "http://localhost:8080/hook", "triggers", List.of("new_issue", "incident_started"),
				"project_filter", List.of(projectId), "environment_filter", List.of("prod"))), adminCookie);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		Map<String, Object> body = created.getBody();
		long id = ((Number) body.get("id")).longValue();
		assertThat(body.get("name")).isEqualTo("Team alerts");
		assertThat(body.get("type")).isEqualTo("teams");
		assertThat(body.get("url")).isEqualTo("http://localhost:8080/hook");
		assertThat(body.get("enabled")).isEqualTo(true);
		assertThat((List<String>) body.get("triggers")).containsExactly("new_issue", "incident_started");
		assertThat(((List<Number>) body.get("project_filter")).stream().map(Number::longValue).toList())
			.containsExactly(projectId);
		assertThat((List<String>) body.get("environment_filter")).containsExactly("prod");

		// List returns it.
		Map<String, Object>[] listed = rest
			.exchange(url(CHANNELS), HttpMethod.GET, new HttpEntity<>(authHeaders(adminCookie)), Map[].class)
			.getBody();
		assertThat(listed).hasSize(1);
		assertThat(listed[0].get("name")).isEqualTo("Team alerts");

		// Update: change everything, including flipping enabled off (the toggle path).
		ResponseEntity<Map> updated = exchange(HttpMethod.PATCH, CHANNELS + "/" + id, body(Map.of(
				"name", "Ops JSON", "type", "generic_json", "url", "https://hooks.example.com/x", "enabled", false,
				"triggers", List.of("incident_resolved"), "project_filter", List.of(),
				"environment_filter", List.of())), adminCookie);
		assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map<String, Object> u = updated.getBody();
		assertThat(u.get("name")).isEqualTo("Ops JSON");
		assertThat(u.get("type")).isEqualTo("generic_json");
		assertThat(u.get("enabled")).isEqualTo(false);
		assertThat((List<String>) u.get("triggers")).containsExactly("incident_resolved");
		// Empty filters mean "all" — persisted as empty arrays.
		assertThat((List<Object>) u.get("project_filter")).isEmpty();
		assertThat((List<Object>) u.get("environment_filter")).isEmpty();

		// Update of a missing channel is a 404.
		assertThat(exchange(HttpMethod.PATCH, CHANNELS + "/999999", body(Map.of("name", "x", "type", "teams",
				"url", "https://example.com", "triggers", List.of("new_issue"))), adminCookie).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);

		// Delete.
		assertThat(exchange(HttpMethod.DELETE, CHANNELS + "/" + id, null, adminCookie).getStatusCode())
			.isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(count()).isZero();
		assertThat(exchange(HttpMethod.DELETE, CHANNELS + "/" + id, null, adminCookie).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ------------------------------------------------------------------ authorization

	@Test
	void managementRequiresAdmin() {
		post("/api/internal/users", Map.of("email", "member@test.local", "password", "member-password", "role",
				"member"), adminCookie);
		String memberCookie = login("member@test.local", "member-password");

		Map<String, Object> valid = body(Map.of("name", "n", "type", "teams", "url", "https://example.com",
				"triggers", List.of("new_issue")));
		// Even listing is admin-only — URLs are bearer credentials (ADR 0006).
		assertThat(exchange(HttpMethod.GET, CHANNELS, null, memberCookie).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(post(CHANNELS, valid, memberCookie).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(exchange(HttpMethod.PATCH, CHANNELS + "/1", valid, memberCookie).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(exchange(HttpMethod.DELETE, CHANNELS + "/1", null, memberCookie).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------ helpers

	private Map<String, Object> body(Map<String, Object> fields) {
		return new java.util.HashMap<>(fields);
	}

	private long count() {
		return jdbc.sql("SELECT count(*) FROM notification_channel").query(Long.class).single();
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

	private ResponseEntity<Map> post(String path, Map<String, ?> body, String cookie) {
		return exchange(HttpMethod.POST, path, body, cookie);
	}

	private ResponseEntity<Map> exchange(HttpMethod method, String path, Map<String, ?> body, String cookie) {
		return rest.exchange(url(path), method, new HttpEntity<>(body, authHeaders(cookie)), Map.class);
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}
}
