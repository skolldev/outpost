package dev.outpost.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The MCP Surface's {@code find_issues} Tool: the Issue list, filtered the way
 * the Issues page filters it and paged by the same keyset.
 *
 * <p>Every statement here is {@link IssueController#buildIssueQuery} — the
 * reuse ADR-0016 is about. It buys the SQL, the {@code (status, sort, id)}
 * indexes #126 added, and {@code IssueQueryPerformanceTest}'s guards over them,
 * but only for requests shaped the way the UI shapes them. That is the whole
 * reason {@link #DEFAULT_STATUS} and {@link ToolSupport#DEFAULT_WINDOW_DAYS} are
 * applied server-side rather than left to the caller: a user never picks either,
 * so no index was ever tuned for their absence, and an agent omits both
 * routinely.
 *
 * <p><b>{@code status} has no "either" value, deliberately.</b> Dropping the
 * predicate is exactly the shape that made the Resolved tab cost fifteen times a
 * full table scan before #126, and the honest way to ask for both is two calls
 * whose plans are each the one that was measured.
 *
 * <p>ADR-0014 shows up in one field name: {@code events_received}, never
 * {@code event_count}. The number counts Events this installation received since
 * the Issue was opened, not occurrences in the world and not what is still in
 * retention.
 */
@Component
public class IssueSearchTool {

	// next_cursor is absent rather than null when the page is the last one: the MCP
	// transport validates results against the advertised output schema, and a null
	// where a string is declared fails it.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record IssueSearchResult(ToolSupport.Window window, String applied_status, String applied_sort,
			List<IssuePayload> issues, @Nullable String next_cursor, List<String> caveats) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record IssuePayload(long id, @Nullable String project_slug, String title, @Nullable String culprit,
			String level, String status, String first_seen, String last_seen, long events_received) {
	}

	/** What the Issues page sends when the user has picked nothing — and therefore the shape the indexes serve. */
	static final String DEFAULT_STATUS = "unresolved";

	static final List<String> STATUSES = List.of("unresolved", "resolved");

	/**
	 * The orderings the list offers, under the names this surface reports the
	 * underlying numbers by. {@code events_received} rather than the controller's
	 * {@code count}, because a caller sorting by a column it can see in the payload
	 * should not have to learn a second name for it (ADR-0014).
	 */
	private static final Map<String, String> SORTS = Map.of("last_seen", "last_seen", "events_received", "count");

	private static final String DEFAULT_SORT = "last_seen";

	private final JdbcTemplate jdbc;

	private final ToolSupport support;

	public IssueSearchTool(ToolSupport support) {
		this.jdbc = support.jdbc();
		this.support = support;
	}

	@McpTool(name = "find_issues", title = "Find Issues", generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(title = "Find Issues", readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false),
			description = """
					Issues matching a filter, newest activity first. An Issue is a group of Events sharing a \
					fingerprint. Use this to find an issue_id, then call get_issue_context for its Event, stack, \
					Log Records and Trace. Filters are combined with AND. Results are paged: pass the returned \
					next_cursor back as cursor for the next page. Read the `caveats` array — it names the defaults \
					that were applied when you omitted a parameter.""")
	public IssueSearchResult findIssues(
			@McpToolParam(required = false,
					description = "Project slugs from list_projects. Omit for every Project.") List<String> project_slugs,
			@McpToolParam(required = false, description = "Environment Names from list_projects, e.g. production. "
					+ "Matched exactly. Omit for every Environment.") List<String> environments,
			@McpToolParam(required = false, description = "unresolved (the default) or resolved. There is no value "
					+ "for both: call twice.") String status,
			@McpToolParam(required = false, description = "Exact release version, e.g. shop@1.4.2 — list_projects "
					+ "shows each Project's recent versions.") String release,
			@McpToolParam(required = false,
					description = "Case-insensitive substring of the Issue title or culprit.") String query,
			@McpToolParam(required = false, description = "Start of the window: an ISO-8601 instant, or an ISO-8601 "
					+ "duration such as PT1H or P2D meaning that far back from `to`. Defaults to "
					+ ToolSupport.DEFAULT_WINDOW_DAYS + " days before `to`.") String from,
			@McpToolParam(required = false,
					description = "End of the window as an ISO-8601 instant. Defaults to now.") String to,
			@McpToolParam(required = false,
					description = "last_seen (the default) or events_received.") String sort,
			@McpToolParam(required = false, description = "next_cursor from a previous call.") String cursor) {

		List<String> caveats = new ArrayList<>();
		ToolSupport.Projects projects = support.projects();
		List<Long> projectIds = projects.resolve(project_slugs);
		// Refused rather than bound, like an unknown slug: an exact-match filter for a
		// value nothing carries returns an empty result that reads as "nothing matched".
		support.requireKnownEnvironments(environments);
		support.requireKnownRelease(release);
		ToolSupport.Window window = ToolSupport.window(from, to, caveats);
		String appliedStatus = status(status, caveats);
		String appliedSort = sort(sort);

		SearchQuery search = buildIssueSearchQuery(projectIds, environments, appliedStatus, release,
				window.fromInstant(), window.toInstant(), query, SORTS.get(appliedSort), cursor);
		List<Map<String, Object>> rows = jdbc.query(search.sql(), (rs, row) -> {
			// The keys the keyset reads to build the next cursor, so they are the
			// controller's names rather than this payload's — see KeysetPage.KeyColumn.
			Map<String, Object> mapped = new LinkedHashMap<>();
			mapped.put("id", rs.getLong("id"));
			mapped.put("project_id", rs.getLong("project_id"));
			mapped.put("title", rs.getString("title"));
			mapped.put("culprit", rs.getString("culprit"));
			mapped.put("level", rs.getString("level"));
			mapped.put("status", rs.getString("status"));
			mapped.put("first_seen", rs.getTimestamp("first_seen").toInstant());
			mapped.put("last_seen", rs.getTimestamp("last_seen").toInstant());
			mapped.put("event_count", rs.getLong("event_count"));
			return mapped;
		}, search.params().toArray());

		KeysetPage.Page page = IssueController.issuePage(SORTS.get(appliedSort)).paginate(rows);
		if (page.nextCursor() != null) {
			caveats.add("More Issues matched than one page holds. Pass next_cursor back as cursor to read the rest, "
					+ "or narrow the filter — this page is not the whole answer.");
		}
		List<IssuePayload> issues = page.rows()
			.stream()
			.map(row -> new IssuePayload((Long) row.get("id"), projects.slug((Long) row.get("project_id")),
					(String) row.get("title"), (String) row.get("culprit"), (String) row.get("level"),
					(String) row.get("status"), row.get("first_seen").toString(), row.get("last_seen").toString(),
					(Long) row.get("event_count")))
			.toList();
		if (issues.isEmpty()) {
			// An empty list has two readings — "nothing is broken" and "you looked in the
			// wrong window" — and only one of them is a conclusion worth drawing.
			caveats.add("No Issue matched. An Issue is in the window when it was last seen after " + window.from()
					+ " and first seen before " + window.to() + ", so an older Issue that has gone quiet is "
					+ "outside it rather than absent.");
		}
		return new IssueSearchResult(window, appliedStatus, appliedSort, issues, page.nextCursor(), caveats);
	}

	/**
	 * The Issue list as this Tool binds it: the controller's own factory, so the
	 * plan and its guards are the list's own. Named here rather than called inline so
	 * {@code QueryPlans} can {@code EXPLAIN} the Tool's shape and
	 * {@code McpToolQueryReuseTest} can assert it is not a copy.
	 */
	static SearchQuery buildIssueSearchQuery(List<Long> project, List<String> environment, String status,
			String release, Instant from, Instant to, String query, String sort, String cursor) {
		return IssueController.buildIssueQuery(project, environment, status, release, from, to, query, sort, cursor);
	}

	/** Every ranking this Tool accepts, in payload names — read by the guards and the reuse test. */
	static List<String> sortKeys() {
		return List.copyOf(SORTS.keySet());
	}

	/** The controller key a payload-named ranking resolves to. */
	static String controllerSort(String sort) {
		return SORTS.get(sort);
	}

	private static String status(String requested, List<String> caveats) {
		if (requested == null || requested.isBlank()) {
			caveats.add("status was not supplied, so the default of '" + DEFAULT_STATUS
					+ "' was applied — resolved Issues are not in this result. Call again with status=resolved "
					+ "to see those.");
			return DEFAULT_STATUS;
		}
		return ToolSupport.choose(requested, Set.copyOf(STATUSES), DEFAULT_STATUS, "status");
	}

	private static String sort(String requested) {
		return ToolSupport.choose(requested, SORTS.keySet(), DEFAULT_SORT, "sort");
	}

}
