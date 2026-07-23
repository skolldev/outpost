package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestTemplate;

/**
 * Backend coverage for #82 / ADR 0009: repeated {@code project} params on the
 * telemetry list endpoints ({@code project_id IN (...)}), and the
 * {@code GET /projects/environments} intersection endpoint. The intersection is
 * over Environment <em>Names</em> across the in-scope Projects, ignoring Projects
 * that have no Environments yet (no evidence, not evidence of absence).
 *
 * <p>Seeds {@code environment} and {@code issue} rows directly — this exercises
 * query behavior, not ingest, so direct seeding keeps it deterministic and off
 * the async pipeline (both are plain, non-partitioned tables).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class EnvironmentIntersectionIntegrationTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	final RestTemplate rest = new RestTemplate();

	long frontend; // envs: local, dev, qa
	long backend; // envs: dev, qa
	long empty; // no environments yet
	String sessionCookie;

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM environment").update();
		jdbc.sql("DELETE FROM project").update();
		frontend = newProject("frontend");
		backend = newProject("backend");
		empty = newProject("empty");
		addEnvironments(frontend, "local", "dev", "qa");
		addEnvironments(backend, "dev", "qa");
		// `empty` intentionally has no environment rows.
		sessionCookie = login();
	}

	@Test
	void intersectionAcrossSelectedProjects() {
		// frontend {local,dev,qa} ∩ backend {dev,qa} = dev, qa (sorted).
		assertThat(environments("?project=" + frontend + "&project=" + backend)).containsExactly("dev", "qa");
	}

	@Test
	void singleProjectReturnsItsOwnEnvironmentsSorted() {
		assertThat(environments("?project=" + frontend)).containsExactly("dev", "local", "qa");
	}

	@Test
	void envlessProjectIsIgnoredNotBlanking() {
		// The env-less Project must not empty the bar — it drops out of the denominator,
		// leaving frontend's own environments.
		assertThat(environments("?project=" + frontend + "&project=" + empty)).containsExactly("dev", "local", "qa");
	}

	@Test
	void noProjectParamIntersectsAcrossAllProjects() {
		// All Projects: frontend ∩ backend = dev, qa; `empty` is ignored, not counted.
		assertThat(environments("")).containsExactly("dev", "qa");
	}

	@Test
	void issuesListFiltersAcrossMultipleProjects() {
		seedIssue(frontend, "fe");
		seedIssue(backend, "be");
		seedIssue(empty, "other");

		List<Map<String, Object>> issues = issues("?project=" + frontend + "&project=" + backend);

		assertThat(issues).extracting(i -> ((Number) i.get("project_id")).longValue())
			.containsExactlyInAnyOrder(frontend, backend);
	}

	@Test
	void singleProjectParamStillBinds() {
		seedIssue(frontend, "fe");
		seedIssue(backend, "be");

		List<Map<String, Object>> issues = issues("?project=" + frontend);

		assertThat(issues).extracting(i -> ((Number) i.get("project_id")).longValue()).containsExactly(frontend);
	}

	// ------------------------------------------------------------------ seeding

	private long newProject(String slug) {
		return jdbc.sql("INSERT INTO project (slug, name) VALUES (?, ?) RETURNING id")
			.param(slug)
			.param(slug)
			.query(Long.class)
			.single();
	}

	private void addEnvironments(long projectId, String... names) {
		for (String name : names) {
			jdbc.sql("INSERT INTO environment (project_id, name) VALUES (?, ?)").param(projectId).param(name).update();
		}
	}

	private void seedIssue(long projectId, String fingerprint) {
		jdbc.sql("""
				INSERT INTO issue (project_id, fingerprint, title, first_seen, last_seen)
				VALUES (?, ?, ?, now(), now())
				""").param(projectId).param(fingerprint).param("boom").update();
	}

	// ------------------------------------------------------------------ helpers

	private List<String> environments(String queryString) {
		ResponseEntity<String[]> response = exchange("/api/internal/projects/environments" + queryString,
				String[].class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return List.of(response.getBody());
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> issues(String queryString) {
		ResponseEntity<Map> response = exchange("/api/internal/issues" + queryString, Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return (List<Map<String, Object>>) response.getBody().get("issues");
	}

	private <T> ResponseEntity<T> exchange(String path, Class<T> type) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, sessionCookie);
		return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), type);
	}

	private String login() {
		ResponseEntity<Map> response = rest.postForEntity(url("/api/internal/auth/login"),
				Map.of("email", "admin@test.local", "password", "test-password"), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).isNotNull();
		return setCookie.split(";")[0];
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

}
