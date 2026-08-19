package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import java.time.Duration;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * {@code GET /transaction-groups} end to end (#159): the Performance leaderboard's
 * wire shape, its statistics against a hand-computed fixture, and the two rules
 * the client cannot enforce for itself — that (Project, name, op) is the whole
 * grouping key, and that the window is capped at 30 days and says so when it bites.
 *
 * <p>The durations are chosen so every percentile is exact rather than
 * approximately right. {@code percentile_cont} interpolates: over ten values
 * 100…1000 the p95 sits 55% of the way between the ninth and tenth, which is 955
 * and nothing else. A fixture of round numbers would have made 900 and 1000 both
 * look plausible, and a percentile guard that cannot distinguish
 * {@code percentile_cont} from {@code percentile_disc} is not guarding much.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class TransactionGroupIntegrationTest {

	private static final String GROUPS = "/api/internal/transaction-groups";

	private static final String CHECKOUT = "GET /api/checkout/{id}";

	/** Percentiles are interpolated in {@code double}, so an exact equality would flake on the last bit. */
	private static final org.assertj.core.data.Offset<Double> TOLERANCE = within(1e-6);

	/**
	 * Comfortably inside the 30-day cap, so an unclamped request covers the fixture.
	 * Truncated to the second because two tests round-trip it through a query param
	 * and back out of the response, and sub-second precision only invites a
	 * formatting difference to fail an assertion that is not about formatting.
	 */
	private static final Instant ANCHOR = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	final RestTemplate rest = new RestTemplate();

	long project;

	long otherProject;

	String adminCookie;

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM txn").update();
		jdbc.sql("DELETE FROM project").update();
		jdbc.sql("DELETE FROM app_user WHERE email <> 'admin@test.local'").update();
		project = jdbc.sql("INSERT INTO project (slug, name) VALUES ('shop', 'Shop') RETURNING id")
			.query(Long.class)
			.single();
		otherProject = jdbc.sql("INSERT INTO project (slug, name) VALUES ('admin-app', 'Admin') RETURNING id")
			.query(Long.class)
			.single();
		partitions.ensurePartition(PartitionManager.TXN, ANCHOR);

		// Group A — ten Transactions, 100…1000 ms. Every statistic below is read off
		// this list by hand: total 5500, avg 550, max 1000, p50 550, p95 955, p99 991.
		for (int i = 1; i <= 10; i++) {
			seed(project, CHECKOUT, "http.server", "production", i * 100.0);
		}
		// Group B — the same name under a different op. Separate group, by the key.
		for (int i = 0; i < 4; i++) {
			seed(project, CHECKOUT, "navigation", "production", 50.0);
		}
		// Group C — no op at all, which is a legitimate group rather than an absence.
		seed(project, "GET /api/cart", null, "production", 10.0);
		seed(project, "GET /api/cart", null, "production", 20.0);
		seed(project, "GET /api/cart", null, "production", 30.0);
		// Elsewhere: another Project, and another Environment Name in this one.
		seed(otherProject, "GET /admin/users", "http.server", "production", 4000.0);
		seed(project, "GET /api/staging-only", "http.server", "staging", 3000.0);

		adminCookie = login("admin@test.local", "test-password");
	}

	/**
	 * The whole grouping contract in one assertion: Transactions collapse by
	 * (Project, name, op), a differing op splits a name into two groups, a null op is
	 * a group of its own, and the list is ranked by total time descending with no
	 * sort asked for.
	 */
	@Test
	void transactionsCollapseIntoGroupsRankedByTotalTime() {
		List<Map<String, Object>> groups = groups("&project=" + project + "&environment=production");

		assertThat(groups).extracting(g -> g.get("name") + " / " + g.get("op"))
			.containsExactly(CHECKOUT + " / http.server", CHECKOUT + " / navigation", "GET /api/cart / null");
	}

	/** Every statistic on the wire, against the hand-computed fixture. */
	@Test
	void eachGroupCarriesItsDurationStatistics() {
		Map<String, Object> checkout = groups("&project=" + project + "&environment=production").get(0);

		assertThat(checkout).containsEntry("project_id", (int) project)
			.containsEntry("count", 10)
			.containsEntry("total_ms", 5500.0)
			.containsEntry("avg_ms", 550.0)
			.containsEntry("max_ms", 1000.0);
		// Interpolated, so compared within a tolerance: p95 of ten values lands 55% of
		// the way from 900 to 1000, and the double that comes back is 954.999…
		assertThat(ms(checkout, "p50_ms")).isCloseTo(550.0, TOLERANCE);
		assertThat(ms(checkout, "p95_ms")).isCloseTo(955.0, TOLERANCE);
		assertThat(ms(checkout, "p99_ms")).isCloseTo(991.0, TOLERANCE);
		// `min` measures cache hits and is deliberately not offered.
		assertThat(checkout).doesNotContainKey("min_ms");
	}

	/** A group whose op is null reaches the wire with a null op, not a dropped row. */
	@Test
	void groupsWithNoOpAreReturned() {
		Map<String, Object> cart = groups("&project=" + project + "&environment=production").get(2);

		assertThat(cart).containsEntry("op", null).containsEntry("count", 3).containsEntry("total_ms", 60.0);
	}

	@Test
	void theProjectFilterNarrowsTheList() {
		assertThat(groups("&project=" + otherProject)).extracting(g -> g.get("name"))
			.containsExactly("GET /admin/users");
	}

	@Test
	void theEnvironmentFilterNarrowsTheList() {
		assertThat(groups("&project=" + project + "&environment=staging")).extracting(g -> g.get("name"))
			.containsExactly("GET /api/staging-only");
	}

	/**
	 * "All time" is what the global range picker offers and what this endpoint
	 * cannot answer (ADR-0015). It is clamped to 30 days, and the response says
	 * which window it used and that it narrowed one — silent clamping is the shape
	 * that produces "the numbers are wrong" reports.
	 */
	@Test
	void anAllTimeRequestIsClampedToThirtyDaysAndSaysSo() {
		Map<String, Object> body = leaderboard("&project=" + project);

		assertThat(body.get("range_clamped")).isEqualTo(true);
		assertThat(window(body)).isEqualTo(30 * 24 * 60);
	}

	/** And so is an explicit window wider than the cap — including the rows outside it. */
	@Test
	void aWindowWiderThanTheCapIsClampedAndExcludesWhatFallsOutsideIt() {
		Instant old = ANCHOR.minus(60, ChronoUnit.DAYS);
		partitions.ensurePartition(PartitionManager.TXN, old);
		jdbc.sql("""
				INSERT INTO txn (id, project_id, environment, trace_id, span_id, name, op, start_ts, end_ts,
				                 duration_ms, status)
				VALUES (?, ?, 'production', 'trace', 'span', 'GET /api/ancient', 'http.server', ?, ?, 9999, 'ok')
				""")
			.param(UUID.randomUUID())
			.param(project)
			.param(java.sql.Timestamp.from(old))
			.param(java.sql.Timestamp.from(old.plusMillis(9999)))
			.update();

		Map<String, Object> body = leaderboard("&project=" + project + "&from=" + ANCHOR.minus(90, ChronoUnit.DAYS));

		assertThat(body.get("range_clamped")).isEqualTo(true);
		assertThat(window(body)).isEqualTo(30 * 24 * 60);
		// 9999 ms would top a total-time ranking if the clamp had not excluded it.
		assertThat(names(body)).doesNotContain("GET /api/ancient");
	}

	/** Even a small overshoot is clamped: the effective window never exceeds the documented cap. */
	@Test
	void aWindowSlightlyWiderThanThirtyDaysIsClamped() {
		Instant to = ANCHOR.plus(1, ChronoUnit.DAYS);
		Instant from = to.minus(30, ChronoUnit.DAYS).minusSeconds(1);

		Map<String, Object> body = leaderboard("&project=" + project + "&from=" + from + "&to=" + to);

		assertThat(body.get("range_clamped")).isEqualTo(true);
		assertThat(window(body)).isEqualTo(30 * 24 * 60);
	}

	/** A window inside the cap is answered as asked, with no notice raised. */
	@Test
	void aWindowInsideTheCapIsNotClamped() {
		Instant from = ANCHOR.minus(1, ChronoUnit.DAYS);
		Instant to = ANCHOR.plus(1, ChronoUnit.DAYS);

		Map<String, Object> body = leaderboard("&project=" + project + "&from=" + from + "&to=" + to);

		assertThat(body.get("range_clamped")).isEqualTo(false);
		assertThat(body.get("from")).isEqualTo(from.toString());
		assertThat(body.get("to")).isEqualTo(to.toString());
	}

	@Test
	void anInvalidWindowIsRejected() {
		ResponseEntity<Map> response = get(
				GROUPS + "?project=" + project + "&from=" + ANCHOR + "&to=" + ANCHOR, adminCookie);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).containsEntry("detail", "from must be before to");
	}

	/**
	 * The list stops at 100 groups and offers no cursor — an aggregate has no key to
	 * seek on (ADR-0015), so "there is no next page" is part of the contract rather
	 * than an omission.
	 */
	@Test
	void theListStopsAtOneHundredGroupsAndOffersNoCursor() {
		for (int i = 0; i < 120; i++) {
			seed(project, "GET /api/wide/" + i, "http.server", "production", 2000.0);
		}

		Map<String, Object> body = leaderboard("&project=" + project);

		assertThat(names(body)).hasSize(100);
		assertThat(body).doesNotContainKey("next_cursor");
	}

	/** Members inspect telemetry — the Performance view is not Admin-only. */
	@Test
	void aMemberCanReadTheLeaderboard() {
		createUser("member@test.local", "member-password", "member");
		String memberCookie = login("member@test.local", "member-password");

		ResponseEntity<Map> response = get(GROUPS + "?project=" + project, memberCookie);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsKey("groups");
	}

	@Test
	void theLeaderboardRequiresASession() {
		assertThat(rest.getForEntity(url(GROUPS), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ------------------------------------------------------------------ helpers

	private void seed(long projectId, String name, String op, String environment, double durationMs) {
		Instant start = ANCHOR;
		jdbc.sql("""
				INSERT INTO txn (id, project_id, environment, trace_id, span_id, name, op, start_ts, end_ts,
				                 duration_ms, status)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ok')
				""")
			.param(UUID.randomUUID())
			.param(projectId)
			.param(environment)
			.param(UUID.randomUUID().toString().replace("-", ""))
			.param(UUID.randomUUID().toString().replace("-", "").substring(0, 16))
			.param(name)
			.param(op)
			.param(java.sql.Timestamp.from(start))
			.param(java.sql.Timestamp.from(start.plusMillis((long) durationMs)))
			.param(durationMs)
			.update();
	}

	private double ms(Map<String, Object> group, String field) {
		return ((Number) group.get(field)).doubleValue();
	}

	private List<Map<String, Object>> groups(String filter) {
		return cast(leaderboard(filter).get("groups"));
	}

	private Map<String, Object> leaderboard(String filter) {
		ResponseEntity<Map> response = get(GROUPS + "?" + filter.replaceFirst("^&", ""), adminCookie);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return cast(response.getBody());
	}

	private List<String> names(Map<String, Object> body) {
		return this.<List<Map<String, Object>>>cast(body.get("groups")).stream().map(g -> (String) g.get("name")).toList();
	}

	/** The reported window, in whole minutes — the unit the 30-day cap is stated in. */
	private long window(Map<String, Object> body) {
		Instant from = Instant.parse((String) body.get("from"));
		Instant to = Instant.parse((String) body.get("to"));
		return Duration.between(from, to).toMinutes();
	}

	private void createUser(String email, String password, String role) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set(HttpHeaders.COOKIE, adminCookie);
		ResponseEntity<Map> response = rest.exchange(url("/api/internal/users"), HttpMethod.POST,
				new HttpEntity<>(Map.of("email", email, "password", password, "role", role), headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private ResponseEntity<Map> get(String path, String cookie) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, cookie);
		return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
	}

	private String login(String email, String password) {
		ResponseEntity<Map> response = rest.postForEntity(url("/api/internal/auth/login"),
				Map.of("email", email, "password", password), Map.class);
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
