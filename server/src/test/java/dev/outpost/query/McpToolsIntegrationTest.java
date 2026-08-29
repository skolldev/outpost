package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.auth.ApiTokenService;
import dev.outpost.support.McpTestClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The seven Tools #180 adds, each driven over JSON-RPC exactly as a client drives
 * them. {@code get_issue_context} and the transport and Scope underneath all
 * eight are covered by {@code McpSurfaceIntegrationTest}, through the same
 * {@link McpTestClient}.
 *
 * <p>Telemetry is posted through the real ingest surface rather than inserted,
 * as {@code TraceQueryIntegrationTest} does it: these Tools read columns and
 * {@code event.data} the pipeline wrote, and a hand-built row would let a
 * projection drift away from the shape ingestion actually produces. Uptime is the
 * exception — an Uptime Check has no ingest path, so those rows go in by hand.
 *
 * <p>Seeded once for the class rather than per test, which is a deliberate
 * departure from this repo's delete-all-in-{@code @BeforeEach} pattern. Every
 * Tool here is read-only and none of these tests can disturb another; re-running
 * the ingest pipeline sixteen times would be the whole runtime of the suite.
 *
 * <p>What each test asserts is not only the shape of the payload but the
 * <b>disclosure</b> in it, because that is what #177 says these Tools are for.
 * A field name that overstates its data and a missing caveat are both defects
 * here, and neither shows up in a schema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
				"outpost.ingest.linger-millis=50" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpToolsIntegrationTest {

	private static final String TRACE_ID = "beefbeefbeefbeefbeefbeefbeefbeef";
	private static final String ROOT_SPAN = "dddd000000000001";
	private static final String CHILD_SPAN = "dddd000000000002";

	/** The Transaction Group the leaderboard must rank: enough samples to clear the floor. */
	private static final String CHECKOUT = "POST /api/checkout";

	/** A second group, deliberately below the sample floor, so the floor's disclosure has something to hide. */
	private static final String RARE = "GET /api/rare";

	private static final int CHECKOUT_TRANSACTIONS = 6;

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper mapper;

	McpTestClient client;

	String token;

	long shopId;
	long billingId;
	String shopKey;
	Instant base;
	long issueId;
	String eventId;
	long monitorId;

	@BeforeAll
	void seed() {
		client = new McpTestClient(port, mapper);
		jdbc.sql("DELETE FROM uptime_incident").update();
		jdbc.sql("DELETE FROM uptime_check").update();
		jdbc.sql("DELETE FROM uptime_monitor").update();
		jdbc.sql("DELETE FROM span").update();
		jdbc.sql("DELETE FROM txn").update();
		jdbc.sql("DELETE FROM log_record").update();
		jdbc.sql("DELETE FROM event").update();
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM environment").update();
		jdbc.sql("DELETE FROM project").update();
		jdbc.sql("DELETE FROM api_token").update();

		shopId = project("shop", "Shop", "java");
		billingId = project("billing", "Billing", "python");
		shopKey = "0123456789abcdef0123456789abcdef";
		jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, ?)")
			.param(shopId)
			.param(shopKey)
			.update();
		token = insertToken("agent", ApiTokenService.SCOPE_TELEMETRY_READ);
		base = Instant.now();

		postEnvelope(errorEnvelope());
		postEnvelope(logEnvelope("info", "handling checkout", TRACE_ID));
		postEnvelope(logEnvelope("error", "checkout failed for order 4711", TRACE_ID));
		for (int i = 0; i < CHECKOUT_TRANSACTIONS; i++) {
			postEnvelope(transactionEnvelope(CHECKOUT, TRACE_ID, 100 + i * 10));
		}
		postEnvelope(transactionEnvelope(RARE, "aaaa" + "0".repeat(28), 500));

		issueId = await("issue", () -> jdbc.sql("SELECT id FROM issue ORDER BY id").query(Long.class).list());
		eventId = await("event", () -> jdbc.sql("SELECT id::text FROM event WHERE issue_id = ?")
			.param(issueId)
			.query(String.class)
			.list());
		await("log records", () -> jdbc.sql("SELECT id::text FROM log_record").query(String.class).list(), 2);
		await("transactions", () -> jdbc.sql("SELECT id::text FROM txn").query(String.class).list(),
				CHECKOUT_TRANSACTIONS + 1);
		seedUptime();

		client.connect(token);
	}

	// ------------------------------------------------------------ list_projects

	@Test
	void listProjectsNamesEveryProjectBySlugWithItsEnvironments() {
		JsonNode result = call("list_projects", Map.of());

		List<String> slugs = result.path("projects")
			.valueStream()
			.map(project -> project.path("slug").asString())
			.toList();
		assertThat(slugs).containsExactly("billing", "shop");
		JsonNode shop = project(result, "shop");
		assertThat(shop.path("name").asString()).isEqualTo("Shop");
		assertThat(shop.path("platform").asString()).isEqualTo("java");
		assertThat(shop.path("environments").valueStream().map(JsonNode::asString).toList()).contains("prod");
	}

	/**
	 * A Project that has received nothing has no Environment Names, and the empty
	 * list has two readings — "none exist" and "nothing has arrived". Only one is
	 * true, and an agent that takes the other stops looking.
	 */
	@Test
	void anEmptyEnvironmentListIsExplainedRatherThanLeftAmbiguous() {
		JsonNode result = call("list_projects", Map.of());

		assertThat(project(result, "billing").path("environments")).isEmpty();
		assertThat(caveats(result)).anySatisfy(caveat -> assertThat(caveat)
			.contains("no telemetry carrying an Environment Name has been received"));
	}

	// -------------------------------------------------------------- find_issues

	@Test
	void findIssuesReturnsTheIssueUnderItsProjectSlug() {
		JsonNode result = call("find_issues", Map.of("project_slugs", List.of("shop")));

		assertThat(result.path("issues")).singleElement().satisfies(issue -> {
			assertThat(issue.path("id").asLong()).isEqualTo(issueId);
			assertThat(issue.path("project_slug").asString()).isEqualTo("shop");
			assertThat(issue.path("status").asString()).isEqualTo("unresolved");
			// Named for what it counts, per ADR-0014 — not `event_count`.
			assertThat(issue.path("events_received").asLong()).isEqualTo(1);
		});
	}

	/**
	 * The defaults ADR-0016 requires to be applied server-side, and the disclosure
	 * that keeps them from being a silent narrowing. Both are the point: the window
	 * is what makes the reused statement affordable, and the status is the predicate
	 * the list indexes lead with.
	 */
	@Test
	void findIssuesAppliesItsDefaultsServerSideAndSaysSo() {
		JsonNode result = call("find_issues", Map.of());

		assertThat(result.path("applied_status").asString()).isEqualTo("unresolved");
		assertThat(result.path("window").path("defaulted").asBoolean()).isTrue();
		assertThat(Instant.parse(result.path("window").path("from").asString()))
			.isBefore(Instant.now().minus(Duration.ofDays(13)));
		assertThat(caveats(result))
			.anySatisfy(caveat -> assertThat(caveat).contains("status was not supplied").contains("unresolved"))
			.anySatisfy(caveat -> assertThat(caveat).contains("from was not supplied"));
	}

	@Test
	void findIssuesFiltersByStatusAndRejectsAStatusItCannotAnswer() {
		assertThat(call("find_issues", Map.of("status", "resolved")).path("issues")).isEmpty();

		assertThat(error("find_issues", Map.of("status", "any"))).contains("status must be one of");
	}

	/**
	 * An unknown slug is an error rather than a dropped filter. A dropped filter
	 * widens the answer silently, and a result spanning every Project reads exactly
	 * like one scoped to the Project that was asked for.
	 */
	@Test
	void anUnknownProjectSlugIsRefusedRatherThanIgnored() {
		String message = error("find_issues", Map.of("project_slugs", List.of("nope")));

		assertThat(message).contains("no Project has the slug 'nope'").contains("list_projects");
	}

	// -------------------------------------------------------------- search_logs

	@Test
	void searchLogsReturnsRecordsNewestFirstWithTheirProjectAndAttributes() {
		JsonNode result = call("search_logs", Map.of("project_slugs", List.of("shop")));

		assertThat(result.path("log_records")).hasSize(2);
		JsonNode newest = result.path("log_records").get(0);
		assertThat(newest.path("body").asString()).isEqualTo("checkout failed for order 4711");
		assertThat(newest.path("level").asString()).isEqualTo("error");
		assertThat(newest.path("project_slug").asString()).isEqualTo("shop");
		assertThat(newest.path("trace_id").asString()).isEqualTo(TRACE_ID);
		assertThat(newest.path("attributes").path("order.id").asString()).isEqualTo("4711");
	}

	@Test
	void searchLogsFiltersByLevelTraceAndBodySubstring() {
		assertThat(call("search_logs", Map.of("levels", List.of("error"))).path("log_records")).hasSize(1);
		assertThat(call("search_logs", Map.of("trace_id", TRACE_ID)).path("log_records")).hasSize(2);
		assertThat(call("search_logs", Map.of("query", "handling")).path("log_records")).singleElement()
			.satisfies(record -> assertThat(record.path("body").asString()).isEqualTo("handling checkout"));
		assertThat(call("search_logs", Map.of("query", "nothing matches this")).path("log_records")).isEmpty();
	}

	// ---------------------------------------------------------------- get_trace

	@Test
	void getTraceReturnsTheWholeTraceAcrossSignals() {
		JsonNode result = call("get_trace", Map.of("trace_id", TRACE_ID));

		assertThat(result.path("trace_id").asString()).isEqualTo(TRACE_ID);
		assertThat(result.path("transactions_received").asLong()).isEqualTo(CHECKOUT_TRANSACTIONS);
		assertThat(result.path("spans_received").asLong()).isEqualTo(CHECKOUT_TRANSACTIONS);
		assertThat(result.path("error_events_received").asLong()).isEqualTo(1);
		assertThat(result.path("log_records_received").asLong()).isEqualTo(2);

		assertThat(result.path("transactions").get(0).path("name").asString()).isEqualTo(CHECKOUT);
		assertThat(result.path("transactions").get(0).path("project_slug").asString()).isEqualTo("shop");
		assertThat(result.path("spans").get(0).path("parent_span_id").asString()).isEqualTo(ROOT_SPAN);
		assertThat(result.path("error_events").get(0).path("issue_id").asLong()).isEqualTo(issueId);
		assertThat(result.path("log_records").get(0).path("body").asString()).isEqualTo("handling checkout");
	}

	/**
	 * The projection is named rather than left to be noticed. A Span's attribute bag
	 * is where a database Span keeps its statement, and a caller that does not know
	 * it is absent will conclude the Span carried nothing.
	 */
	@Test
	void getTraceSaysThatSpanAttributesAreNotInIt() {
		JsonNode result = call("get_trace", Map.of("trace_id", TRACE_ID));

		assertThat(result.path("spans").get(0).has("data")).isFalse();
		assertThat(caveats(result)).anySatisfy(
				caveat -> assertThat(caveat).contains("attribute payloads are not returned by this Tool"));
	}

	@Test
	void anUnknownTraceIdIsAToolErrorThatSaysWhyItMightBeMissing() {
		String message = error("get_trace", Map.of("trace_id", "f".repeat(32)));

		assertThat(message).contains("no Trace with trace_id").contains("retention");
	}

	// ------------------------------------------------------------ get_event_raw

	@Test
	void getEventRawReturnsTheStoredPayloadWholeAndSaysItIsUnredacted() {
		JsonNode result = call("get_event_raw", Map.of("event_id", eventId));

		assertThat(result.path("issue_id").asLong()).isEqualTo(issueId);
		assertThat(result.path("project_slug").asString()).isEqualTo("shop");
		assertThat(result.path("data_bytes").asInt()).isPositive();
		// The keys get_issue_context names as omitted are exactly the ones here.
		assertThat(result.path("data").path("tags").path("handled").asString()).isEqualTo("no");
		assertThat(result.path("data").path("contexts").path("runtime").path("name").asString()).isEqualTo("OpenJDK");
		assertThat(caveats(result))
			.anySatisfy(caveat -> assertThat(caveat).contains("verbatim and unredacted"));
	}

	@Test
	void getEventRawRejectsSomethingThatIsNotAnEventId() {
		assertThat(error("get_event_raw", Map.of("event_id", "not-a-uuid"))).contains("must be a UUID");
		assertThat(error("get_event_raw", Map.of("event_id", UUID.randomUUID().toString())))
			.contains("no Event with id");
	}

	// ------------------------------------------------------------ uptime_status

	@Test
	void uptimeStatusReportsTheOpenIncidentAndTheChecksBehindIt() {
		JsonNode result = call("uptime_status", Map.of());

		assertThat(result.path("monitors")).singleElement().satisfies(monitor -> {
			assertThat(monitor.path("id").asLong()).isEqualTo(monitorId);
			assertThat(monitor.path("project_slug").asString()).isEqualTo("shop");
			assertThat(monitor.path("status").asString()).isEqualTo("down");
			assertThat(monitor.path("open_incident").path("last_error").asString()).isEqualTo("connect timed out");
			assertThat(monitor.path("checks_received").asLong()).isEqualTo(10);
			assertThat(monitor.path("checks_failed").asLong()).isEqualTo(3);
			assertThat(monitor.path("successful_checks_pct").asDouble()).isEqualTo(70.0);
			assertThat(monitor.path("days")).isNotEmpty();
		});
	}

	/**
	 * The naming decision this Tool exists to get right: a share of probes is not
	 * availability, and a field called {@code uptime_pct} would invite a model to
	 * report the stronger claim.
	 */
	@Test
	void uptimeStatusNamesThePercentageForWhatItActuallyMeasures() {
		JsonNode result = call("uptime_status", Map.of());

		JsonNode monitor = result.path("monitors").get(0);
		assertThat(monitor.has("uptime_pct")).isFalse();
		assertThat(monitor.has("successful_checks_pct")).isTrue();
		assertThat(caveats(result)).anySatisfy(caveat -> assertThat(caveat)
			.contains("share of Uptime Checks that succeeded, not availability"));
	}

	@Test
	void uptimeStatusClampsTheHistoryItWillRenderAndSaysSo() {
		JsonNode result = call("uptime_status", Map.of("days", 10_000));

		assertThat(result.path("days_returned").asInt()).isEqualTo(90);
		assertThat(caveats(result)).anySatisfy(caveat -> assertThat(caveat).contains("clamped from 10000"));
	}

	@Test
	void uptimeStatusFiltersByProject() {
		assertThat(call("uptime_status", Map.of("project_slugs", List.of("billing"))).path("monitors")).isEmpty();
	}

	// ----------------------------------------------------- performance_overview

	@Test
	void performanceOverviewRanksTransactionGroupsByTheTimeTheyAccountFor() {
		JsonNode result = call("performance_overview", Map.of("project_slugs", List.of("shop")));

		assertThat(result.path("transaction_groups")).singleElement().satisfies(group -> {
			assertThat(group.path("name").asString()).isEqualTo(CHECKOUT);
			assertThat(group.path("op").asString()).isEqualTo("http.server");
			assertThat(group.path("project_slug").asString()).isEqualTo("shop");
			// Never `count`, and never a bare `p95` — ADR-0014 puts the units in the name.
			assertThat(group.path("transactions_received").asLong()).isEqualTo(CHECKOUT_TRANSACTIONS);
			assertThat(group.path("p95_ms").asDouble()).isPositive();
			assertThat(group.path("total_ms").asDouble()).isPositive();
		});
		assertThat(result.path("sorted_by").asString()).isEqualTo("total_ms");
		assertThat(result.path("range_clamped").asBoolean()).isFalse();
	}

	/**
	 * The group below the sample floor is missing from the ranking and present in
	 * the cardinality, which is why those two numbers do not reconcile — and why the
	 * caveat has to say so rather than leave it looking like a defect.
	 */
	@Test
	void performanceOverviewDisclosesTheSampleFloorAndTheCardinalityBehindIt() {
		JsonNode result = call("performance_overview", Map.of("project_slugs", List.of("shop")));

		assertThat(result.path("distinct_transaction_groups").asLong()).isEqualTo(2);
		assertThat(result.path("transaction_groups")).hasSize(1);
		assertThat(caveats(result))
			.anySatisfy(caveat -> assertThat(caveat).contains("fewer than").contains("are excluded from this ranking"))
			.anySatisfy(caveat -> assertThat(caveat).contains("never normalized"));
	}

	/**
	 * The disclosure #180 names outright: these are the Transactions Outpost
	 * received, and the Performance view carries this as a banner that does not
	 * survive a Tool call.
	 */
	@Test
	void performanceOverviewCarriesTheSamplingCaveatTheUiBannerCarries() {
		JsonNode result = call("performance_overview", Map.of());

		assertThat(caveats(result)).anySatisfy(caveat -> assertThat(caveat)
			.contains("not the requests served")
			.contains("stores no sample rate"));
	}

	@Test
	void performanceOverviewRanksByEveryOrderItOffersAndRefusesTheRest() {
		for (String sort : List.of("total_ms", "p95_ms", "p50_ms", "transactions_received")) {
			assertThat(call("performance_overview", Map.of("sort", sort)).path("sorted_by").asString())
				.isEqualTo(sort);
		}
		assertThat(error("performance_overview", Map.of("sort", "p95"))).contains("sort must be one of");
	}

	// ------------------------------------------------------------------ helpers

	private JsonNode call(String tool, Map<String, Object> arguments) {
		return client.callTool(token, tool, arguments);
	}

	private String error(String tool, Map<String, Object> arguments) {
		return client.callToolExpectingError(token, tool, arguments);
	}

	private static List<String> caveats(JsonNode result) {
		return result.path("caveats").valueStream().map(JsonNode::asString).toList();
	}

	private static JsonNode project(JsonNode result, String slug) {
		return result.path("projects")
			.valueStream()
			.filter(project -> slug.equals(project.path("slug").asString()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no Project " + slug + " in " + result));
	}

	// ------------------------------------------------------------------ fixture

	private long project(String slug, String name, String platform) {
		return jdbc.sql("INSERT INTO project (slug, name, platform) VALUES (?, ?, ?) RETURNING id")
			.param(slug)
			.param(name)
			.param(platform)
			.query(Long.class)
			.single();
	}

	/**
	 * One Uptime Monitor that is down: ten Uptime Checks, the last three failed, and
	 * the Incident those three opened. {@code next_check_at} is pushed into the
	 * future because {@code UptimeScheduler} runs during tests and would otherwise
	 * probe {@code example.invalid} for real.
	 */
	private void seedUptime() {
		monitorId = jdbc.sql("""
				INSERT INTO uptime_monitor (project_id, environment, url, interval_seconds, next_check_at)
				VALUES (?, 'prod', 'https://example.invalid/health', 60, now() + interval '1 day') RETURNING id
				""").param(shopId).query(Long.class).single();
		for (int i = 0; i < 10; i++) {
			boolean success = i < 7;
			jdbc.sql("""
					INSERT INTO uptime_check (monitor_id, checked_at, success, status_code, latency_ms, error)
					VALUES (?, now() - make_interval(mins => ?), ?, ?, 42, ?)
					""")
				.param(monitorId)
				.param(10 - i)
				.param(success)
				.param(success ? 200 : null)
				.param(success ? null : "connect timed out")
				.update();
		}
		jdbc.sql("INSERT INTO uptime_incident (monitor_id, last_error) VALUES (?, 'connect timed out')")
			.param(monitorId)
			.update();
	}

	private String insertToken(String name, String scope) {
		String secret = "outpost_" + UUID.randomUUID().toString().replace("-", "");
		jdbc.sql("INSERT INTO api_token (name, token_hash, scopes) VALUES (?, ?, string_to_array(?, ','))")
			.param(name)
			.param(sha256(secret))
			.param(scope)
			.update();
		return secret;
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private String errorEnvelope() {
		String event = """
				{"event_id":"%s","timestamp":"%s","platform":"java","level":"error","environment":"prod",\
				"release":"shop@1.0.0","tags":{"handled":"no"},\
				"contexts":{"runtime":{"name":"OpenJDK"},"trace":{"trace_id":"%s","span_id":"%s"}},\
				"breadcrumbs":{"values":[{"timestamp":"%s","type":"http","category":"request",\
				"level":"info","message":"POST /api/checkout"}]},\
				"exception":{"values":[{"type":"IllegalStateException","value":"order 4711 has no customer",\
				"module":"java.lang","stacktrace":{"frames":[\
				{"module":"dev.demo.CheckoutController","function":"checkout","in_app":true,"lineno":41}]}}]}}"""
			.formatted(hexId(), base, TRACE_ID, ROOT_SPAN, base.minusSeconds(3));
		return envelope("event", event);
	}

	private String transactionEnvelope(String name, String traceId, int durationMillis) {
		Instant start = base.minusMillis(durationMillis);
		String txn = """
				{"type":"transaction","transaction":"%s","platform":"java","environment":"prod",\
				"release":"shop@1.0.0","start_timestamp":%s,"timestamp":%s,\
				"contexts":{"trace":{"trace_id":"%s","span_id":"%s","op":"http.server","status":"internal_error"}},\
				"spans":[{"trace_id":"%s","span_id":"%s","parent_span_id":"%s","op":"db.sql.query",\
				"description":"SELECT * FROM orders WHERE id = ?","start_timestamp":%s,"timestamp":%s,\
				"status":"ok"}]}"""
			.formatted(name, epochSeconds(start), epochSeconds(base), traceId, ROOT_SPAN, traceId, CHILD_SPAN,
					ROOT_SPAN, epochSeconds(start), epochSeconds(base));
		return envelope("transaction", txn);
	}

	private String logEnvelope(String level, String body, String traceId) {
		String payload = """
				{"items":[{"timestamp":%s,"trace_id":"%s","span_id":"%s","level":"%s","severity_number":9,\
				"body":"%s","attributes":{"sentry.environment":{"value":"prod","type":"string"},\
				"order.id":{"value":"4711","type":"string"}}}]}"""
			.formatted(epochSeconds(base.minusSeconds("error".equals(level) ? 1 : 2)), traceId, CHILD_SPAN, level,
					body);
		return "{\"sent_at\":\"" + Instant.now() + "\"}\n"
				+ "{\"type\":\"log\",\"item_count\":1,\"content_type\":\"application/vnd.sentry.items.log+json\",\"length\":"
				+ payload.getBytes(StandardCharsets.UTF_8).length + "}\n" + payload + "\n";
	}

	private String envelope(String type, String payload) {
		return "{\"sent_at\":\"" + Instant.now() + "\"}\n" + "{\"type\":\"" + type + "\",\"length\":"
				+ payload.getBytes(StandardCharsets.UTF_8).length + "}\n" + payload + "\n";
	}

	private void postEnvelope(String envelope) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/x-sentry-envelope");
		ResponseEntity<String> response = client.rest()
			.exchange(client.url("/api/" + shopId + "/envelope/?sentry_key=" + shopKey), HttpMethod.POST,
					new HttpEntity<>(envelope.getBytes(StandardCharsets.UTF_8), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	/** Ingestion is asynchronous; wait for the pipeline to have stored {@code expected} rows. */
	private <T> T await(String what, java.util.function.Supplier<List<T>> rows) {
		return await(what, rows, 1).get(0);
	}

	private <T> List<T> await(String what, java.util.function.Supplier<List<T>> rows, int expected) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
		List<T> found = List.of();
		while (Instant.now().isBefore(deadline)) {
			found = rows.get();
			if (found.size() >= expected) {
				return found;
			}
			sleep();
		}
		throw new AssertionError(
				"expected " + expected + " " + what + " within 20s, the pipeline stored " + found.size());
	}

	private void sleep() {
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static double epochSeconds(Instant instant) {
		return instant.toEpochMilli() / 1000.0;
	}

	private String hexId() {
		return UUID.randomUUID().toString().replace("-", "");
	}


}
