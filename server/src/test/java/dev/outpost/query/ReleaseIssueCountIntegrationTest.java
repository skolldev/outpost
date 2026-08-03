package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.ingest.IngestItem;
import dev.outpost.pipeline.ErrorPipeline;
import dev.outpost.pipeline.EventStore;
import dev.outpost.pipeline.ProcessedEvent;
import dev.outpost.retention.DataRetentionService;
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
import tools.jackson.databind.ObjectMapper;

/**
 * What the Releases page's {@code issue_count} is worth, as opposed to what it
 * costs. #130 moved the count off {@code event} and onto the
 * {@code issue_release_stats} rollup, and the whole risk of that trade is that a
 * rollup can be fast and wrong: {@link ReleaseQueryPerformanceTest} only
 * {@code EXPLAIN}s the SQL, and a plan that returns a stale, doubled or empty
 * number explains exactly as well as a correct one.
 *
 * <p>So every case where "distinct Issues on this Release" and "rows somebody
 * incremented" could come apart has a test here: an Issue seen many times on one
 * Release, a Release nothing has ever hit, a redelivered Event, two Projects
 * shipping the same version string, and an Event ageing out from under a rollup
 * row whose Issue survives.
 *
 * <p>Events are stored through {@link EventStore} rather than posted as
 * envelopes. Whether two Events land in one worker batch is a timing accident;
 * none of the invariants below is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class ReleaseIssueCountIntegrationTest {

	private static final String RELEASE = "shop@1.0.0";

	private static final String OTHER_RELEASE = "shop@1.1.0";

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper mapper;

	@Autowired
	ErrorPipeline pipeline;

	@Autowired
	EventStore store;

	@Autowired
	DataRetentionService retention;

	final RestTemplate rest = new RestTemplate();

	long projectId;
	long otherProjectId;
	String sessionCookie;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM event").update();
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM project").update();
		projectId = newProject("shop");
		otherProjectId = newProject("warehouse");
		sessionCookie = login();
	}

	@Test
	void anIssueSeenManyTimesOnOneReleaseCountsOnce() {
		store(projectId, "boom", RELEASE);
		store(projectId, "boom", RELEASE);
		store(projectId, "boom", RELEASE);

		assertThat(issueCounts(projectId)).containsExactly(Map.entry(RELEASE, 1L));
	}

	@Test
	void everyIssueOnAReleaseIsCountedOnce() {
		store(projectId, "boom", RELEASE);
		store(projectId, "boom", RELEASE);
		store(projectId, "crash", RELEASE);
		store(projectId, "wedged", OTHER_RELEASE);

		assertThat(issueCounts(projectId)).containsOnly(Map.entry(RELEASE, 2L), Map.entry(OTHER_RELEASE, 1L));
	}

	/**
	 * A Release created ahead of its deploy — {@code sentry-cli releases new}, or an
	 * artifact upload — has no Events yet, and must read as zero rather than being
	 * dropped from the page. The {@code LEFT JOIN} is what makes that so, and an
	 * inner join would silently hide the Release instead.
	 */
	@Test
	void aReleaseNothingHasHitCountsZero() {
		store(projectId, "boom", RELEASE);
		jdbc.sql("INSERT INTO release (project_id, version) VALUES (?, ?)")
			.param(projectId)
			.param("shop@2.0.0")
			.update();

		assertThat(issueCounts(projectId)).containsOnly(Map.entry(RELEASE, 1L), Map.entry("shop@2.0.0", 0L));
	}

	/**
	 * An SDK retrying a failed request sends the same {@code event_id} again. The
	 * second copy stores no Event, so it must not add a rollup row either — and
	 * because the count is now {@code count(*)} over those rows rather than
	 * {@code count(DISTINCT issue_id)} over Events, a rollup that gained a duplicate
	 * row per redelivery would report it.
	 */
	@Test
	void aRedeliveredEventDoesNotInflateTheCount() {
		ProcessedEvent event = process(projectId, "boom", RELEASE, Instant.now().minusSeconds(60));
		store.store(List.of(event));
		store.store(List.of(event));
		store.store(List.of(event, event));

		assertThat(eventRows()).isEqualTo(1);
		assertThat(issueCounts(projectId)).containsExactly(Map.entry(RELEASE, 1L));
	}

	/**
	 * Release versions are not unique across Projects — two services deployed from
	 * the same tag carry the same string — so a count that matched on version alone
	 * would report each Project the other's Issues.
	 */
	@Test
	void countsAreScopedToTheProjectThatAsked() {
		store(projectId, "boom", RELEASE);
		store(projectId, "crash", RELEASE);
		store(otherProjectId, "stuck", RELEASE);

		assertThat(issueCounts(projectId)).containsExactly(Map.entry(RELEASE, 2L));
		assertThat(issueCounts(otherProjectId)).containsExactly(Map.entry(RELEASE, 1L));
	}

	/**
	 * Retention deletes Events, and the count has to follow them down. The Issue
	 * here <em>survives</em> the sweep — it still has a recent Event on
	 * {@link #OTHER_RELEASE} — so its rollup rows are rebuilt rather than cascaded
	 * away with it, which is the path that can leave a stale row behind.
	 */
	@Test
	void aReleaseWhoseEventsExpiredCountsZeroAgain() {
		Instant old = Instant.now().minus(20, ChronoUnit.DAYS);
		store.store(List.of(process(projectId, "boom", RELEASE, old)));
		store(projectId, "boom", OTHER_RELEASE);
		assertThat(issueCounts(projectId)).containsOnly(Map.entry(RELEASE, 1L), Map.entry(OTHER_RELEASE, 1L));

		retention.cleanup(Instant.now().minus(10, ChronoUnit.DAYS));

		assertThat(jdbc.sql("SELECT count(*) FROM issue").query(Long.class).single())
			.as("the issue outlives the sweep — its recent event is still there")
			.isEqualTo(1);
		assertThat(issueCounts(projectId)).containsOnly(Map.entry(RELEASE, 0L), Map.entry(OTHER_RELEASE, 1L));
	}

	/**
	 * {@code "release":""} is not a Release. Nothing else in the product treats it as
	 * one — the rollup skips it, the issue-list filter rejects it — so listing it
	 * would put a nameless row on the page that could only ever show zero Issues.
	 */
	@Test
	void aBlankReleaseIsNotListed() {
		store(projectId, "boom", "");
		store(projectId, "boom", RELEASE);

		assertThat(eventRows()).isEqualTo(2);
		assertThat(issueCounts(projectId)).containsExactly(Map.entry(RELEASE, 1L));
	}

	// ------------------------------------------------------------------ helpers

	/** The page as the UI reads it: version to {@code issue_count}, newest first. */
	private List<Map.Entry<String, Long>> issueCounts(long project) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, sessionCookie);
		ResponseEntity<List> response = rest.exchange(url("/api/internal/releases?project=" + project), HttpMethod.GET,
				new HttpEntity<>(headers), List.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<Map<String, Object>> rows = cast(response.getBody());
		return rows.stream()
			.map(row -> Map.entry((String) row.get("version"), ((Number) row.get("issue_count")).longValue()))
			.toList();
	}

	private void store(long project, String exceptionType, String release) {
		store.store(List.of(process(project, exceptionType, release, Instant.now().minusSeconds(60))));
	}

	/**
	 * One Event with a fresh {@code event_id}, so repeats of the same fingerprint are
	 * distinct deliveries rather than the redelivery {@link #aRedeliveredEventDoesNotInflateTheCount}
	 * is about.
	 */
	private ProcessedEvent process(long project, String exceptionType, String release, Instant timestamp) {
		String json = """
				{"event_id":"%s","timestamp":"%s","platform":"javascript","level":"error",\
				"environment":"production","release":"%s",\
				"exception":{"values":[{"type":"%s","value":"boom","stacktrace":{"frames":[\
				{"filename":"http://localhost:4200/main.js","function":"handleClick","in_app":true,"lineno":13}\
				]}}]}}""".formatted(UUID.randomUUID().toString().replace("-", ""), timestamp, release, exceptionType);
		return pipeline.process(new IngestItem.ErrorEvent(project, Instant.now(), mapper.readTree(json), List.of()));
	}

	private long newProject(String slug) {
		return jdbc.sql("INSERT INTO project (slug, name) VALUES (?, ?) RETURNING id")
			.param(slug)
			.param(slug)
			.query(Long.class)
			.single();
	}

	private long eventRows() {
		return jdbc.sql("SELECT count(*) FROM event").query(Long.class).single();
	}

	private String login() {
		ResponseEntity<Map> response = rest.postForEntity(url("/api/internal/auth/login"),
				Map.of("email", "admin@test.local", "password", "test-password"), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).isNotNull();
		return setCookie.split(";")[0];
	}

	@SuppressWarnings("unchecked")
	private <T> T cast(Object value) {
		return (T) value;
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

}
