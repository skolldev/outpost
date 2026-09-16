package dev.outpost.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The MCP Surface's {@code find_transactions} Tool: the Transactions received
 * for one Transaction Group, slowest first, each carrying its Trace ID for
 * {@code get_trace}. Unlike other Tools here, its query is written directly
 * rather than reused from a controller, since the UI's own drill-down
 * ({@code /transaction-groups/detail}) aggregates the group and never lists
 * its rows.
 */
@Component
public class TransactionSearchTool {

	// op omitted (not null) when absent — MCP schema validation rejects null for a declared string.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TransactionSearchResult(String from, String to, boolean range_clamped, String project_slug,
			String name, @Nullable String op, String sorted_by, List<TransactionPayload> transactions,
			boolean more_transactions_matched, List<String> caveats) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TransactionPayload(String id, String trace_id, String environment, @Nullable String release,
			String start_ts, double duration_ms, @Nullable String status) {
	}

	static final int DEFAULT_LIMIT = 10;

	static final int MAX_LIMIT = 50;

	/**
	 * The orderings offered, mapped to their ORDER BY clause; also the whitelist
	 * that keeps an unrecognised value out of the statement.
	 */
	private static final Map<String, String> SORTS = sorts();

	private static Map<String, String> sorts() {
		Map<String, String> sorts = new LinkedHashMap<>();
		sorts.put("duration_ms", "duration_ms DESC");
		sorts.put("start_ts", "start_ts DESC");
		return Map.copyOf(sorts);
	}

	private static final String DEFAULT_SORT = "duration_ms";

	/** Must match {@link #SORTS}'s keys exactly — the JSON Schema enum is generated from these names. */
	public enum Sort {

		duration_ms, start_ts

	}

	private final JdbcTemplate jdbc;

	private final ToolSupport support;

	public TransactionSearchTool(ToolSupport support) {
		this.jdbc = support.jdbc();
		this.support = support;
	}

	@McpTool(name = "find_transactions", title = "Find Transactions in a group", generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(title = "Find Transactions in a group", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = """
					The Transactions received for one Transaction Group, slowest first — the drill-down behind \
					performance_overview. Each row carries the trace_id to pass to get_trace, which is how "this \
					group is slow" becomes "this is what a slow request did". Identify the group by the exact name \
					and op performance_overview reported; omit op only when the group reported none. Read the \
					`caveats` array — these are received Transactions, a sample whenever the SDK samples traces.""")
	public TransactionSearchResult findTransactions(
			@McpToolParam(description = "Project slug from list_projects. One Project — a Transaction Group "
					+ "belongs to one.") String project_slug,
			@McpToolParam(description = "The Transaction Group's exact name, as performance_overview reports "
					+ "it.") String name,
			@McpToolParam(required = false, description = "The group's op, exactly as performance_overview reports "
					+ "it. Omit only when the group reports no op — a group's identity is (name, op).") String op,
			@McpToolParam(required = false, description = "Environment Names from list_projects. Matched exactly. "
					+ "Omit for every Environment.") List<String> environments,
			@McpToolParam(required = false, description = "Exact release version, e.g. shop@1.4.2 — list_projects "
					+ "shows each Project's recent versions.") String release,
			@McpToolParam(required = false, description = "duration_ms (the default, slowest first) or start_ts "
					+ "(most recent first).") Sort sort,
			@McpToolParam(required = false, description = "Start of the window: an ISO-8601 instant, or an ISO-8601 "
					+ "duration such as PT1H or P2D meaning that far back from `to`. Defaults to "
					+ ToolSupport.DEFAULT_WINDOW_DAYS + " days before `to`; anything earlier than 30 days before "
					+ "`to` is clamped.") String from,
			@McpToolParam(required = false,
					description = "End of the window as an ISO-8601 instant. Defaults to now.") String to,
			@McpToolParam(required = false, description = "Transactions to return. Defaults to " + DEFAULT_LIMIT
					+ ", at most " + MAX_LIMIT + ".") Integer limit) {

		if (project_slug == null || project_slug.isBlank()) {
			throw new IllegalArgumentException("project_slug is required; call list_projects for the slugs this "
					+ "installation has");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name is required — the Transaction Group's exact name, as "
					+ "performance_overview reports it");
		}
		List<String> caveats = new ArrayList<>();
		ToolSupport.Projects projects = support.projects();
		long projectId = projects.resolve(List.of(project_slug)).get(0);
		support.requireKnownEnvironments(environments);
		support.requireKnownRelease(release);
		String sortedBy = sort == null ? DEFAULT_SORT : sort.name();
		int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
		String groupOp = op == null || op.isBlank() ? null : op;

		// Reuses performance_overview's window resolution (30-day clamp) so the two Tools agree on relative windows.
		ToolSupport.Window requested = ToolSupport.window(from, to, caveats);
		TransactionGroupController.Window window = TransactionGroupController.window(requested.fromInstant(),
				requested.toInstant());
		if (window.clamped()) {
			caveats.add("The window was clamped to the 30 days before " + window.to()
					+ ", the same cap performance_overview applies.");
		}

		// Fetches one row past the limit so "more matched" is answered without a second count.
		SearchQuery search = buildTransactionSearchQuery(projectId, name, groupOp, environments, release, sortedBy,
				window.from(), window.to(), size + 1);
		List<TransactionPayload> rows = jdbc.query(search.sql(),
				(rs, row) -> new TransactionPayload(rs.getObject("id", UUID.class).toString(),
						rs.getString("trace_id"), rs.getString("environment"), rs.getString("release"),
						rs.getTimestamp("start_ts").toInstant().toString(), rs.getDouble("duration_ms"),
						rs.getString("status")),
				search.params().toArray());

		boolean more = rows.size() > size;
		List<TransactionPayload> transactions = rows.subList(0, Math.min(rows.size(), size));
		if (more) {
			caveats.add("More Transactions matched than the " + size + " returned; this is the "
					+ ("duration_ms".equals(sortedBy) ? "slowest " : "most recent ") + size + " of them.");
		}
		if (transactions.isEmpty()) {
			// Group key has no catalogue to validate against, so a near-miss returns empty rather than an error.
			caveats.add("No Transaction matched between " + window.from() + " and " + window.to() + ". The group key "
					+ "is matched exactly: name and op must be character-for-character what performance_overview "
					+ "reported, and op must be supplied whenever the group reports one.");
		}
		caveats.add("These are the Transactions Outpost received, not the requests served. Sentry SDKs sample "
				+ "traces and Outpost stores no sample rate, so the slowest received Transaction is not "
				+ "necessarily the slowest request served.");
		return new TransactionSearchResult(window.from().toString(), window.to().toString(), window.clamped(),
				project_slug, name, groupOp, sortedBy, transactions, more, caveats);
	}

	/**
	 * The group's members over a window. Predicate order matches
	 * {@code idx_txn_performance} (project_id, name, op) so the query stays an
	 * index scan.
	 */
	static SearchQuery buildTransactionSearchQuery(long project, String name, @Nullable String op,
			@Nullable List<String> environment, @Nullable String release, String sort, Instant from, Instant to,
			int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT id, trace_id, environment, release, start_ts, duration_ms, status
				FROM txn WHERE project_id = ? AND name = ?""");
		List<Object> params = new ArrayList<>(List.of(project, name));
		if (op == null) {
			sql.append(" AND op IS NULL");
		}
		else {
			sql.append(" AND op = ?");
			params.add(op);
		}
		QuerySupport.appendInClause(sql, "environment", environment, params);
		if (release != null && !release.isBlank()) {
			sql.append(" AND release = ?");
			params.add(release);
		}
		sql.append(" AND start_ts >= ?");
		params.add(Timestamp.from(from));
		sql.append(" AND start_ts < ?");
		params.add(Timestamp.from(to));
		sql.append(" ORDER BY ").append(SORTS.get(sort)).append(" LIMIT ?");
		params.add(limit);
		return new SearchQuery(sql.toString(), params);
	}

	static List<String> sortKeys() {
		return List.copyOf(SORTS.keySet());
	}

}
