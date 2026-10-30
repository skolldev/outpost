package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.pipeline.EventStore;
import dev.outpost.pipeline.ProcessedEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The issue list's "Users" value, end to end: events stored through the real
 * {@link EventStore} and read back through {@code GET /api/internal/issues}.
 * Distinct users are not additive, so each case here is one a counter would
 * get wrong.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class IssueUsersAffectedIntegrationTest {

	/** Recent enough to fall inside any range the list is asked for. */
	private static final Instant DAY_ONE = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	EventStore store;

	@Autowired
	ObjectMapper mapper;

	final RestTemplate rest = new RestTemplate();

	long projectId;
	String sessionCookie;

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM event").update();
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM project").update();
		projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES ('users', 'Users') RETURNING id")
			.query(Long.class)
			.single();
		sessionCookie = login();
	}

	@Test
	void oneUserAcrossSeveralDaysCountsOnce() {
		store.store(List.of(event("checkout", "alice", DAY_ONE), event("checkout", "alice", DAY_ONE.plus(1, ChronoUnit.DAYS)),
				event("checkout", "alice", DAY_ONE.plus(2, ChronoUnit.DAYS))));

		assertThat(usersAffected()).containsExactlyEntriesOf(Map.of("checkout", 1L));
	}

	@Test
	void aRepeatWithinOneDayCountsOnce() {
		store.store(List.of(event("checkout", "alice", DAY_ONE)));
		store.store(List.of(event("checkout", "alice", DAY_ONE.plusSeconds(60)), event("checkout", "bob", DAY_ONE)));

		assertThat(usersAffected()).containsExactlyEntriesOf(Map.of("checkout", 2L));
	}

	@Test
	void eventsWithoutAUserAreExcluded() {
		store.store(List.of(event("checkout", null, DAY_ONE), event("checkout", null, DAY_ONE.plusSeconds(1)),
				event("checkout", "alice", DAY_ONE)));

		assertThat(usersAffected()).containsExactlyEntriesOf(Map.of("checkout", 1L));
	}

	@Test
	void anIssueWithNoUsersShowsZero() {
		store.store(List.of(event("anonymous", null, DAY_ONE)));

		assertThat(usersAffected()).containsExactlyEntriesOf(Map.of("anonymous", 0L));
	}

	@Test
	void twoIssuesOnOnePageDoNotBleedIntoEachOther() {
		store.store(List.of(event("checkout", "alice", DAY_ONE), event("checkout", "bob", DAY_ONE),
				event("login", "alice", DAY_ONE), event("login", "carol", DAY_ONE), event("login", "dave", DAY_ONE)));

		assertThat(usersAffected()).containsExactlyInAnyOrderEntriesOf(Map.of("checkout", 2L, "login", 3L));
	}

	@Test
	void redeliveredEventDoesNotDoubleCount() {
		ProcessedEvent first = event("checkout", "alice", DAY_ONE);
		store.store(List.of(first));
		store.store(List.of(first));
		store.store(List.of(event("checkout", "bob", DAY_ONE), first));

		assertThat(usersAffected()).containsExactlyEntriesOf(Map.of("checkout", 2L));
	}

	/**
	 * {@code user.id} arrives from the SDK unbounded. Keyed on the raw text, one
	 * past the btree entry limit (~2.7 KB) would fail the membership insert and
	 * drop an event that stored fine before.
	 */
	@Test
	void anOversizedUserIdStoresAndCountsOnce() {
		String huge = incompressible(10_000);
		store.store(List.of(event("checkout", huge, DAY_ONE), event("checkout", huge, DAY_ONE.plusSeconds(1)),
				event("checkout", huge + "x", DAY_ONE)));

		assertThat(jdbc.sql("SELECT count(*) FROM event").query(Long.class).single()).isEqualTo(3);
		assertThat(usersAffected()).containsExactlyEntriesOf(Map.of("checkout", 2L));
	}

	// ------------------------------------------------------------------ helpers

	/** users_affected per issue title, from the page the UI loads. */
	private Map<String, Long> usersAffected() {
		Map<String, Object> body = getJson("/api/internal/issues?project=" + projectId);
		List<Map<String, Object>> rows = cast(body.get("issues"));
		Map<String, Long> byTitle = new HashMap<>();
		for (Map<String, Object> row : rows) {
			byTitle.put((String) row.get("title"), ((Number) row.get("users_affected")).longValue());
		}
		return byTitle;
	}

	private ProcessedEvent event(String fingerprint, String userIdent, Instant timestamp) {
		return new ProcessedEvent(UUID.randomUUID(), projectId, "prod", null, timestamp, null, "error", "boom",
				"RuntimeException", fingerprint, "app.main", fingerprint, userIdent, mapper.createObjectNode(), null,
				null);
	}

	private String login() {
		ResponseEntity<Map> response = rest.postForEntity(url("/api/internal/auth/login"),
				Map.of("email", "admin@test.local", "password", "test-password"), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).isNotNull();
		return setCookie.split(";")[0];
	}

	private Map<String, Object> getJson(String path) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, sessionCookie);
		ResponseEntity<Map> response = rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return cast(response.getBody());
	}

	@SuppressWarnings("unchecked")
	private <T> T cast(Object value) {
		return (T) value;
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

	/** Random hex, so neither TOAST nor index compression can shrink it under the btree entry limit. */
	private static String incompressible(int length) {
		StringBuilder text = new StringBuilder(length + 36);
		while (text.length() < length) {
			text.append(UUID.randomUUID());
		}
		return text.substring(0, length);
	}
}
