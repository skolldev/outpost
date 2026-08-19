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
 * {@code GET /transaction-groups} end to end (#159, #160): the Performance
 * leaderboard's wire shape, its statistics against a hand-computed fixture, and the
 * rules the client cannot enforce for itself — that (Project, name, op) is the whole
 * grouping key, that the window is capped at 30 days and says so when it bites, and
 * that a group too small to have a percentile is excluded from the ranking while
 * still being counted in the cardinality the page warns on.
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

	/**
	 * The key travels in query params rather than in the path: a transaction name
	 * contains slashes — {@code GET /api/checkout/{id}} is a name, not a path — so it
	 * cannot be a path segment without encoding the same string into a second shape on
	 * the wire.
	 */
	private static final String GROUP = "/api/internal/transaction-groups/detail";

	private static final String CHECKOUT = "GET /api/checkout/{id}";

	/**
	 * The sort and Release fixtures live in Environment Names of their own so they can
	 * be filtered to exactly, and so the tests that count the groups the base fixture
	 * holds are not rewritten every time one is added. Both are seeded per test rather
	 * than in {@code setUp}, for the same reason.
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
	 * The controller's minimum-sample floor, restated rather than imported: it is part
	 * of the contract this test speaks for, and a test that read the constant would
	 * still pass if someone changed it to 1.
	 */
	private static final int SAMPLE_FLOOR = 5;

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

		// Every group meant to be ranked carries at least SAMPLE_FLOOR Transactions;
		// anything smaller is excluded, and a fixture that ignored that would be testing
		// the floor rather than what it was written to test.

		// Group A — ten Transactions, 100…1000 ms. Every statistic below is read off
		// this list by hand: total 5500, avg 550, max 1000, p50 550, p95 955, p99 991.
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
		// Group D — one Transaction, half a minute long. Its total, max and every
		// percentile would top all three of the above, and none of them is a statistic:
		// they are one duration wearing different labels.
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

		assertThat(cart).containsEntry("op", null).containsEntry("count", 5).containsEntry("total_ms", 150.0);
	}

	/**
	 * The sample floor, at the point where it matters most: Group D's single 30-second
	 * Transaction beats every other group in the fixture on total time, on max, and on
	 * all three percentiles, so if it can be ranked at all it is ranked first.
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
	 * The cardinality count is taken <em>before</em> the floor, and that is the whole
	 * reason it is useful. The Project this warning exists for emits a Transaction
	 * Group per unparameterized URL, each holding one or two Transactions — precisely
	 * what the floor removes — so a count taken after it would come back near zero on
	 * the only data that needs to be reported.
	 */
	@Test
	void theDistinctGroupCountIncludesGroupsTheFloorExcluded() {
		Map<String, Object> body = leaderboard("&project=" + project + "&environment=production");

		// A, B, C and the one-sample D. Three are ranked; all four are counted.
		assertThat(body).containsEntry("distinct_groups", 4);
		assertThat(names(body)).hasSize(3);
	}

	/**
	 * Each sort ranks the same three groups differently, and the fixture is built so
	 * that <b>no two of the four orders agree</b> — see {@link #seedSortFixture()}. A
	 * controller that ignored {@code sort}, or mapped two of them to the same
	 * expression, therefore fails here rather than passing on a list that happened to
	 * come out the same way.
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
	 * An unrecognised sort is rejected, and the message says what the endpoint does
	 * accept — a client that guessed wrong can only fix itself if it is told the set.
	 * Coercing it to the default instead would hand back a different ranking than the
	 * one asked for, which the client would then read as the one it asked for.
	 */
	@Test
	void anUnrecognisedSortIsRejected() {
		ResponseEntity<Map> response = get(GROUPS + "?project=" + project + "&sort=worst", adminCookie);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat((String) response.getBody().get("detail")).contains("total_ms", "p95", "p50", "count");
	}

	/**
	 * And a sort carrying SQL is rejected by the same whitelist rather than reaching a
	 * statement. The order is chosen from a map of literals written in the controller;
	 * a request supplies a key, never an expression, so there is nothing here to
	 * escape and nothing to get wrong.
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
	 * The cardinality count is taken over the searched slice too. It annotates the
	 * list, so a count over a wider set than the list it sits beside would warn about
	 * names the user has just filtered away.
	 */
	@Test
	void theNameSearchNarrowsTheCardinalityCountWithIt() {
		Map<String, Object> body = leaderboard("&project=" + project + "&environment=production&query=checkout");

		// The two ops of the checkout group, and nothing else — not the four the
		// unsearched window holds.
		assertThat(body).containsEntry("distinct_groups", 2);
	}

	/**
	 * The Release filter narrows the Transactions that are aggregated, and that is the
	 * whole point of it: Release is <b>not</b> part of the Transaction Group key, so
	 * one group spanning two versions stays one group, and filtering to a version
	 * re-states its statistics for that version. This is what "attribute a change in
	 * duration to a specific version" means in practice — the same group, twice, at a
	 * fifth of the duration.
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
		// Above the sample floor, so the clamp is the only thing that can exclude it.
		for (int i = 0; i < SAMPLE_FLOOR; i++) {
			seedAt(old, project, "GET /api/ancient", "http.server", "production", 9999.0);
		}

		Map<String, Object> body = leaderboard("&project=" + project + "&from=" + ANCHOR.minus(90, ChronoUnit.DAYS));

		assertThat(body.get("range_clamped")).isEqualTo(true);
		assertThat(window(body)).isEqualTo(30 * 24 * 60);
		// 5 x 9999 ms would top a total-time ranking if the clamp had not excluded it.
		assertThat(names(body)).doesNotContain("GET /api/ancient");
		// And the cardinality count is taken over the clamped window too, or it would
		// warn about names that are not in the list for a reason other than truncation.
		// Five, not four: with no environment filter the staging group counts, because
		// environment filters the input and is not part of the grouping key.
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
	 * The list stops at 100 groups and offers no cursor — an aggregate has no key to
	 * seek on (ADR-0015), so "there is no next page" is part of the contract rather
	 * than an omission. What replaces the cursor is a flag saying the list was cut and
	 * a count of everything it was cut from, so the user knows they are reading the
	 * top of a longer list rather than all of it.
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
	 * The boundary the {@code LIMIT MAX_GROUPS + 1} probe decides: exactly 100 ranked
	 * groups is a complete list, not a cut one. Off by one here means every full page
	 * tells the user there is more to see when there is not.
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
	 * The detail view a leaderboard row opens into carries the same statistics for the
	 * one group, computed the same way — this is the assertion that the two screens the
	 * user reads in sequence cannot disagree.
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
	 * (Project, name, op) is the whole key, so an absent {@code op} means the group
	 * whose op is <em>null</em> — not "any op", which names a set of Transaction Groups
	 * rather than one and would average the very things the key exists to separate.
	 */
	@Test
	void anAbsentOpResolvesToTheGroupWhoseOpIsNull() {
		Map<String, Object> group = detail(
				"&project=" + project + "&name=" + encode("GET /api/cart") + "&environment=production");

		assertThat(group).containsEntry("op", null).containsEntry("count", 5).containsEntry("total_ms", 150.0);
	}

	/**
	 * And it is that group only. The same name under an op is a different Transaction
	 * Group, and asking for one must not collect the other — a detail view that summed
	 * both would report statistics no row on the leaderboard shows.
	 */
	@Test
	void aNameCarriedByTwoOpsResolvesToTheOneAskedFor() {
		String key = "&project=" + project + "&name=" + encode(CHECKOUT) + "&environment=production";

		assertThat(detail(key + "&op=http.server")).containsEntry("count", 10).containsEntry("total_ms", 5500.0);
		assertThat(detail(key + "&op=navigation")).containsEntry("count", SAMPLE_FLOOR).containsEntry("total_ms", 250.0);
	}

	/**
	 * The name matches exactly, not as a substring. The leaderboard's {@code query} is a
	 * substring search because it is how a long list is narrowed to find a group; once
	 * found, the group is identified by the name it actually has, and a substring here
	 * would silently fold every route sharing a prefix into one set of statistics.
	 */
	@Test
	void theNameMatchesExactlyRatherThanAsASubstring() {
		assertThat(detailStatus("&project=" + project + "&name=" + encode("checkout") + "&op=http.server"))
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	/**
	 * A Transaction Group too small to be ranked still has a detail view. The sample
	 * floor keeps a one-sample group from taking a slot in a <em>ranking</em>; nothing
	 * is ranked here, the request names the group, and the count travels beside the
	 * percentiles so the user can see what they are worth.
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
	 * The 30-day cap applies here too, and says so. The header is read beside the row it
	 * was opened from, over the same global range filter — a detail view that quietly
	 * covered a different window than the list would disagree with the number the user
	 * just clicked.
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

		// Every Transaction it holds is outside the clamped window, so there is no group
		// left to describe.
		assertThat(detailStatus(key + "&from=" + ANCHOR.minus(90, ChronoUnit.DAYS))).isEqualTo(HttpStatus.NOT_FOUND);
		// And the clamp is what excluded it, not a fixture that was never there: a window
		// inside the cap, over the same rows, finds it.
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
	 * {@code count} distinct Transaction Groups, each just clearing the sample floor —
	 * the shape a Project with unparameterized URLs produces, minus the part where its
	 * groups are too small to rank.
	 */
	private void seedWideProject(int count) {
		for (int i = 0; i < count; i++) {
			for (int sample = 0; sample < SAMPLE_FLOOR; sample++) {
				seed(project, "GET /api/wide/" + i, "http.server", "production", 2000.0);
			}
		}
	}

	/**
	 * Three Transaction Groups whose four rankings are four <em>different</em> orders,
	 * which is what makes each sort assertion able to fail:
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
	 *
	 * The shapes are the three the sorts exist to tell apart: a hot path that is fine
	 * but called constantly, an endpoint that is uniformly slow, and one that is fast
	 * until it is not.
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
	 * One Transaction Group that got five times slower between two Releases — the
	 * question the Release filter exists to answer. It is one group, not two: Release
	 * filters the input to the aggregate and is not part of the (Project, name, op)
	 * key.
	 */
	private void seedReleaseFixture() {
		for (int i = 0; i < SAMPLE_FLOOR; i++) {
			seedRelease(FAST_RELEASE, PRICING, 100.0);
			seedRelease(SLOW_RELEASE, PRICING, 500.0);
		}
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
	 * Passed as a {@link java.net.URI} rather than a String: a String is a URI
	 * <em>template</em> to {@code RestTemplate}, which expands {@code {id}} in a
	 * transaction name and re-encodes the {@code %2F} of an already-encoded one. The
	 * path arrives here encoded, and a URI is what stops it being encoded twice.
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
