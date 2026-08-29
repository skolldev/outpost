package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-0016's reuse rule, asserted rather than trusted: the MCP Surface's Tools
 * call the query controllers' {@code build…Query} factories, and do not carry
 * copies of their SQL.
 *
 * <p>The rule is a performance rule, not a tidiness one, and this is the test
 * that keeps it true. A copied statement looks identical on the day it is copied
 * and diverges silently afterwards — the controller gains a predicate, an index
 * is added for the shape it now sends, and the Tool goes on running the old one
 * with the guards still green because the guards {@code EXPLAIN} the factory. That
 * is #126's failure with the guard aimed at the wrong statement instead of at the
 * wrong shape.
 *
 * <p>Two assertions, because either alone is escapable. {@link
 * #noToolDeclaresSqlOfItsOwn()} says no Tool has SQL in it at all, which catches
 * a statement pasted in beside the factory call; the equality tests say the
 * factories are called with the arguments straight through, which catches a
 * wrapper that quietly drops one.
 */
class McpToolQueryReuseTest {

	/** Where the Tools live. Resolved from the Gradle project directory, which is {@code server/}. */
	private static final Path TOOLS = Path.of("src/main/java/dev/outpost/query");

	/**
	 * The only Tool allowed to declare SQL, and the reason ADR-0016 says the reuse
	 * rule is not an exemption from guarding: its Issue + Project + latest-Event join
	 * and its Trace summary are questions no controller asks, so they are written
	 * here and guarded by {@code IssueContextPerformanceTest}. Anything added to this
	 * set needs the same treatment.
	 */
	private static final Set<String> TOOLS_WITH_THEIR_OWN_SQL = Set.of("IssueContextTool.java");

	@Test
	void noToolDeclaresSqlOfItsOwn() throws IOException {
		assertThat(TOOLS).as("the Tool sources are not where this test expects them").isDirectory();

		List<String> offenders = new ArrayList<>();
		try (Stream<Path> sources = Files.list(TOOLS)) {
			for (Path source : sources.filter(path -> path.getFileName().toString().contains("Tool")).toList()) {
				String name = source.getFileName().toString();
				if (TOOLS_WITH_THEIR_OWN_SQL.contains(name)) {
					continue;
				}
				// A bare "SELECT" in a comment would be a false positive; "SELECT … FROM"
				// on one statement is what a pasted query looks like and prose does not.
				String text = Files.readString(source, StandardCharsets.UTF_8);
				if (text.contains("SELECT ") && text.contains(" FROM ")) {
					offenders.add(name);
				}
			}
		}
		assertThat(offenders)
			.as("Tools carrying SQL of their own. Call the controller's build…Query factory instead, so the "
					+ "statement arrives with its performance guard (ADR-0016); if the question is genuinely new, "
					+ "write a guard for it and add the file to TOOLS_WITH_THEIR_OWN_SQL")
			.isEmpty();
	}

	@Test
	void findIssuesRunsTheIssueListsOwnStatement() {
		Instant to = Instant.now();
		Instant from = to.minus(14, ChronoUnit.DAYS);
		List<Long> project = List.of(7L);
		List<String> environment = List.of("production");

		assertThat(IssueSearchTool.buildIssueSearchQuery(project, environment, "unresolved", "shop@1.0.0", from, to,
				"checkout", "last_seen", null))
			.isEqualTo(IssueController.buildIssueQuery(project, environment, "unresolved", "shop@1.0.0", from, to,
					"checkout", "last_seen", null));
	}

	@Test
	void searchLogsRunsTheLogStreamsOwnStatement() {
		Instant to = Instant.now();
		Instant from = to.minus(14, ChronoUnit.DAYS);
		List<String> attr = List.of("order.id=137");

		assertThat(LogSearchTool.buildLogSearchQuery(List.of(7L), List.of("production"), List.of("error"), null,
				"shop@1.0.0", "timeout", attr, from, to, null))
			.isEqualTo(LogController.buildLogQuery(List.of(7L), List.of("production"), List.of("error"), null,
					"shop@1.0.0", "timeout", attr, from, to, null));
	}

	/**
	 * {@code get_issue_context}'s surrounding Log Records, which were the first
	 * instance of this rule and are the one the slice in #178 shipped.
	 */
	@Test
	void theSurroundingLogWindowRunsTheLogStreamsOwnStatement() {
		Instant at = Instant.now();

		assertThat(IssueContextTool.buildSurroundingLogQuery(7L, at.minus(5, ChronoUnit.MINUTES), at))
			.isEqualTo(LogController.buildLogQuery(List.of(7L), null, null, null, null, null, null,
					at.minus(5, ChronoUnit.MINUTES), at, null));
	}

	@Test
	void performanceOverviewRunsTheLeaderboardsOwnStatements() {
		Instant to = Instant.now();
		Instant from = to.minus(7, ChronoUnit.DAYS);
		List<Long> project = List.of(7L);
		List<String> environment = List.of("production");

		for (String sort : TransactionGroupController.sortKeys()) {
			assertThat(PerformanceOverviewTool.buildPerformanceOverviewQuery(project, environment, "shop@1.0.0",
					"checkout", sort, from, to))
				.as("performance_overview sorted by %s", sort)
				.isEqualTo(TransactionGroupController.buildLeaderboardQuery(project, environment, "shop@1.0.0",
						"checkout", sort, from, to));
		}
		assertThat(PerformanceOverviewTool.buildPerformanceCardinalityQuery(project, environment, "shop@1.0.0",
				"checkout", from, to))
			.isEqualTo(TransactionGroupController.buildDistinctGroupQuery(project, environment, "shop@1.0.0",
					"checkout", from, to));
	}

	/**
	 * {@code get_event_raw} runs the event detail page's row lookup and <em>not</em>
	 * the two neighbour probes beside it, which exist so a human can step through an
	 * Issue's Events and are where that page's cost actually is. Asserted rather than
	 * left in a comment because "we left the expensive part out" stops being true
	 * quietly: adding them back is a one-line change with no visible consequence.
	 */
	@Test
	void getEventRawRunsTheEventLookupAloneAndNotThePagesNeighbourProbes() {
		UUID id = UUID.fromString("0f4c2f4e-9b3a-4a1e-8f2b-6d5c1a2b3c4d");
		List<QueryPlans.Built> page = QueryPlans.eventDetail(id, 7L, Instant.now());

		assertThat(QueryPlans.eventRaw(id).sql()).isEqualTo(IssueController.EVENT_BY_ID);
		assertThat(page).as("the event detail page's statements").hasSize(3);
		assertThat(page.get(0).sql()).isEqualTo(IssueController.EVENT_BY_ID);
		assertThat(page.stream().skip(1).map(QueryPlans.Built::sql))
			.as("the neighbour probes get_event_raw does not issue")
			.containsExactly(IssueController.NEWER_EVENT_IN_ISSUE, IssueController.OLDER_EVENT_IN_ISSUE);
	}

	/**
	 * Every ranking the Tool offers resolves to one the leaderboard whitelists. The
	 * Tool renames them — {@code p95_ms} rather than {@code p95}, because ADR-0014
	 * will not have a duration without its unit — and a rename that stopped resolving
	 * would throw at the statement rather than here.
	 */
	@Test
	void everyRankingTheToolOffersIsOneTheLeaderboardWhitelists() {
		assertThat(PerformanceOverviewTool.sortKeys()).isNotEmpty()
			.allSatisfy(sort -> assertThat(TransactionGroupController.sortKeys())
				.as("performance_overview sort %s", sort)
				.contains(PerformanceOverviewTool.controllerSort(sort)));
	}

}
