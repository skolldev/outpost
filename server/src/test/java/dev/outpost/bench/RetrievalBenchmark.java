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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("benchmark")
@Tag("retrieval")
class RetrievalBenchmark {

	/**
	 * Reads are a latency-at-scale question, not a throughput one, so most scenarios
	 * sit at a rate the server can comfortably serve and the interesting number is
	 * what a single request costs. The saturation ladder is the one place that asks
	 * the other question.
	 */
	private static final Duration STEP_DURATION = Duration.ofSeconds(10);

	private static final int MAX_SCENARIO_RATE = 20;

	/**
	 * Requests a scenario may have outstanding at once. The rate is derived from
	 * this and the endpoint's own measured latency rather than fixed, because a
	 * fixed rate is only safe for endpoints that keep up with it: the releases page
	 * takes seconds at full scale, and offering it 20/s open-loop would bury it
	 * under a backlog, blow the driver's request timeout, and fail the run for a
	 * reason that has nothing to do with the query. The point is to measure what one
	 * request costs, and a small stable concurrency does that at any speed.
	 */
	private static final double TARGET_CONCURRENCY = 4;

	/** Below this a percentile is noise, so a slow scenario buys samples with wall clock. */
	private static final int MIN_SAMPLES = 20;

	/**
	 * An endpoint slower than half the driver's per-request timeout cannot be driven
	 * at all: every request in the step would time out and the run would fail on an
	 * endpoint being slow — deleting the whole report to tell us something the
	 * report was going to say anyway. Those are measured once and reported as a
	 * single sample, loudly marked.
	 */
	private static final long DRIVABLE_MILLIS = LoadDriver.REQUEST_TIMEOUT.toMillis() / 2;

	/** Long enough that an endpoint's own slowness is reported rather than thrown. */
	private static final Duration PROBE_TIMEOUT = Duration.ofMinutes(3);

	/** How long to let the server work off a saturated step before giving up and saying so. */
	private static final Duration QUIESCE_TIMEOUT = Duration.ofMinutes(3);

	/**
	 * The one throughput question worth asking, as multiples of what a single
	 * connection sustains. {@code application.yaml} sets no HikariCP tuning, so the
	 * pool is Spring Boot's default 10 connections while one issue-list request
	 * issues four queries; the knee should land inside this ladder whatever the
	 * dataset.
	 *
	 * <p>Multiples rather than absolute rates because the endpoint's own speed moves
	 * by an order of magnitude with the dataset. A ladder fixed at rates calibrated
	 * against a tenth-scale run offers a full-scale server a hundred times its
	 * capacity, which does not find a knee — it builds a backlog that outlives the
	 * step and contaminates whatever runs next.
	 */
	private static final int[] SATURATION_MULTIPLES = { 1, 2, 4, 8, 16 };

	private static final int ISSUE_PAGE_SIZE = 50;

	private static final int LOG_PAGE_SIZE = 100;

	private static final int TRACE_PAGE_SIZE = 50;

	/** Deep enough that O(offset) and O(page) are unmistakably different journeys. */
	private static final int DEEP_ISSUE_PAGE = 50;

	private static final int DEEP_LOG_PAGE = 50;

	private static final int DEEP_TRACE_PAGE = 20;

	/** The range picker's default, from {@code ui/src/app/core/filters.ts}. */
	private static final int UI_WINDOW_DAYS = 14;

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
	@Order(1)
	void issueList() throws Exception {
		measure("issues", "page 1", "/issues", "issues", ISSUE_PAGE_SIZE,
				issuePagePlan(QueryPlans.issueList(null, null, null, null, null, null, null, "last_seen", null)));
		measure("issues", "sort=count", "/issues?sort=count", "issues", ISSUE_PAGE_SIZE,
				issuePagePlan(QueryPlans.issueList(null, null, null, null, null, null, null, "count", null)));
		measure("issues", "query=", "/issues?query=order", "issues", ANY_SIZE,
				issuePagePlan(QueryPlans.issueList(null, null, null, null, null, null, "order", "last_seen", null)));
		measure("issues", "release=", "/issues?release=" + seeded.release(), "issues", ANY_SIZE, issuePagePlan(
				QueryPlans.issueList(null, null, null, seeded.release(), null, null, null, "last_seen", null)));
		measure("issues", "environment=", "/issues?environment=" + seeded.environment(), "issues", ANY_SIZE,
				issuePagePlan(QueryPlans.issueList(null, List.of(seeded.environment()), null, null, null, null, null,
						"last_seen", null)));
		measure("issues", "project=", "/issues?project=" + seeded.projectId(), "issues", ANY_SIZE,
				issuePagePlan(QueryPlans.issueList(List.of(seeded.projectId()), null, null, null, null, null, null,
						"last_seen", null)));
	}

	/**
	 * An issue-list page load is four queries, not one: the list, a 14-day
	 * sparkline, a distinct-user count, and the environment rollup. Reporting the
	 * list query's plan next to the whole page's latency would put 558 blocks beside
	 * half a second and make the machine look slow — the aggregates are where the
	 * time goes, and the columns have to say so.
	 *
	 * <p>The ids come from {@code list} itself, not from an unfiltered stand-in: a
	 * filtered scenario returns different issues, and pairing its latency with the
	 * aggregate cost of somebody else's page would be a plan for a query the run
	 * never made. The window comes from the controller for the same reason — see
	 * {@link QueryPlans#sparklineSince()}.
	 */
	private PlanFacts issuePagePlan(QueryPlans.Built list) {
		List<Long> ids = QueryPlans.issueIdsOnPage(jdbc, list);
		// The aggregates take an IN list, so an empty page has no plan to report — and
		// a scenario matching no issues is not measuring anything either way.
		assertThat(ids).as("issues matched by the scenario's own list query").isNotEmpty();
		return sum(List.of(list, QueryPlans.sparkline(ids, QueryPlans.sparklineSince()), QueryPlans.usersAffected(ids),
				QueryPlans.environmentRollup(ids)));
	}

	/** The direct test of {@code KeysetPage}'s O(page) claim against the missing sort indexes. */
	@Test
	@Order(2)
	void issueDeepPagination() throws Exception {
		CursorWalk walk = walk("/issues", "issues", DEEP_ISSUE_PAGE);
		measure("issues", "page " + walk.depth(), "/issues?cursor=" + walk.cursor(), "issues",
				pageSizeReached(walk, ISSUE_PAGE_SIZE), issuePagePlan(
						QueryPlans.issueList(null, null, null, null, null, null, null, "last_seen", walk.cursor())));
	}

	// -------------------------------------------------------------------- logs

	/**
	 * The unfiltered rows below are the "All time" end of the range picker, not the
	 * default. #128's acceptance was explicit that a fix must be measured at the
	 * shapes the UI actually sends, so the default 14-day window and its
	 * project-scoped variant are measured at benchmark scale too — the guard tier
	 * pins their plan shape, but only this tier has the rows to price them.
	 */
	@Test
	void logStream() throws Exception {
		measure("logs", "page 1", "/logs", "logs", LOG_PAGE_SIZE,
				QueryPlans.logs(null, null, null, null, null, null, null, null, null, null));
		Instant from = uiWindowStart();
		measure("logs", "page 1, 14d", "/logs?from=" + encode(from.toString()), "logs", LOG_PAGE_SIZE,
				QueryPlans.logs(null, null, null, null, null, null, null, from, null, null));
		measure("logs", "page 1, 14d, project=", logsInWindow(from), "logs", LOG_PAGE_SIZE, QueryPlans
			.logs(List.of(seeded.projectId()), null, null, null, null, null, null, from, null, null));
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
		CursorWalk walk = walk("/logs", "logs", DEEP_LOG_PAGE);
		measure("logs", "page " + walk.depth(), "/logs?cursor=" + walk.cursor(), "logs",
				pageSizeReached(walk, LOG_PAGE_SIZE),
				QueryPlans.logs(null, null, null, null, null, null, null, null, null, walk.cursor()));

		// Walked under the default filters rather than reached unfiltered and then
		// filtered: a project-scoped stream gets deep over a different span of time,
		// so a cursor borrowed from the global walk names a page nobody lands on.
		Instant from = uiWindowStart();
		CursorWalk scoped = walk(logsInWindow(from), "logs", DEEP_LOG_PAGE);
		measure("logs", "page " + scoped.depth() + ", 14d, project=",
				logsInWindow(from) + "&cursor=" + scoped.cursor(), "logs", pageSizeReached(scoped, LOG_PAGE_SIZE),
				QueryPlans.logs(List.of(seeded.projectId()), null, null, null, null, null, null, from, null,
						scoped.cursor()));
	}

	/** The default logs request the UI sends: one project, the 14-day window. */
	private String logsInWindow(Instant from) {
		return "/logs?project=" + seeded.projectId() + "&from=" + encode(from.toString());
	}

	/** `ui/src/app/core/filters.ts` defaults the global range to 14 days and sends it as `from`. */
	private static Instant uiWindowStart() {
		return Instant.now().minus(UI_WINDOW_DAYS, ChronoUnit.DAYS);
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
		CursorWalk walk = walk("/traces", "traces", DEEP_TRACE_PAGE);
		measure("traces", "page " + walk.depth(), "/traces?cursor=" + walk.cursor(), "traces",
				pageSizeReached(walk, TRACE_PAGE_SIZE),
				QueryPlans.traceSearch(null, null, null, null, null, null, null, null, null, walk.cursor()));
	}

	/** The four-table fan-out by {@code trace_id}, with no time predicate on any of them. */
	@Test
	void traceDetail() throws Exception {
		measure("trace detail", "4-table fan-out", "/traces/" + seeded.traceId(), "transactions", ANY_SIZE,
				sum(QueryPlans.traceDetail(seeded.traceId())), "spans", "errors", "logs");
	}

	// ------------------------------------------------------------------- pages

	@Test
	void otherPages() throws Exception {
		measure("releases", "list", "/releases?project=" + seeded.projectId(), null, ANY_SIZE,
				QueryPlans.releaseList(seeded.projectId()));
		measure("uptime", "overview", "/uptime/overview", null, ANY_SIZE, PlanFacts.NONE);
		measure("event detail", "+ 2 neighbours", "/events/" + seeded.eventId(), null, ANY_SIZE,
				sum(QueryPlans.eventDetail(seeded.eventId(), seeded.issueId(), seeded.eventTimestamp())));
	}

	// -------------------------------------------------------------- saturation

	/**
	 * The one throughput ladder. Each issue-list request issues four queries against
	 * a ten-connection pool, so the knee here is about the pool and the aggregates
	 * behind it rather than about any single query.
	 */
	@Test
	@Order(Integer.MAX_VALUE)
	void issueListSaturationLadder() throws Exception {
		// The whole page load, as every other issue row reports it — the ladder is
		// driving /issues, not the list query on its own.
		PlanFacts plan = issuePagePlan(QueryPlans.issueList(null, null, null, null, null, null, null, "last_seen", null));
		Probe probe = validate("issues saturation", "/issues", "issues", ISSUE_PAGE_SIZE);
		int base = Math.max(1, (int) Math.round(1000.0 / Math.max(probe.millis(), 1)));

		for (int multiple : SATURATION_MULTIPLES) {
			int rate = base * multiple;
			LoadDriver.Result result = driver.run(new LoadDriver.Step(rate, STEP_DURATION), () -> request("/issues"));
			// Reported, not asserted: past the knee a saturated step is the finding, and
			// failing the run on it would delete the answer.
			System.out.printf("issue list @ %4d/s → p50 %8.1f ms, p99 %8.1f ms, %d non-200%n", rate, result.p50Millis(),
					result.p99Millis(), result.offered() - result.status(200));
			REPORT.add(new RetrievalReport.Row("issues saturation", rate + "/s", result, datasetRows, ISSUE_PAGE_SIZE,
					plan));
			quiesce(probe);
		}
	}

	/**
	 * Waits for the server to finish whatever the last step left it holding.
	 *
	 * <p>{@link LoadDriver} gives up on its in-flight tail after two minutes and
	 * reports what is outstanding, but the server keeps working through it — so a
	 * saturated step hands its backlog to the next scenario, which then measures the
	 * backlog. The ingest benchmark has the same hazard and drains the queue between
	 * plateaus; there is no queue to read here, so responsiveness is the signal.
	 */
	private void quiesce(Probe reference) throws Exception {
		long deadline = System.nanoTime() + QUIESCE_TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			long startedAt = System.nanoTime();
			http.send(probeRequest("/issues"), HttpResponse.BodyHandlers.discarding());
			long millis = (System.nanoTime() - startedAt) / 1_000_000;
			if (millis <= Math.max(reference.millis() * 2, 100)) {
				return;
			}
		}
		System.out.printf("note: server still slow %s after a saturation step; later rows may carry its backlog%n",
				QUIESCE_TIMEOUT);
	}

	// ----------------------------------------------------------------- harness

	/**
	 * The combined facts of every statement one endpoint issues. Trace detail fans
	 * out into four and event detail into three; reporting one of them would be a
	 * number nobody actually waits for.
	 */
	private PlanFacts sum(List<QueryPlans.Built> queries) {
		PlanFacts total = PlanFacts.NONE;
		for (QueryPlans.Built query : queries) {
			total = total.merge(query.explain(jdbc));
		}
		return total;
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

	private void measure(String scenario, String step, String path, String listKey, int expectedRows, PlanFacts plan,
			String... alsoNonEmpty) throws Exception {
		Probe probe = validate(scenario + " " + step, path, listKey, expectedRows, alsoNonEmpty);
		if (probe.millis() > DRIVABLE_MILLIS) {
			System.out.printf("%-14s %-18s TOO SLOW TO DRIVE — one request took %d ms; reporting that sample alone%n",
					scenario, step, probe.millis());
			REPORT.add(new RetrievalReport.Row(scenario, step + " (single sample)", probe.asResult(), datasetRows,
					probe.rows(), plan));
			return;
		}
		LoadDriver.Result result = driver.run(stepFor(probe), () -> request(path));

		assertThat(result.failures()).as("driver/socket failures during %s %s", scenario, step).isZero();
		assertThat(result.shed()).as("requests shed by the load driver during %s %s", scenario, step).isZero();
		assertThat(result.offered() - result.status(200)).as("non-200 responses during %s %s", scenario, step).isZero();

		System.out.printf("%-14s %-18s %3d/s  p50 %7.1f ms  p99 %7.1f ms  %5d rows  %9d blocks%n", scenario, step,
				result.targetRate(), result.p50Millis(), result.p99Millis(), probe.rows(), plan.logicalIo());
		REPORT.add(new RetrievalReport.Row(scenario, step, result, datasetRows, probe.rows(), plan));
	}

	/**
	 * A rate this endpoint can actually absorb, and long enough at that rate for the
	 * percentiles to mean something.
	 */
	private static LoadDriver.Step stepFor(Probe probe) {
		double seconds = Math.max(probe.millis(), 1) / 1000.0;
		int rate = (int) Math.clamp(Math.round(TARGET_CONCURRENCY / seconds), 1, MAX_SCENARIO_RATE);
		long duration = Math.max(STEP_DURATION.toSeconds(), Math.ceilDiv(MIN_SAMPLES, rate));
		return new LoadDriver.Step(rate, Duration.ofSeconds(duration));
	}

	/** What one unhurried request returned, and how long it took. */
	private record Probe(long rows, long millis) {

		/** The probe as a one-sample result, for an endpoint too slow to drive. */
		LoadDriver.Result asResult() {
			return new LoadDriver.Result(1, 1, 1, 1, Map.of(200, 1L), Map.of(), 0, millis, millis, millis, millis);
		}
	}

	/**
	 * One unhurried request, checked before it is timed.
	 *
	 * <p>{@code alsoNonEmpty} names the other lists a response has to have filled
	 * in. Trace detail fans out into four, and a run where three of them came back
	 * empty would measure three queries finding nothing and report it as the cost of
	 * a trace — the seeder plants a trace with guaranteed fan-out precisely so that
	 * cannot pass quietly. A body that is a bare array is held to the same bar; the
	 * releases page returns one, and an empty releases page is fast and meaningless.
	 */
	private Probe validate(String what, String path, String listKey, int expectedRows, String... alsoNonEmpty)
			throws Exception {
		long startedAt = System.nanoTime();
		HttpResponse<String> response = http.send(probeRequest(path), HttpResponse.BodyHandlers.ofString());
		long millis = (System.nanoTime() - startedAt) / 1_000_000;
		assertThat(response.statusCode()).as("status for %s", what).isEqualTo(200);

		JsonNode body = mapper.readTree(response.body());
		for (String key : alsoNonEmpty) {
			assertNonEmptyList(body, key, what);
		}
		if (listKey == null) {
			if (!body.isArray()) {
				return new Probe(1, millis);
			}
			assertThat(body.size()).as("rows returned by %s — an empty page is fast and meaningless", what).isPositive();
			return new Probe(body.size(), millis);
		}
		JsonNode list = assertNonEmptyList(body, listKey, what);
		if (expectedRows != ANY_SIZE) {
			assertThat(list.size()).as("page size for %s", what).isEqualTo(expectedRows);
		}
		return new Probe(list.size(), millis);
	}

	private static JsonNode assertNonEmptyList(JsonNode body, String listKey, String what) {
		JsonNode list = body.get(listKey);
		assertThat(list).as("`%s` in the response for %s", listKey, what).isNotNull();
		assertThat(list.size()).as("`%s` rows returned by %s — an empty page is fast and meaningless", listKey, what)
			.isPositive();
		return list;
	}

	/**
	 * Walks {@code path} to page {@code target} over real HTTP. The arithmetic lives
	 * in {@link CursorWalk}, which is unit-tested; this supplies the transport.
	 */
	private CursorWalk walk(String path, String listKey, int target) throws Exception {
		CursorWalk walk = CursorWalk.to(target, cursor -> {
			String url = cursor == null ? path : path + (path.contains("?") ? "&" : "?") + "cursor=" + cursor;
			HttpResponse<String> response = http.send(probeRequest(url), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).as("status walking %s", path).isEqualTo(200);

			JsonNode body = mapper.readTree(response.body());
			JsonNode next = body.get("next_cursor");
			return new CursorWalk.Page(idsOf(body.get(listKey)),
					next == null || next.isNull() ? null : next.asString());
		});
		if (walk.depth() < target) {
			System.out.printf("note: %s ran out of rows at page %d of a requested %d%n", path, walk.depth(), target);
		}
		System.out.printf("walked %s to page %d%n", path, walk.depth());
		return walk;
	}

	/**
	 * The page size to hold a walked page to. A walk that ran out of rows ends on
	 * the dataset's last page, which is legitimately short — asserting a full page
	 * there would fail a smoke run for having less data, which is the one thing a
	 * smoke run is meant to have.
	 */
	private static int pageSizeReached(CursorWalk walk, int pageSize) {
		return walk.pageIsFull() ? pageSize : ANY_SIZE;
	}

	private static List<String> idsOf(JsonNode list) {
		List<String> ids = new ArrayList<>();
		for (JsonNode row : list) {
			ids.add(row.get("id").asString());
		}
		return ids;
	}

	// -------------------------------------------------------------------- http

	/** A request for the timed step, bounded by the driver's per-request timeout. */
	private HttpRequest request(String path) {
		return requestWithin(path, LoadDriver.REQUEST_TIMEOUT);
	}

	/**
	 * A request for a probe or a cursor walk, with room to be slow.
	 *
	 * <p>The driver's timeout exists to stop one stalled request from wrecking a
	 * plateau's percentiles, and it is the wrong bound here: a probe that takes
	 * thirty seconds is not a stall, it is the finding, and timing it out would fail
	 * the run instead of reporting it. {@link #DRIVABLE_MILLIS} is what acts on the
	 * answer.
	 */
	private HttpRequest probeRequest(String path) {
		return requestWithin(path, PROBE_TIMEOUT);
	}

	private HttpRequest requestWithin(String path, Duration timeout) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/internal" + path))
			.timeout(timeout)
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
