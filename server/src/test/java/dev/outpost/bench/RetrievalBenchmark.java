package dev.outpost.bench;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.outpost.db.PartitionManager;
import dev.outpost.query.QueryPlans;
import dev.outpost.support.PlanFacts;
import dev.outpost.support.TelemetrySeeder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Answers "how does the read path behave at production scale, and which query
 * gives out first". Opt-in: {@code ./gradlew retrievalBenchmark}. Excluded from
 * the {@code test} task, so it never runs in CI.
 *
 * <p>Every request goes over real HTTP through the session filter, the
 * controller, and Postgres — the whole thing a user waits for, not a query
 * measured in isolation.
 *
 * <h2>What it asserts, and what it only reports</h2>
 *
 * Wall-clock latency has no threshold here, or anywhere. What the run <em>does</em>
 * gate is its own validity, and it fails rather than reporting a fast number:
 * every response must be a 200, a page must be the size the endpoint promises, a
 * cursor must be well-formed where one is expected, and — for the paginated
 * scenarios — <b>no row id may repeat across adjacent pages</b>. A benchmark
 * measuring a query that returns page 1 fifty times is fast, worthless, and
 * silent about it. The pass/fail half that runs in CI lives in
 * {@code dev.outpost.query}'s guards and contains no wall clock at all.
 *
 * <h2>Deep pagination</h2>
 *
 * Cursors are <b>walked</b>, not synthesized: page by page, exactly as a user
 * gets there. It measures the true cost of getting deep, it is the journey that
 * actually happens, and it needs no production visibility widened to reach it.
 *
 * <h2>Scale</h2>
 *
 * {@code -Pbench.scale=0.1} for a fast smoke run. The scale factor moves row
 * counts only: cardinalities and the retention window are what make the dataset
 * production-shaped, and shrinking those alongside the volume would produce a
 * small dataset that is also the wrong shape.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(BenchContainerConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("benchmark")
@Tag("retrieval")
class RetrievalBenchmark {

	/**
	 * Reads are a latency-at-scale question, not a throughput one, so most
	 * scenarios sit at a rate the server can comfortably serve and the interesting
	 * number is what a single request costs.
	 */
	private static final LoadDriver.Step STEP = new LoadDriver.Step(20, Duration.ofSeconds(10));

	/**
	 * The one throughput question worth asking. {@code application.yaml} sets no
	 * HikariCP tuning, so the pool is Spring Boot's default 10 connections while a
	 * single issue-list request issues four queries. Where that becomes the wall is
	 * worth knowing.
	 */
	private static final int[] SATURATION_RATES = { 10, 25, 50, 100, 200 };

	private static final int ISSUE_PAGE_SIZE = 50;

	private static final int LOG_PAGE_SIZE = 100;

	private static final int TRACE_PAGE_SIZE = 50;

	/** Deep enough that O(offset) and O(page) are unmistakably different journeys. */
	private static final int DEEP_ISSUE_PAGE = 50;

	private static final int DEEP_LOG_PAGE = 50;

	private static final int DEEP_TRACE_PAGE = 20;

	/**
	 * A page whose size the endpoint does not promise. Page 1 of an unfiltered list
	 * is always full and asserting that catches a broken {@code LIMIT}; a filtered
	 * result legitimately returns whatever matched, so only "not empty" is
	 * meaningful there — and the row count is in the table either way.
	 */
	private static final int ANY_SIZE = -1;

	private static final RetrievalReport REPORT = new RetrievalReport("Outpost retrieval benchmark");

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	@Autowired
	ObjectMapper mapper;

	final LoadDriver driver = new LoadDriver();

	final HttpClient http = HttpClient.newHttpClient();

	TelemetrySeeder.Seeded seeded;

	String session;

	long datasetRows;

	/**
	 * Seeded once, for the whole class. The ingest benchmark clears telemetry
	 * between tests because each of its plateaus has to start from a drained queue;
	 * here the dataset <em>is</em> the fixture, and rebuilding it per test would
	 * spend the entire run seeding.
	 */
	@BeforeAll
	void seedOnce() {
		double scale = Double.parseDouble(System.getProperty("outpost.bench.scale", "1.0"));
		TelemetrySeeder.Scale target = TelemetrySeeder.Scale.DEFAULT.times(scale);
		Instant startedAt = Instant.now();
		seeded = new TelemetrySeeder(jdbc, partitions).seed(target);
		datasetRows = seeded.events() + seeded.logs() + seeded.txns() + seeded.spans();
		session = login();

		REPORT.condition("bench_scale", scale);
		REPORT.condition("dataset_rows", datasetRows);
		REPORT.condition("events", seeded.events());
		REPORT.condition("log_records", seeded.logs());
		REPORT.condition("transactions", seeded.txns());
		REPORT.condition("spans", seeded.spans());
		REPORT.condition("issues", seeded.issues());
		REPORT.condition("retention_window_days", target.windowDays());
		REPORT.condition("seed_seconds", Duration.between(startedAt, Instant.now()).toSeconds());
		seeded.settings().forEach(REPORT::condition);
	}

	@AfterAll
	void writeReport() {
		driver.close();
		http.close();
		REPORT.write();
	}

	// ------------------------------------------------------------------ issues

	@Test
	void issueList() throws Exception {
		measure("issues", "page 1", "/issues", "issues", ISSUE_PAGE_SIZE,
				QueryPlans.issueList(null, null, null, null, null, null, null, "last_seen", null));
		measure("issues", "sort=count", "/issues?sort=count", "issues", ISSUE_PAGE_SIZE,
				QueryPlans.issueList(null, null, null, null, null, null, null, "count", null));
		measure("issues", "query=", "/issues?query=order", "issues", ANY_SIZE,
				QueryPlans.issueList(null, null, null, null, null, null, "order", "last_seen", null));
		measure("issues", "release=", "/issues?release=" + seeded.release(), "issues", ANY_SIZE,
				QueryPlans.issueList(null, null, null, seeded.release(), null, null, null, "last_seen", null));
		measure("issues", "environment=", "/issues?environment=" + seeded.environment(), "issues", ANY_SIZE,
				QueryPlans.issueList(null, List.of(seeded.environment()), null, null, null, null, null, "last_seen",
						null));
		measure("issues", "project=", "/issues?project=" + seeded.projectId(), "issues", ANY_SIZE,
				QueryPlans.issueList(List.of(seeded.projectId()), null, null, null, null, null, null, "last_seen",
						null));
	}

	/** The direct test of {@code KeysetPage}'s O(page) claim against the missing sort indexes. */
	@Test
	void issueDeepPagination() throws Exception {
		Walk walk = walk("/issues", "issues", ISSUE_PAGE_SIZE, DEEP_ISSUE_PAGE);
		measure("issues", "page " + walk.depth(), "/issues?cursor=" + walk.cursor(), "issues", ISSUE_PAGE_SIZE,
				QueryPlans.issueList(null, null, null, null, null, null, null, "last_seen", walk.cursor()));
	}

	// -------------------------------------------------------------------- logs

	@Test
	void logStream() throws Exception {
		measure("logs", "page 1", "/logs", "logs", LOG_PAGE_SIZE,
				QueryPlans.logs(null, null, null, null, null, null, null, null, null, null));
		measure("logs", "query=", "/logs?query=" + encode(seeded.bodyNeedle()), "logs", ANY_SIZE,
				QueryPlans.logs(null, null, null, null, null, seeded.bodyNeedle(), null, null, null, null));
		String attr = seeded.attributeKey() + "=" + seeded.attributeValue();
		measure("logs", "attr=", "/logs?attr=" + encode(attr), "logs", ANY_SIZE,
				QueryPlans.logs(null, null, null, null, null, null, List.of(attr), null, null, null));
		measure("logs", "trace_id=", "/logs?trace_id=" + seeded.traceId(), "logs", ANY_SIZE,
				QueryPlans.logs(null, null, null, seeded.traceId(), null, null, null, null, null, null));
	}

	@Test
	void logDeepPagination() throws Exception {
		Walk walk = walk("/logs", "logs", LOG_PAGE_SIZE, DEEP_LOG_PAGE);
		measure("logs", "page " + walk.depth(), "/logs?cursor=" + walk.cursor(), "logs", LOG_PAGE_SIZE,
				QueryPlans.logs(null, null, null, null, null, null, null, null, null, walk.cursor()));
	}

	// ------------------------------------------------------------------ traces

	@Test
	void traceSearch() throws Exception {
		measure("traces", "page 1", "/traces", "traces", TRACE_PAGE_SIZE,
				QueryPlans.traceSearch(null, null, null, null, null, null, null, null, null, null));
		measure("traces", "has_errors=true", "/traces?has_errors=true", "traces", ANY_SIZE,
				QueryPlans.traceSearch(null, null, null, null, null, null, true, null, null, null));
	}

	@Test
	void traceDeepPagination() throws Exception {
		Walk walk = walk("/traces", "traces", TRACE_PAGE_SIZE, DEEP_TRACE_PAGE);
		measure("traces", "page " + walk.depth(), "/traces?cursor=" + walk.cursor(), "traces", TRACE_PAGE_SIZE,
				QueryPlans.traceSearch(null, null, null, null, null, null, null, null, null, walk.cursor()));
	}

	/** The four-table fan-out by {@code trace_id}, with no time predicate on any of them. */
	@Test
	void traceDetail() throws Exception {
		PlanFacts fanOut = PlanFacts.NONE;
		for (String table : List.of("txn", "span", "event", "log_record")) {
			fanOut = fanOut.merge(PlanFacts.explain(jdbc,
					"SELECT * FROM " + table + " WHERE trace_id = ?", List.of(seeded.traceId())));
		}
		measure("trace detail", "4-table fan-out", "/traces/" + seeded.traceId(), "transactions", ANY_SIZE, fanOut);
	}

	// ------------------------------------------------------------------- pages

	@Test
	void otherPages() throws Exception {
		measure("releases", "list", "/releases?project=" + seeded.projectId(), null, ANY_SIZE,
				QueryPlans.releaseList(seeded.projectId()));
		measure("uptime", "overview", "/uptime/overview", null, ANY_SIZE, PlanFacts.NONE);
		measure("event detail", "+ 2 neighbours", "/events/" + seeded.eventId(), null, ANY_SIZE,
				new QueryPlans.Built("SELECT * FROM event WHERE id = ?", List.of(seeded.eventId())));
	}

	// -------------------------------------------------------------- saturation

	/**
	 * The one throughput ladder. Each issue-list request issues four queries against
	 * a ten-connection pool, so the knee here is about the pool and the aggregates
	 * behind it rather than about any single query.
	 */
	@Test
	void issueListSaturationLadder() throws Exception {
		QueryPlans.Built plan = QueryPlans.issueList(null, null, null, null, null, null, null, "last_seen", null);
		for (int rate : SATURATION_RATES) {
			LoadDriver.Result result = driver.run(new LoadDriver.Step(rate, Duration.ofSeconds(10)),
					() -> request("/issues"));
			// Reported, not asserted: past the knee a saturated step is the finding, and
			// failing the run on it would delete the answer.
			System.out.printf("issue list @ %4d/s → p99 %.1f ms, %d non-200%n", rate, result.p99Millis(),
					result.offered() - result.status(200));
			REPORT.add(new RetrievalReport.Row("issues saturation", rate + "/s", result, datasetRows, ISSUE_PAGE_SIZE,
					plan.explain(jdbc)));
		}
	}

	// ----------------------------------------------------------------- harness

	private record Walk(String cursor, int depth) {
	}

	/**
	 * Validates the scenario, times it, and pairs the timing with the plan facts of
	 * the same query. Validation runs first: a scenario that is not returning what
	 * it claims should fail before it produces a number somebody quotes.
	 */
	private void measure(String scenario, String step, String path, String listKey, int expectedRows,
			QueryPlans.Built plan) throws Exception {
		measure(scenario, step, path, listKey, expectedRows, plan.explain(jdbc));
	}

	private void measure(String scenario, String step, String path, String listKey, int expectedRows, PlanFacts plan)
			throws Exception {
		long rows = validate(scenario + " " + step, path, listKey, expectedRows);
		LoadDriver.Result result = driver.run(STEP, () -> request(path));

		assertThat(result.failures()).as("driver/socket failures during %s %s", scenario, step).isZero();
		assertThat(result.shed()).as("requests shed by the load driver during %s %s", scenario, step).isZero();
		assertThat(result.offered() - result.status(200)).as("non-200 responses during %s %s", scenario, step).isZero();

		System.out.printf("%-14s %-18s p50 %6.1f ms  p99 %6.1f ms  %5d rows  %8d blocks%n", scenario, step,
				result.p50Millis(), result.p99Millis(), rows, plan.logicalIo());
		REPORT.add(new RetrievalReport.Row(scenario, step, result, datasetRows, rows, plan));
	}

	/** @return the number of rows the response actually carried */
	private long validate(String what, String path, String listKey, int expectedRows) throws Exception {
		HttpResponse<String> response = http.send(request(path), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("status for %s", what).isEqualTo(200);

		JsonNode body = mapper.readTree(response.body());
		if (listKey == null) {
			return body.isArray() ? body.size() : 1;
		}
		JsonNode list = body.get(listKey);
		assertThat(list).as("`%s` in the response for %s", listKey, what).isNotNull();
		assertThat(list.size()).as("rows returned by %s — an empty page is fast and meaningless", what).isPositive();
		if (expectedRows != ANY_SIZE) {
			assertThat(list.size()).as("page size for %s", what).isEqualTo(expectedRows);
		}
		return list.size();
	}

	/**
	 * Pages forward to {@code target}, checking as it goes that adjacent pages share
	 * no row id. Stops early when the data runs out and reports the depth it
	 * reached, so a smoke run at {@code -Pbench.scale=0.1} measures a genuinely deep
	 * page for its dataset rather than failing or silently measuring page 3.
	 */
	private Walk walk(String path, String listKey, int pageSize, int target) throws Exception {
		String cursor = null;
		List<String> previousIds = List.of();
		int depth = 1;
		for (; depth < target; depth++) {
			String url = cursor == null ? path : path + "?cursor=" + cursor;
			HttpResponse<String> response = http.send(request(url), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).as("status walking %s to page %d", path, depth).isEqualTo(200);

			JsonNode body = mapper.readTree(response.body());
			List<String> ids = idsOf(body.get(listKey));
			PageWalk walk = PageWalk.inspect(previousIds, ids);
			assertThat(walk.advanced()).as("page %d of %s: %s", depth, path, walk.describe()).isTrue();

			JsonNode next = body.get("next_cursor");
			if (next == null || next.isNull()) {
				break;
			}
			cursor = next.asString();
			previousIds = ids;
		}
		// Two is the floor at which this measured anything: the scenario is not page 1,
		// and the overlap comparison above ran at least once. A -Pbench.scale smoke run
		// legitimately lands here; a full run reaches `target`, and the step label in
		// the report says which, so a shallow walk cannot be mistaken for a deep one.
		assertThat(depth).as("pages available under %s — a deep-pagination scenario needs somewhere to go", path)
			.isGreaterThanOrEqualTo(2);
		if (depth < target) {
			System.out.printf("note: %s ran out of rows at page %d of a requested %d%n", path, depth, target);
		}
		System.out.printf("walked %s to page %d (page size %d)%n", path, depth, pageSize);
		return new Walk(cursor, depth);
	}

	private static List<String> idsOf(JsonNode list) {
		List<String> ids = new ArrayList<>();
		for (JsonNode row : list) {
			ids.add(row.get("id").asString());
		}
		return ids;
	}

	// -------------------------------------------------------------------- http

	private HttpRequest request(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/internal" + path))
			.timeout(LoadDriver.REQUEST_TIMEOUT)
			.header("Cookie", session)
			.GET()
			.build();
	}

	private String login() {
		try {
			HttpResponse<String> response = http.send(
					HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/internal/auth/login"))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers
							.ofString("{\"email\":\"admin@test.local\",\"password\":\"test-password\"}"))
						.build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).as("login").isEqualTo(200);
			String cookie = response.headers().firstValue("set-cookie").orElseThrow();
			return cookie.split(";")[0];
		}
		catch (Exception e) {
			throw new IllegalStateException("could not authenticate the benchmark", e);
		}
	}

	private static String encode(String value) {
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
	}

}
