package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import java.net.URI;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * {@code GET /transaction-groups} end to end (#159, #160): wire shape, statistics
 * against a hand-computed fixture, and rules the client can't enforce itself — the
 * (Project, name, op) grouping key, the 30-day window cap, and the sample floor
 * that excludes a group from ranking but not from the cardinality count.
 * Durations are non-round so {@code percentile_cont} interpolation is exact
 * (e.g. p95 of 100..1000 is 955), distinguishing it from {@code percentile_disc}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class TransactionGroupIntegrationTest {

	private static final String GROUPS = "/api/internal/transaction-groups";

	/**
	 * The key travels in query params, not the path — a transaction name like
	 * {@code GET /api/checkout/{id}} contains slashes and can't be a path segment
	 * without double-encoding.
	 */
	private static final String GROUP = "/api/internal/transaction-groups/detail";

	private static final String CHECKOUT = "GET /api/checkout/{id}";

	/**
	 * The sort and Release fixtures live in their own Environment Names so they
	 * filter cleanly and don't perturb the base fixture's group counts; both are
	 * seeded per test rather than in {@code setUp} for the same reason.
	 */
	private static final String SORT_FIXTURE = "sort-fixture";

	private static final String RELEASE_FIXTURE = "release-fixture";

	private static final String HOT = "GET /api/search";

	private static final String SLOW = "GET /api/reports";

	private static final String SPIKY = "POST /api/import";

	private static final String PRICING = "GET /api/pricing";

	private static final String FAST_RELEASE = "shop@1.0.0";

	private static final String SLOW_RELEASE = "shop@2.0.0";

	/**
	 * The controller's minimum-sample floor, restated rather than imported —
	 * importing it would let the test keep passing even if the floor changed.
	 */
	private static final int SAMPLE_FLOOR = 5;

	/** Percentiles are interpolated in {@code double}, so an exact equality would flake on the last bit. */
	private static final org.assertj.core.data.Offset<Double> TOLERANCE = within(1e-6);

	/**
	 * Comfortably inside the 30-day cap, so an unclamped request covers the
	 * fixture. Truncated to the second because it round-trips through a query
	 * param and back, and sub-second precision would only fail a formatting
	 * mismatch unrelated to the assertion.
	 */
	private static final Instant ANCHOR = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

	/**
	 * Truncated to the hour so it sits on every sub-day bucket boundary the
	 * ladder produces; tests that need an off-grid window say so explicitly.
	 */
	private static final Instant TREND_ANCHOR = ANCHOR.truncatedTo(ChronoUnit.HOURS);

	/** Its own Environment Name, so it is invisible to every test that counts groups. */
	private static final String TREND_FIXTURE = "trend-fixture";

	private static final String TRENDING = "GET /api/quote";

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

		// Every group meant to be ranked carries at least SAMPLE_FLOOR Transactions, or the fixture would be testing the floor instead.

		// Group A — ten Transactions, 100..1000 ms: total 5500, avg 550, max 1000, p50 550, p95 955, p99 991.
		for (int i = 1; i <= 10; i++) {
			seed(project, CHECKOUT, "http.server", "production", i * 100.0);
		}
		// Group B — the same name under a different op. Separate group, by the key.
		for (int i = 0; i < SAMPLE_FLOOR; i++) {
			seed(project, CHECKOUT, "navigation", "production", 50.0);
		}
		// Group C — no op at all, which is a legitimate group rather than an absence.
		for (int i = 1; i <= SAMPLE_FLOOR; i++) {
			seed(project, "GET /api/cart", null, "production", i * 10.0);
		}
		// Group D — one 30s Transaction: it would top every statistic above, because they're all the same one duration.
		seed(project, "GET /api/orders/98217", "http.server", "production", 30_000.0);
		// Elsewhere: another Project, and another Environment Name in this one.
		for (int i = 0; i < SAMPLE_FLOOR; i++) {
			seed(otherProject, "GET /admin/users", "http.server", "production", 4000.0);
			seed(project, "GET /api/staging-only", "http.server", "staging", 3000.0);
		}

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
		// Interpolated: p95 of ten values lands 55% from 900 to 1000, i.e. 954.999... compared within tolerance.
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

		assertThat(cart).containsEntry("op", null).containsEntry("count", 5).containsEntry("total_ms", 150.0);
	}

	/**
	 * Group D's single 30-second Transaction beats every other group on every
	 * statistic, so if the sample floor didn't exclude it, it would rank first.
	 */
	@Test
	void aGroupBelowTheSampleFloorCannotOutrankRealOnesHoweverExtremeItIs() {
		List<String> ranked = names(leaderboard("&project=" + project + "&environment=production"));

		assertThat(ranked).doesNotContain("GET /api/orders/98217").startsWith(CHECKOUT);
	}

	/** And a group that reaches the floor exactly is ranked — the boundary is inclusive. */
	@Test
	void aGroupThatReachesTheFloorExactlyIsRanked() {
		for (int i = 0; i < SAMPLE_FLOOR; i++) {
			seed(project, "GET /api/exactly-at-the-floor", "http.server", "production", 100.0);
		}

		assertThat(groups("&project=" + project + "&environment=production")).anySatisfy(
				group -> assertThat(group).containsEntry("name", "GET /api/exactly-at-the-floor")
					.containsEntry("count", SAMPLE_FLOOR));
	}

	/**
	 * The cardinality count is taken before the floor — a Project emitting a
	 * Transaction Group per unparameterized URL (1-2 Transactions each) is
	 * exactly what the floor removes, so a post-floor count would read near zero
	 * on the data that most needs reporting.
	 */
	@Test
	void theDistinctGroupCountIncludesGroupsTheFloorExcluded() {
		Map<String, Object> body = leaderboard("&project=" + project + "&environment=production");

		// A, B, C and the one-sample D. Three are ranked; all four are counted.
		assertThat(body).containsEntry("distinct_groups", 4);
		assertThat(names(body)).hasSize(3);
	}

	/**
	 * Each sort ranks the same three groups differently — no two of the four
	 * orders agree (see {@link #seedSortFixture()}) — so a controller that
	 * ignored {@code sort} or aliased two of them would fail here rather than
	 * coincidentally pass.
	 */
	@Test
	void eachSortRanksTheListAsItClaims() {
		seedSortFixture();
		String filter = "&project=" + project + "&environment=" + SORT_FIXTURE;

		assertThat(names(leaderboard(filter))).as("default").containsExactly(HOT, SLOW, SPIKY);
		assertThat(names(leaderboard(filter + "&sort=total_ms"))).as("total_ms").containsExactly(HOT, SLOW, SPIKY);
		assertThat(names(leaderboard(filter + "&sort=count"))).as("count").containsExactly(HOT, SPIKY, SLOW);
		assertThat(names(leaderboard(filter + "&sort=p50"))).as("p50").containsExactly(SLOW, HOT, SPIKY);
		assertThat(names(leaderboard(filter + "&sort=p95"))).as("p95").containsExactly(SPIKY, SLOW, HOT);
	}

	/**
	 * An unrecognised sort is rejected with the accepted set named in the
	 * message, rather than coerced to the default — silently returning a
	 * different ranking than the one asked for is worse than an error.
	 */
	@Test
	void anUnrecognisedSortIsRejected() {
		ResponseEntity<Map> response = get(GROUPS + "?project=" + project + "&sort=worst", adminCookie);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat((String) response.getBody().get("detail")).contains("total_ms", "p95", "p50", "count");
	}

	/**
	 * A sort carrying SQL is rejected by the same whitelist before it reaches a
	 * statement. The order expression is chosen from a fixed map in the
	 * controller — a request supplies only a key, never an expression.
	 */
	@Test
	void aSortCarryingSqlIsRejectedRatherThanInterpolated() {
		String injection = "total_ms; DROP TABLE txn";

		ResponseEntity<Map> response = get(
				GROUPS + "?project=" + project + "&sort=" + java.net.URLEncoder
					.encode(injection, java.nio.charset.StandardCharsets.UTF_8),
				adminCookie);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		// The list still answers, which it could not do if the statement had run.
		assertThat(names(leaderboard("&project=" + project + "&environment=production"))).startsWith(CHECKOUT);
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

	/** The name search narrows the list, and matches a substring however it is cased. */
	@Test
	void theNameSearchNarrowsTheList() {
		assertThat(groups("&project=" + project + "&environment=production&query=CART"))
			.extracting(g -> g.get("name"))
			.containsExactly("GET /api/cart");
	}

	/**
	 * The cardinality count is taken over the searched slice too — counting the
	 * wider unfiltered set would warn about names the user just filtered away.
	 */
	@Test
	void theNameSearchNarrowsTheCardinalityCountWithIt() {
		Map<String, Object> body = leaderboard("&project=" + project + "&environment=production&query=checkout");

		// The two ops of the checkout group only, not the four the unsearched window holds.
		assertThat(body).containsEntry("distinct_groups", 2);
	}

	/**
	 * The Release filter narrows the Transactions aggregated, not the grouping
	 * key — Release isn't part of (Project, name, op), so a group spanning two
	 * versions stays one group and filtering just re-states its statistics for
	 * that version. This is how a duration regression gets attributed to a
	 * specific release.
	 */
	@Test
	void theReleaseFilterNarrowsTheTransactionsAGroupIsComputedFrom() {
		seedReleaseFixture();
		String filter = "&project=" + project + "&environment=" + RELEASE_FIXTURE;

		Map<String, Object> both = groups(filter).get(0);
		assertThat(both).containsEntry("name", PRICING).containsEntry("count", 10).containsEntry("avg_ms", 300.0);

		Map<String, Object> regressed = groups(filter + "&release=" + SLOW_RELEASE).get(0);
		assertThat(regressed).containsEntry("name", PRICING).containsEntry("count", 5).containsEntry("avg_ms", 500.0);

		Map<String, Object> before = groups(filter + "&release=" + FAST_RELEASE).get(0);
		assertThat(before).containsEntry("count", 5).containsEntry("avg_ms", 100.0);
	}

	/** And it excludes groups that carry no Transaction on that Release at all. */
	@Test
	void theReleaseFilterExcludesGroupsThatNeverRanOnIt() {
		seedReleaseFixture();

		Map<String, Object> body = leaderboard("&project=" + project + "&release=" + SLOW_RELEASE);

		// Every other group in the fixture was seeded with no release.
		assertThat(names(body)).containsExactly(PRICING);
		assertThat(body).containsEntry("distinct_groups", 1);
	}

	/**
	 * "All time" (from the global range picker) is clamped to 30 days per
	 * ADR-0015, and the response discloses the window and the clamp — silent
	 * clamping is how "the numbers are wrong" reports happen.
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
		// Above the sample floor, so the clamp is the only thing that can exclude it.
		for (int i = 0; i < SAMPLE_FLOOR; i++) {
			seedAt(old, project, "GET /api/ancient", "http.server", "production", 9999.0);
		}

		Map<String, Object> body = leaderboard("&project=" + project + "&from=" + ANCHOR.minus(90, ChronoUnit.DAYS));

		assertThat(body.get("range_clamped")).isEqualTo(true);
		assertThat(window(body)).isEqualTo(30 * 24 * 60);
		// 5 x 9999 ms would top a total-time ranking if the clamp had not excluded it.
		assertThat(names(body)).doesNotContain("GET /api/ancient");
		// Cardinality is taken over the clamped window too; five includes the staging group since environment filters input, not the key.
		assertThat(body).containsEntry("distinct_groups", 5);
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
	 * The list stops at 100 groups with no cursor — an aggregate has no key to
	 * seek on (ADR-0015), so "no next page" is contractual, not an omission. In
	 * its place, a flag says the list was cut and a count says how much it was
	 * cut from.
	 */
	@Test
	void theListStopsAtOneHundredGroupsAndSaysItWasCut() {
		seedWideProject(120);

		Map<String, Object> body = leaderboard("&project=" + project);

		assertThat(names(body)).hasSize(100);
		assertThat(body).containsEntry("truncated", true);
		assertThat(body).doesNotContainKey("next_cursor");
		// 120 wide groups plus the five this Project already had across both environments.
		assertThat(body).containsEntry("distinct_groups", 125);
	}

	/** A list that fits raises no truncation notice, so the notice means something. */
	@Test
	void aListThatFitsIsNotMarkedTruncated() {
		Map<String, Object> body = leaderboard("&project=" + project + "&environment=production");

		assertThat(body).containsEntry("truncated", false);
		assertThat(names(body)).hasSizeLessThan(100);
	}

	/**
	 * The {@code LIMIT MAX_GROUPS + 1} boundary: exactly 100 ranked groups is a
	 * complete list, not a cut one — off by one here would make every full page
	 * falsely claim more.
	 */
	@Test
	void exactlyOneHundredGroupsIsNotTruncated() {
		jdbc.sql("DELETE FROM txn").update();
		seedWideProject(100);

		Map<String, Object> body = leaderboard("&project=" + project);

		assertThat(names(body)).hasSize(100);
		assertThat(body).containsEntry("truncated", false);
		assertThat(body).containsEntry("distinct_groups", 100);
	}

	// ------------------------------------------------------------------- detail

	/**
	 * The detail view a leaderboard row opens into must carry the same
	 * statistics, computed the same way — the two screens the user reads in
	 * sequence cannot disagree.
	 */
	@Test
	void theDetailViewCarriesTheSameStatisticsAsTheRow() {
		Map<String, Object> row = groups("&project=" + project + "&environment=production").get(0);

		Map<String, Object> group = detail("&project=" + project + "&name=" + encode(CHECKOUT)
				+ "&op=http.server&environment=production");

		assertThat(group).containsAllEntriesOf(row);
		assertThat(group).containsEntry("count", 10).containsEntry("total_ms", 5500.0);
		assertThat(ms(group, "p95_ms")).isCloseTo(955.0, TOLERANCE);
	}

	/**
	 * (Project, name, op) is the whole key, so an absent {@code op} means the
	 * group whose op is null, not "any op" — which would average the very
	 * things the key exists to separate.
	 */
	@Test
	void anAbsentOpResolvesToTheGroupWhoseOpIsNull() {
		Map<String, Object> group = detail(
				"&project=" + project + "&name=" + encode("GET /api/cart") + "&environment=production");

		assertThat(group).containsEntry("op", null).containsEntry("count", 5).containsEntry("total_ms", 150.0);
	}

	/**
	 * The same name under a different op is a different Transaction Group — a
	 * detail view that summed both would report statistics no leaderboard row
	 * shows.
	 */
	@Test
	void aNameCarriedByTwoOpsResolvesToTheOneAskedFor() {
		String key = "&project=" + project + "&name=" + encode(CHECKOUT) + "&environment=production";

		assertThat(detail(key + "&op=http.server")).containsEntry("count", 10).containsEntry("total_ms", 5500.0);
		assertThat(detail(key + "&op=navigation")).containsEntry("count", SAMPLE_FLOOR).containsEntry("total_ms", 250.0);
	}

	/**
	 * The name matches exactly here, unlike the leaderboard's {@code query}
	 * substring search used to narrow a long list. Once found, a group is
	 * identified by its actual name — a substring match here would silently
	 * fold every route sharing a prefix into one set of statistics.
	 */
	@Test
	void theNameMatchesExactlyRatherThanAsASubstring() {
		assertThat(detailStatus("&project=" + project + "&name=" + encode("checkout") + "&op=http.server"))
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	/**
	 * A Transaction Group too small to be ranked still has a detail view — the
	 * sample floor only keeps it out of the ranking; here the request names the
	 * group directly, and the count travels beside the percentiles so the user
	 * can judge them.
	 */
	@Test
	void aGroupBelowTheSampleFloorStillHasADetailView() {
		Map<String, Object> group = detail(
				"&project=" + project + "&name=" + encode("GET /api/orders/98217") + "&op=http.server");

		assertThat(group).containsEntry("count", 1).containsEntry("max_ms", 30_000.0);
	}

	/** The environment filter narrows the Transactions the statistics are computed from. */
	@Test
	void theDetailViewHonoursTheEnvironmentFilter() {
		String key = "&project=" + project + "&name=" + encode("GET /api/staging-only") + "&op=http.server";

		assertThat(detail(key + "&environment=staging")).containsEntry("count", SAMPLE_FLOOR);
		assertThat(detailStatus(key + "&environment=production")).isEqualTo(HttpStatus.NOT_FOUND);
	}

	/** As does the Release filter, which is how a duration change is attributed to a version. */
	@Test
	void theDetailViewHonoursTheReleaseFilter() {
		seedReleaseFixture();
		String key = "&project=" + project + "&name=" + encode(PRICING) + "&op=http.server&environment="
				+ RELEASE_FIXTURE;

		assertThat(detail(key)).containsEntry("count", 10).containsEntry("avg_ms", 300.0);
		assertThat(detail(key + "&release=" + SLOW_RELEASE)).containsEntry("count", 5).containsEntry("avg_ms", 500.0);
		assertThat(detail(key + "&release=" + FAST_RELEASE)).containsEntry("count", 5).containsEntry("avg_ms", 100.0);
	}

	/**
	 * The 30-day cap applies here too — the header is read beside the row it
	 * opened from, over the same global range filter, so it must agree with
	 * the number the user just clicked.
	 */
	@Test
	void anAllTimeDetailRequestIsClampedToThirtyDaysAndSaysSo() {
		Map<String, Object> body = detailBody("&project=" + project + "&name=" + encode(CHECKOUT) + "&op=http.server");

		assertThat(body.get("range_clamped")).isEqualTo(true);
		assertThat(window(body)).isEqualTo(30 * 24 * 60);
	}

	/** And the clamp excludes what falls outside it, rather than only relabelling the window. */
	@Test
	void aDetailWindowWiderThanTheCapExcludesWhatFallsOutsideIt() {
		Instant old = ANCHOR.minus(60, ChronoUnit.DAYS);
		partitions.ensurePartition(PartitionManager.TXN, old);
		for (int i = 0; i < SAMPLE_FLOOR; i++) {
			seedAt(old, project, "GET /api/ancient", "http.server", "production", 9999.0);
		}

		String key = "&project=" + project + "&name=" + encode("GET /api/ancient") + "&op=http.server";

		// Every Transaction here is outside the clamped window, so there's no group left to describe.
		ResponseEntity<Map> empty = get(
				GROUP + "?" + (key + "&from=" + ANCHOR.minus(90, ChronoUnit.DAYS)).replaceFirst("^&", ""),
				adminCookie);
		assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(empty.getBody()).containsEntry("range_clamped", true).containsKeys("from", "to");
		assertThat(window(empty.getBody())).isEqualTo(30 * 24 * 60);
		// The clamp excluded it, not a missing fixture — the same rows are found inside the cap.
		assertThat(detail(key + "&from=" + old.minus(1, ChronoUnit.DAYS) + "&to=" + old.plus(1, ChronoUnit.DAYS)))
			.containsEntry("count", SAMPLE_FLOOR);
	}

	/** A window inside the cap is answered as asked, with no notice raised. */
	@Test
	void aDetailWindowInsideTheCapIsNotClamped() {
		Instant from = ANCHOR.minus(1, ChronoUnit.DAYS);
		Instant to = ANCHOR.plus(1, ChronoUnit.DAYS);

		Map<String, Object> body = detailBody("&project=" + project + "&name=" + encode(CHECKOUT)
				+ "&op=http.server&from=" + from + "&to=" + to);

		assertThat(body.get("range_clamped")).isEqualTo(false);
		assertThat(body.get("from")).isEqualTo(from.toString());
		assertThat(body.get("to")).isEqualTo(to.toString());
	}

	@Test
	void anInvalidDetailWindowIsRejected() {
		ResponseEntity<Map> response = get(GROUP + "?project=" + project + "&name=" + encode(CHECKOUT) + "&from="
				+ ANCHOR + "&to=" + ANCHOR, adminCookie);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).containsEntry("detail", "from must be before to");
	}

	/**
	 * A Transaction Group in another Project is a different Transaction Group, however
	 * identically it is named — the key leads with the Project.
	 */
	@Test
	void theProjectIsPartOfTheKeyRatherThanAFilterOverIt() {
		assertThat(detailStatus("&project=" + otherProject + "&name=" + encode(CHECKOUT) + "&op=http.server"))
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	/** Members inspect telemetry — the detail view is not Admin-only either. */
	@Test
	void aMemberCanReadTheDetailView() {
		createUser("detail-member@test.local", "member-password", "member");
		String memberCookie = login("detail-member@test.local", "member-password");

		ResponseEntity<Map> response = get(
				GROUP + "?project=" + project + "&name=" + encode(CHECKOUT) + "&op=http.server", memberCookie);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsKey("group");
	}

	@Test
	void theDetailViewRequiresASession() {
		assertThat(rest.getForEntity(url(GROUP + "?project=" + project + "&name=x"), String.class).getStatusCode())
			.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// -------------------------------------------------------------------- trend

	/**
	 * Bucket width comes from the shared ladder, keyed to the range, pinned as
	 * an exact table (not just a point count) per {@code TimeBucketsTest}'s
	 * reasoning. ADR-0015 caps this window at 30 days, so 6 hours is the widest
	 * bucket reachable here, and {@code 1w} — the ladder's last rung — is
	 * unreachable from this endpoint.
	 */
	@Test
	void theBucketWidthIsChosenByTheLadderForTheRequestedRange() {
		String key = "&project=" + project + "&name=" + encode(CHECKOUT) + "&op=http.server";

		// No `from` is "All time", which the 30-day cap resolves to a 30-day window.
		assertThat(trend(key).get("bucket_seconds")).isEqualTo(6 * 60 * 60);
		assertThat(trend(key + over(TREND_ANCHOR, 24 * 60)).get("bucket_seconds")).isEqualTo(15 * 60);
		assertThat(trend(key + over(TREND_ANCHOR, 60)).get("bucket_seconds")).isEqualTo(60);
	}

	/**
	 * Bucketed statistics against a fixture with three shapes the chart must
	 * tell apart: a flat stretch, a tail-only spike, and a step change in both
	 * series. Empty intervals are absent rather than zero — a bucket with no
	 * Transactions has no p50, and a zero would draw a false dive to the axis.
	 */
	@Test
	void theTrendCarriesBucketedPercentilesAndCounts() {
		seedTrendFixture();

		Map<String, Object> body = detailBody(trendKey() + over(TREND_ANCHOR, 60));
		List<Map<String, Object>> points = points(body);

		// Three non-empty minutes out of sixty, in time order.
		assertThat(points).hasSize(3);
		assertThat(points).extracting(p -> p.get("start"))
			.containsExactly(TREND_ANCHOR.toString(), TREND_ANCHOR.plus(10, ChronoUnit.MINUTES).toString(),
					TREND_ANCHOR.plus(20, ChronoUnit.MINUTES).toString());
		assertThat(points).extracting(p -> p.get("count")).containsExactly(5, 5, 5);

		// Flat: five identical Transactions, so both series sit on the same value.
		assertThat(ms(points.get(0), "p50_ms")).isCloseTo(100.0, TOLERANCE);
		assertThat(ms(points.get(0), "p95_ms")).isCloseTo(100.0, TOLERANCE);
		// Tail only: one slow Transaction among four lifts p95 not p50 — interpolated 80% from 100 to 1000 = 820.
		assertThat(ms(points.get(1), "p50_ms")).isCloseTo(100.0, TOLERANCE);
		assertThat(ms(points.get(1), "p95_ms")).isCloseTo(820.0, TOLERANCE);
		// Step change: everything got slower, so both series move together.
		assertThat(ms(points.get(2), "p50_ms")).isCloseTo(500.0, TOLERANCE);
		assertThat(ms(points.get(2), "p95_ms")).isCloseTo(500.0, TOLERANCE);

		// p99 is on the header, not the chart — over a bucket it's computed from too few samples and is mostly noise.
		assertThat(points.get(0)).doesNotContainKey("p99_ms");
	}

	/**
	 * Buckets and header statistics describe exactly the same Transactions —
	 * the trend query is bound to the reported window rather than widened onto
	 * the bucket grid, unlike the Log Timeline.
	 */
	@Test
	void theTrendCoversExactlyTheTransactionsTheStatisticsDo() {
		seedTrendFixture();

		Map<String, Object> body = detailBody(trendKey() + over(TREND_ANCHOR, 60));
		List<Map<String, Object>> points = points(body);

		long bucketed = points.stream().mapToLong(p -> ((Number) p.get("count")).longValue()).sum();
		assertThat(bucketed).isEqualTo(((Number) group(body).get("count")).longValue());
	}

	/**
	 * The grid points are placed on is the window's start floored onto bucket
	 * boundaries, reported separately since the client indexes off
	 * {@code date_bin}'s actual grid. The window itself is not moved to meet it
	 * — a request starting 90s into a bucket excludes that bucket's
	 * Transactions from both stats and chart, even though the grid start
	 * floors back to it.
	 */
	@Test
	void theTrendReportsItsGridWithoutWideningTheWindow() {
		seedTrendFixture();
		Instant from = TREND_ANCHOR.plusSeconds(90);

		Map<String, Object> body = detailBody(
				trendKey() + "&from=" + from + "&to=" + TREND_ANCHOR.plus(1, ChronoUnit.HOURS));
		Map<String, Object> trend = cast(body.get("trend"));

		assertThat(body.get("from")).isEqualTo(from.toString());
		assertThat(trend.get("from")).isEqualTo(TREND_ANCHOR.plusSeconds(60).toString());
		// The first minute is before `from`, so neither the chart nor the header counts it.
		assertThat(points(body)).hasSize(2);
		assertThat(group(body).get("count")).isEqualTo(10);
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

	/**
	 * {@code count} distinct Transaction Groups, each just clearing the sample
	 * floor — the shape an unparameterized-URL Project produces, but rankable.
	 */
	private void seedWideProject(int count) {
		for (int i = 0; i < count; i++) {
			for (int sample = 0; sample < SAMPLE_FLOOR; sample++) {
				seed(project, "GET /api/wide/" + i, "http.server", "production", 2000.0);
			}
		}
	}

	/**
	 * Three Transaction Groups whose four rankings are four different orders,
	 * so each sort assertion can actually fail:
	 *
	 * <pre>
	 *   group                              count  total  p50     p95
	 *   GET /api/search   40 x 200ms          40   8000  200     200
	 *   GET /api/reports   8 x 300ms           8   2400  300     300
	 *   POST /api/import   9 x 10ms + 1000ms  10   1090   10   554.5
	 *
	 *   total_ms: search, reports, import     p50: reports, search, import
	 *   count:    search, import, reports     p95: import, reports, search
	 * </pre>
	 */
	private void seedSortFixture() {
		for (int i = 0; i < 40; i++) {
			seed(project, HOT, "http.server", SORT_FIXTURE, 200.0);
		}
		for (int i = 0; i < 8; i++) {
			seed(project, SLOW, "http.server", SORT_FIXTURE, 300.0);
		}
		for (int i = 0; i < 9; i++) {
			seed(project, SPIKY, "http.server", SORT_FIXTURE, 10.0);
		}
		seed(project, SPIKY, "http.server", SORT_FIXTURE, 1000.0);
	}

	/**
	 * One Transaction Group that got five times slower between two Releases.
	 * It's one group, not two — Release filters the aggregate's input and
	 * isn't part of the (Project, name, op) key.
	 */
	private void seedReleaseFixture() {
		for (int i = 0; i < SAMPLE_FLOOR; i++) {
			seedRelease(FAST_RELEASE, PRICING, 100.0);
			seedRelease(SLOW_RELEASE, PRICING, 500.0);
		}
	}

	/**
	 * One Transaction Group across three one-minute intervals, ten minutes apart:
	 *
	 * <pre>
	 *   interval          durations                p50   p95
	 *   +0m   flat        5 x 100ms                100   100
	 *   +10m  tail only   4 x 100ms + 1 x 1000ms   100   820
	 *   +20m  step        5 x 500ms                500   500
	 * </pre>
	 *
	 * The gaps between intervals are minutes with no Transactions, asserted absent.
	 */
	private void seedTrendFixture() {
		for (int i = 0; i < SAMPLE_FLOOR; i++) {
			seedAt(TREND_ANCHOR, project, TRENDING, "http.server", TREND_FIXTURE, 100.0);
			seedAt(TREND_ANCHOR.plus(20, ChronoUnit.MINUTES), project, TRENDING, "http.server", TREND_FIXTURE, 500.0);
		}
		Instant tail = TREND_ANCHOR.plus(10, ChronoUnit.MINUTES);
		for (int i = 0; i < SAMPLE_FLOOR - 1; i++) {
			seedAt(tail, project, TRENDING, "http.server", TREND_FIXTURE, 100.0);
		}
		seedAt(tail, project, TRENDING, "http.server", TREND_FIXTURE, 1000.0);
	}

	/** The trend fixture's Transaction Group, as the detail endpoint is asked for it. */
	private String trendKey() {
		return "&project=" + project + "&name=" + encode(TRENDING) + "&op=http.server&environment=" + TREND_FIXTURE;
	}

	/** A window of {@code minutes} starting at {@code start}, as query params. */
	private String over(Instant start, long minutes) {
		return "&from=" + start + "&to=" + start.plus(minutes, ChronoUnit.MINUTES);
	}

	/** The bucketed series of the one Transaction Group a detail request resolves to. */
	private Map<String, Object> trend(String key) {
		return cast(detailBody(key).get("trend"));
	}

	private List<Map<String, Object>> points(Map<String, Object> body) {
		Map<String, Object> trend = cast(body.get("trend"));
		return cast(trend.get("points"));
	}

	private Map<String, Object> group(Map<String, Object> body) {
		return cast(body.get("group"));
	}

	private void seed(long projectId, String name, String op, String environment, double durationMs) {
		seedAt(ANCHOR, projectId, name, op, environment, null, durationMs);
	}

	private void seedRelease(String release, String name, double durationMs) {
		seedAt(ANCHOR, project, name, "http.server", RELEASE_FIXTURE, release, durationMs);
	}

	private void seedAt(Instant start, long projectId, String name, String op, String environment, double durationMs) {
		seedAt(start, projectId, name, op, environment, null, durationMs);
	}

	private void seedAt(Instant start, long projectId, String name, String op, String environment, String release,
			double durationMs) {
		jdbc.sql("""
				INSERT INTO txn (id, project_id, environment, release, trace_id, span_id, name, op, start_ts, end_ts,
				                 duration_ms, status)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ok')
				""")
			.param(UUID.randomUUID())
			.param(projectId)
			.param(environment)
			.param(release)
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

	/** The one Transaction Group a detail request resolves to. */
	private Map<String, Object> detail(String key) {
		return cast(detailBody(key).get("group"));
	}

	private Map<String, Object> detailBody(String key) {
		ResponseEntity<Map> response = get(GROUP + "?" + key.replaceFirst("^&", ""), adminCookie);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return cast(response.getBody());
	}

	private HttpStatusCode detailStatus(String key) {
		return get(GROUP + "?" + key.replaceFirst("^&", ""), adminCookie).getStatusCode();
	}

	/** Names carry slashes and braces, so they reach the endpoint encoded. */
	private String encode(String value) {
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
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

	/**
	 * Passed as a {@link java.net.URI}, not a String — a String is a URI
	 * template to {@code RestTemplate}, which would expand {@code {id}} in a
	 * transaction name and re-encode an already-encoded {@code %2F}. The path
	 * arrives here already encoded, and a URI is what stops it being encoded
	 * twice.
	 */
	private ResponseEntity<Map> get(String path, String cookie) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, cookie);
		return rest.exchange(URI.create(url(path)), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
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
