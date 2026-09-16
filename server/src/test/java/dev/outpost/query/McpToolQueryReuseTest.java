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
 * Enforces ADR-0016: Tools must call the query controllers' {@code build…Query}
 * factories instead of carrying copies of their SQL, so a factory change can't
 * silently diverge from what its guard checked. The equality tests below also pin
 * argument order, since several factories take consecutive same-typed parameters
 * that would compile and run silently wrong if transposed.
 */
class McpToolQueryReuseTest {

	/** Where the Tools live. Resolved from the Gradle project directory, which is {@code server/}. */
	private static final Path TOOLS = Path.of("src/main/java/dev/outpost/query");

	/**
	 * The Tools allowed to declare SQL, because each asks a question no controller
	 * asks, so the statement is written in the Tool and guarded on its own instead.
	 * Anything added to this set needs the same treatment.
	 */
	private static final Set<String> TOOLS_WITH_THEIR_OWN_SQL = Set.of("IssueContextTool.java",
			"TransactionSearchTool.java");

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
				// Matches "SELECT … FROM" rather than bare "SELECT" to avoid flagging prose.
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

	/** Distinct values per parameter: identical placeholders would make a transposition invisible. */
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
	 * The uptime read has one definition. {@code uptime_status} reuses it through
	 * {@link dev.outpost.uptime.UptimeStatusService} rather than a
	 * {@code build…Query} factory in this package, so
	 * {@link #noToolDeclaresSqlOfItsOwn()} — which scans only {@code dev.outpost.query}
	 * — would not catch a regression here.
	 */
	@Test
	void theUptimeReadIsNotDeclaredTwice() throws IOException {
		Path controller = Path.of("src/main/java/dev/outpost/uptime/UptimeController.java");
		assertThat(controller).as("the uptime controller is not where this test expects it").isRegularFile();

		String text = Files.readString(controller, StandardCharsets.UTF_8);
		assertThat(text)
			.as("UptimeController declares statements over the uptime read tables again; it must delegate to "
					+ "UptimeStatusService, which uptime_status reads through too")
			.doesNotContain("FROM uptime_check")
			.doesNotContain("FROM uptime_incident");
	}

	/**
	 * {@code get_event_raw} runs the event detail page's row lookup and <em>not</em>
	 * the two neighbour probes beside it, which is where that page's cost actually is.
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
	 * Tool renames them — {@code p95_ms} rather than {@code p95}, per ADR-0014's
	 * rule that a duration always carries its unit.
	 */
	@Test
	void everyRankingTheToolOffersIsOneTheLeaderboardWhitelists() {
		assertThat(PerformanceOverviewTool.sortKeys()).isNotEmpty()
			.allSatisfy(sort -> assertThat(TransactionGroupController.sortKeys())
				.as("performance_overview sort %s", sort)
				.contains(PerformanceOverviewTool.controllerSort(sort)));
	}

	/**
	 * The enumerated parameters are declared twice over: once as the {@code enum}
	 * whose constants the JSON Schema advertises, and once as the whitelist the
	 * statement resolves a value through. The two must be the same strings, or the
	 * schema advertises a ranking the statement cannot bind, or hides one it can.
	 */
	@Test
	void everyEnumeratedParameterAdvertisesExactlyTheValuesItsWhitelistAccepts() {
		assertThat(names(IssueSearchTool.Sort.values())).containsExactlyInAnyOrderElementsOf(IssueSearchTool.sortKeys());
		assertThat(names(IssueSearchTool.Status.values()))
			.containsExactlyInAnyOrderElementsOf(IssueSearchTool.STATUSES);
		assertThat(names(TransactionSearchTool.Sort.values()))
			.containsExactlyInAnyOrderElementsOf(TransactionSearchTool.sortKeys());
		assertThat(names(PerformanceOverviewTool.Sort.values()))
			.containsExactlyInAnyOrderElementsOf(PerformanceOverviewTool.sortKeys());
	}

	private static List<String> names(Enum<?>[] constants) {
		return Stream.of(constants).map(Enum::name).toList();
	}

}
