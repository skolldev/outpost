package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * {@code GET /logs/timeline} end to end (#141): the wire shape the chart is drawn
 * from, and the two rules the client cannot enforce for itself — that the server
 * resolves the window it was not given, and that the window is half-open.
 *
 * <p>Records are seeded on exact bucket boundaries rather than at convenient
 * offsets, because the boundary is where every off-by-one in this feature lives: a
 * record on a bucket edge counted into both of the selections either side of it is
 * the defect the half-open window exists to prevent, and it is invisible to a
 * fixture whose timestamps all sit mid-bucket.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class LogTimelineIntegrationTest {

	/** Inside a 1-hour window the ladder picks 1-minute buckets, so these land one per bucket. */
	private static final Instant ANCHOR = Instant.parse("2026-07-01T12:00:00Z");

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	final RestTemplate rest = new RestTemplate();

	long project;

	String sessionCookie;

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM log_record").update();
		jdbc.sql("DELETE FROM project").update();
		project = jdbc.sql("INSERT INTO project (slug, name) VALUES ('tl', 'Timeline') RETURNING id")
			.query(Long.class)
			.single();
		partitions.ensurePartition(PartitionManager.LOG_RECORD, ANCHOR);

		// 12:00 → two info; 12:01 → one error. 12:02 is left empty on purpose.
		seed(ANCHOR, "info");
		seed(ANCHOR.plusSeconds(30), "info");
		seed(ANCHOR.plus(1, ChronoUnit.MINUTES), "error");

		sessionCookie = login();
	}

	@Test
	void bucketsCarryPerLevelCountsAndOmitEmptyOnes() {
		Map<String, Object> body = timeline(ANCHOR, ANCHOR.plus(1, ChronoUnit.HOURS));

		assertThat(body.get("bucket_seconds")).isEqualTo(60);
		List<Map<String, Object>> buckets = cast(body.get("buckets"));
		assertThat(buckets).hasSize(2);
		assertThat(buckets).allSatisfy(bucket -> assertThat(bucket).containsKeys("start", "counts"));
		assertThat(counts(buckets, ANCHOR)).isEqualTo(Map.of("info", 2));
		assertThat(counts(buckets, ANCHOR.plus(1, ChronoUnit.MINUTES))).isEqualTo(Map.of("error", 1));
	}

	/**
	 * The upper bound excludes its own instant. A record at exactly {@code to} showing
	 * up in the window means two adjacent brush selections both claim it.
	 */
	@Test
	void theWindowIsHalfOpen() {
		Instant secondBucket = ANCHOR.plus(1, ChronoUnit.MINUTES);

		List<Map<String, Object>> buckets = cast(timeline(ANCHOR, secondBucket).get("buckets"));

		assertThat(buckets).hasSize(1);
		assertThat(counts(buckets, ANCHOR)).isEqualTo(Map.of("info", 2));
	}

	/**
	 * A window that does not start on a bucket boundary is snapped down to one, and
	 * the snapped instant is what comes back.
	 *
	 * <p>This is the case every other test here was blind to, because every other
	 * fixture instant is already aligned. {@code date_bin} bins from a fixed origin,
	 * so with an unaligned {@code from} the client's
	 * {@code (bucket.start - from) / width} puts every bucket one index low and floors
	 * the first to {@code -1}, silently dropping it and shifting the chart a bar left
	 * — while a spot check of "are the counts right?" passes, because the counts are.
	 */
	@Test
	void anUnalignedWindowIsSnappedOntoTheBucketGrid() {
		// 30 seconds into the minute, with 1-minute buckets.
		Map<String, Object> body = timeline(ANCHOR.plusSeconds(30), ANCHOR.plus(1, ChronoUnit.HOURS));

		assertThat(body.get("from")).isEqualTo(ANCHOR.toString());
		List<Map<String, Object>> buckets = cast(body.get("buckets"));
		// The first bucket lands at index 0 off the reported `from`, not at -1.
		assertThat(counts(buckets, ANCHOR)).isEqualTo(Map.of("info", 2));
		assertThat(counts(buckets, ANCHOR.plus(1, ChronoUnit.MINUTES))).isEqualTo(Map.of("error", 1));
	}

	/**
	 * With no {@code from} — the range picker's "All time" — the server supplies the
	 * left edge and says which one it used, because the client has no way to know how
	 * far back this installation retains logs.
	 */
	@Test
	void anAbsentWindowIsResolvedAndEchoedBack() {
		Map<String, Object> body = timeline(null, null);

		Instant from = Instant.parse((String) body.get("from"));
		Instant to = Instant.parse((String) body.get("to"));
		assertThat(from).isBefore(ANCHOR);
		assertThat(to).isAfter(ANCHOR);
		assertThat((Integer) body.get("bucket_seconds")).isPositive();
		assertThat((List<?>) body.get("buckets")).isNotEmpty();
	}

	/** The chart carries the list's filters, so a filter that excludes everything empties it. */
	@Test
	void filtersApplyToTheChartAsTheyDoToTheList() {
		Map<String, Object> body = timeline(ANCHOR, ANCHOR.plus(1, ChronoUnit.HOURS), "&level=fatal");

		assertThat((List<?>) body.get("buckets")).isEmpty();
	}

	// ------------------------------------------------------------------ helpers

	private void seed(Instant timestamp, String level) {
		jdbc.sql("""
				INSERT INTO log_record (id, project_id, environment, "timestamp", level, body)
				VALUES (?, ?, 'prod', ?, ?, 'line')
				""")
			.param(UUID.randomUUID())
			.param(project)
			.param(java.sql.Timestamp.from(timestamp))
			.param(level)
			.update();
	}

	private Map<String, Object> timeline(Instant from, Instant to) {
		return timeline(from, to, "");
	}

	private Map<String, Object> timeline(Instant from, Instant to, String extra) {
		String window = (from == null ? "" : "&from=" + from) + (to == null ? "" : "&to=" + to);
		return getJson("/api/internal/logs/timeline?project=" + project + window + extra);
	}

	private Map<String, Integer> counts(List<Map<String, Object>> buckets, Instant start) {
		return buckets.stream()
			.filter(bucket -> Instant.parse((String) bucket.get("start")).equals(start))
			.map(bucket -> this.<Map<String, Integer>>cast(bucket.get("counts")))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no bucket at " + start + " in " + buckets));
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
