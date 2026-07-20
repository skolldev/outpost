package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.web.client.RestTemplate;

/**
 * Keyset pagination for the log list, end to end through {@link KeysetPage}. Logs
 * sort by {@code (timestamp, id)} with a UUID id and PAGE_SIZE 100, so 101 seeded
 * records force two pages. Proves the seam drives the ordered walk with no
 * overlap and a terminating cursor — the coverage LogController never had.
 *
 * <p>{@code log_record} is weekly range-partitioned, so partitions are ensured
 * before seeding, exactly as the ingest store does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class LogPaginationIntegrationTest {

	private static final int PAGE_SIZE = 100;
	private static final int LOG_COUNT = 101;

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	final RestTemplate rest = new RestTemplate();

	long project;
	String sessionCookie;
	List<UUID> idsByTimestampDesc = new ArrayList<>();

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM log_record").update();
		jdbc.sql("DELETE FROM project").update();
		project = jdbc.sql("INSERT INTO project (slug, name) VALUES ('pg', 'Pag') RETURNING id").query(Long.class)
			.single();

		Instant base = Instant.parse("2026-07-01T00:00:00Z");
		partitions.ensurePartition(PartitionManager.LOG_RECORD, base);
		List<Object[]> seeded = new ArrayList<>(); // [id, timestampSeconds]
		for (int i = 0; i < LOG_COUNT; i++) {
			UUID id = UUID.randomUUID();
			Instant ts = base.plusSeconds(i); // higher i → more recent
			jdbc.sql("""
					INSERT INTO log_record (id, project_id, environment, "timestamp", level, body)
					VALUES (?, ?, 'prod', ?, 'info', ?)
					""")
				.param(id)
				.param(project)
				.param(java.sql.Timestamp.from(ts))
				.param("line " + i)
				.update();
			seeded.add(new Object[] { id, (long) i });
		}
		// Expected order: timestamp DESC then id DESC.
		idsByTimestampDesc = seeded.stream()
			.sorted((a, b) -> {
				long ta = (long) a[1];
				long tb = (long) b[1];
				return ta != tb ? Long.compare(tb, ta) : ((UUID) b[0]).compareTo((UUID) a[0]);
			})
			.map(r -> (UUID) r[0])
			.toList();

		sessionCookie = login();
	}

	@Test
	void walksBothPagesInTimestampOrderWithoutOverlap() {
		List<UUID> ids = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		int firstPageSize = -1;
		while (pages < 10) {
			String url = "/api/internal/logs?project=" + project + (cursor == null ? "" : "&cursor=" + cursor);
			Map<String, Object> body = getJson(url);
			List<Map<String, Object>> rows = cast(body.get("logs"));
			if (firstPageSize < 0) {
				firstPageSize = rows.size();
			}
			for (Map<String, Object> row : rows) {
				ids.add(UUID.fromString((String) row.get("id")));
			}
			pages++;
			cursor = (String) body.get("next_cursor");
			if (cursor == null) {
				break;
			}
		}
		assertThat(firstPageSize).isEqualTo(PAGE_SIZE);
		assertThat(pages).isEqualTo(2);
		assertThat(ids).doesNotHaveDuplicates();
		assertThat(ids).containsExactlyElementsOf(idsByTimestampDesc);
	}

	@Test
	void secondPageHoldsRemainderAndTerminates() {
		Map<String, Object> page1 = getJson("/api/internal/logs?project=" + project);
		assertThat((List<?>) page1.get("logs")).hasSize(PAGE_SIZE);
		String cursor = (String) page1.get("next_cursor");
		assertThat(cursor).isNotNull();

		Map<String, Object> page2 = getJson("/api/internal/logs?project=" + project + "&cursor=" + cursor);
		assertThat((List<?>) page2.get("logs")).hasSize(LOG_COUNT - PAGE_SIZE);
		assertThat(page2.get("next_cursor")).isNull();
	}

	// ------------------------------------------------------------------ helpers

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
