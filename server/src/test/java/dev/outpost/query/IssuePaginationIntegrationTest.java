package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Keyset pagination for the issue list, end to end through {@link KeysetPage}.
 * The seam's mechanics are unit-tested in {@code KeysetPageTest}; this proves the
 * wiring the unit test can't see — the dual-mode sort switch across a real
 * two-page walk, and that {@code attachAggregates} (run between trim and cursor)
 * rides along without disturbing the walk. PAGE_SIZE is 50, so 51 seeded issues
 * force exactly two pages.
 *
 * <p>event_count is seeded inversely to last_seen, so the default
 * ({@code last_seen}) and {@code sort=count} walks produce genuinely different
 * orders — a bug that ignored the sort mode would surface as identical first
 * pages.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class IssuePaginationIntegrationTest {

	private static final int PAGE_SIZE = 50;
	private static final int ISSUE_COUNT = 51;

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	final RestTemplate rest = new RestTemplate();

	long project;
	String sessionCookie;
	List<Long> idsByLastSeenDesc = new ArrayList<>();
	List<Long> idsByCountDesc = new ArrayList<>();

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM project").update();
		project = jdbc.sql("INSERT INTO project (slug, name) VALUES ('pg', 'Pag') RETURNING id").query(Long.class)
			.single();

		Instant base = Instant.parse("2026-07-01T00:00:00Z");
		List<long[]> seeded = new ArrayList<>(); // [id, lastSeenSeconds, eventCount]
		for (int i = 0; i < ISSUE_COUNT; i++) {
			long lastSeenOffset = i; // higher i → more recent
			long eventCount = ISSUE_COUNT - i; // higher i → fewer events (inverse of recency)
			Instant lastSeen = base.plusSeconds(lastSeenOffset);
			long id = jdbc.sql("""
					INSERT INTO issue (project_id, fingerprint, title, first_seen, last_seen, event_count)
					VALUES (?, ?, ?, ?, ?, ?) RETURNING id
					""")
				.param(project)
				.param("fp-" + i)
				.param("boom " + i)
				.param(java.sql.Timestamp.from(base))
				.param(java.sql.Timestamp.from(lastSeen))
				.param(eventCount)
				.query(Long.class)
				.single();
			seeded.add(new long[] { id, lastSeenOffset, eventCount });
		}
		// Expected orders: last_seen DESC then id DESC; event_count DESC then id DESC.
		idsByLastSeenDesc = seeded.stream()
			.sorted((a, b) -> a[1] != b[1] ? Long.compare(b[1], a[1]) : Long.compare(b[0], a[0]))
			.map(r -> r[0])
			.toList();
		idsByCountDesc = seeded.stream()
			.sorted((a, b) -> a[2] != b[2] ? Long.compare(b[2], a[2]) : Long.compare(b[0], a[0]))
			.map(r -> r[0])
			.toList();

		sessionCookie = login();
	}

	@Test
	void defaultSortWalksBothPagesInLastSeenOrder() {
		Walk walk = walkAll("/api/internal/issues?project=" + project, "issues");
		assertThat(walk.firstPageSize).isEqualTo(PAGE_SIZE);
		assertThat(walk.pages).isEqualTo(2);
		assertThat(walk.ids).containsExactlyElementsOf(idsByLastSeenDesc);
	}

	@Test
	void countSortWalksBothPagesInEventCountOrder() {
		Walk walk = walkAll("/api/internal/issues?project=" + project + "&sort=count", "issues");
		assertThat(walk.firstPageSize).isEqualTo(PAGE_SIZE);
		assertThat(walk.pages).isEqualTo(2);
		assertThat(walk.ids).containsExactlyElementsOf(idsByCountDesc);
		// The two sort modes genuinely differ — the count walk does not equal the
		// last_seen walk, so the dual-mode descriptor selection is exercised.
		assertThat(walk.ids).isNotEqualTo(idsByLastSeenDesc);
	}

	@Test
	void pagesDoNotOverlapAndAggregatesRideAlong() {
		Map<String, Object> page1 = getJson("/api/internal/issues?project=" + project);
		List<Map<String, Object>> rows = cast(page1.get("issues"));
		// attachAggregates ran between trim and cursor: its keys are present on every row.
		assertThat(rows).allSatisfy(row -> {
			assertThat(row).containsKeys("sparkline", "users_affected", "environments");
		});
		String cursor = (String) page1.get("next_cursor");
		assertThat(cursor).isNotNull();

		Map<String, Object> page2 = getJson("/api/internal/issues?project=" + project + "&cursor=" + cursor);
		List<Map<String, Object>> rows2 = cast(page2.get("issues"));
		assertThat(rows2).hasSize(ISSUE_COUNT - PAGE_SIZE);
		assertThat(page2.get("next_cursor")).isNull();
	}

	// ------------------------------------------------------------------ helpers

	private record Walk(List<Long> ids, int pages, int firstPageSize) {
	}

	/** Follows next_cursor to the end, collecting row ids across every page. */
	private Walk walkAll(String path, String key) {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		int firstPageSize = -1;
		while (pages < 10) { // guard: our datasets are two pages
			String url = cursor == null ? path : path + "&cursor=" + cursor;
			Map<String, Object> body = getJson(url);
			List<Map<String, Object>> rows = cast(body.get(key));
			if (firstPageSize < 0) {
				firstPageSize = rows.size();
			}
			for (Map<String, Object> row : rows) {
				ids.add(((Number) row.get("id")).longValue());
			}
			pages++;
			cursor = (String) body.get("next_cursor");
			if (cursor == null) {
				break;
			}
		}
		return new Walk(ids, pages, firstPageSize);
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
}
