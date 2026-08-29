package dev.outpost.query;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The MCP Surface's {@code search_logs} Tool: the Log Record stream, filtered
 * the way the Logs page filters it and paged by the same keyset.
 *
 * <p>The statement is {@link LogController#buildLogQuery} — the reuse ADR-0016
 * asks for, which brings V11's ordering index and
 * {@code LogQueryPerformanceTest}'s guards with it. The window default in
 * {@link ToolSupport} is what keeps the reuse worth having: the log list treats
 * {@code from} as optional because a human always has a range picker set, and an
 * unbounded stream ordered by timestamp is the one shape here that reads every
 * partition ever created.
 *
 * <p>Two truncations, both disclosed rather than silent. A Log Record's
 * {@code body} is capped at {@link #MAX_BODY_CHARS} because a body is frequently
 * a stack trace and a hundred of them is a context window; its
 * {@code attributes} are capped at {@link #MAX_ATTRIBUTES} keys for the same
 * reason. Neither is summarized — the kept part is the received text verbatim,
 * and the caveat says how much was dropped.
 */
@Component
public class LogSearchTool {

	// next_cursor is absent rather than null when the page is the last one: the MCP
	// transport validates results against the advertised output schema, and a null
	// where a string is declared fails it.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LogSearchResult(ToolSupport.Window window, List<LogRecordPayload> log_records,
			@Nullable String next_cursor, List<String> caveats) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LogRecordPayload(String id, String timestamp, @Nullable String project_slug, String environment,
			String level, @Nullable Integer severity_number, String body, @Nullable String trace_id,
			@Nullable String span_id, @Nullable String release, Map<String, String> attributes) {
	}

	/** Characters of {@code body} kept. A body is often a stack trace; a page of them is a context window. */
	static final int MAX_BODY_CHARS = 2_000;

	/** Attribute keys kept per Log Record, in the order the SDK sent them. */
	static final int MAX_ATTRIBUTES = 25;

	private final JdbcTemplate jdbc;

	private final ToolSupport support;

	private final ObjectMapper mapper;

	public LogSearchTool(ToolSupport support, ObjectMapper mapper) {
		this.jdbc = support.jdbc();
		this.support = support;
		this.mapper = mapper;
	}

	@McpTool(name = "search_logs", title = "Search Log Records", generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(title = "Search Log Records", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = """
					Log Records matching a filter, newest first. Filters are combined with AND. Pass trace_id to \
					read everything logged during one Trace. Results are paged: pass the returned next_cursor back \
					as cursor for the next page. Read the `caveats` array — it names the window that was applied \
					and any body or attribute truncation.""")
	public LogSearchResult searchLogs(
			@McpToolParam(required = false,
					description = "Project slugs from list_projects. Omit for every Project.") List<String> project_slugs,
			@McpToolParam(required = false,
					description = "Environment Names from list_projects. Omit for every Environment.") List<String> environments,
			@McpToolParam(required = false,
					description = "Severity levels, e.g. error, warn, info. Omit for every level.") List<String> levels,
			@McpToolParam(required = false,
					description = "32-character hex Trace ID; returns only Log Records correlated with that Trace.") String trace_id,
			@McpToolParam(required = false, description = "Exact release version, e.g. shop@1.4.2.") String release,
			@McpToolParam(required = false,
					description = "Case-insensitive substring of the Log Record body.") String query,
			@McpToolParam(required = false, description = "Attribute filters as key=value, or a bare key to match "
					+ "records that carry it at all. Attribute names are visible on returned records.") List<String> attribute_filters,
			@McpToolParam(required = false, description = "Start of the window as an ISO-8601 instant. Defaults to "
					+ ToolSupport.DEFAULT_WINDOW_DAYS + " days before `to`.") String from,
			@McpToolParam(required = false,
					description = "End of the window as an ISO-8601 instant. Defaults to now.") String to,
			@McpToolParam(required = false, description = "next_cursor from a previous call.") String cursor) {

		List<String> caveats = new ArrayList<>();
		ToolSupport.Projects projects = support.projects();
		List<Long> projectIds = projects.resolve(project_slugs);
		ToolSupport.Window window = ToolSupport.window(from, to, caveats);

		SearchQuery search = buildLogSearchQuery(projectIds, environments, levels, trace_id, release, query,
				attribute_filters, window.fromInstant(), window.toInstant(), cursor);
		List<Map<String, Object>> rows = jdbc.query(search.sql(), (rs, row) -> {
			Map<String, Object> mapped = new LinkedHashMap<>();
			mapped.put("id", rs.getObject("id", UUID.class));
			mapped.put("project_id", rs.getLong("project_id"));
			mapped.put("environment", rs.getString("environment"));
			mapped.put("timestamp", rs.getTimestamp("timestamp").toInstant());
			mapped.put("trace_id", rs.getString("trace_id"));
			mapped.put("span_id", rs.getString("span_id"));
			mapped.put("level", rs.getString("level"));
			mapped.put("severity_number", rs.getObject("severity_number", Integer.class));
			mapped.put("body", rs.getString("body"));
			mapped.put("attributes", QuerySupport.parseJson(mapper, rs.getString("attributes")));
			mapped.put("release", rs.getString("release"));
			return mapped;
		}, search.params().toArray());

		KeysetPage.Page page = LogController.logPage().paginate(rows);
		if (page.nextCursor() != null) {
			caveats.add("More Log Records matched than one page holds, and the ones returned are the newest of "
					+ "them. Pass next_cursor back as cursor to read further back in time.");
		}

		boolean bodyTruncated = false;
		boolean attributesTruncated = false;
		List<LogRecordPayload> records = new ArrayList<>();
		for (Map<String, Object> row : page.rows()) {
			String body = (String) row.get("body");
			bodyTruncated |= body != null && body.length() > MAX_BODY_CHARS;
			Map<String, String> attributes = attributes((JsonNode) row.get("attributes"));
			attributesTruncated |= attributes.size() < size((JsonNode) row.get("attributes"));
			records.add(new LogRecordPayload(row.get("id").toString(), row.get("timestamp").toString(),
					projects.slug((Long) row.get("project_id")), (String) row.get("environment"),
					(String) row.get("level"), (Integer) row.get("severity_number"), ToolSupport.truncate(body, MAX_BODY_CHARS),
					(String) row.get("trace_id"), (String) row.get("span_id"), (String) row.get("release"),
					attributes));
		}
		if (bodyTruncated) {
			caveats.add("At least one body was longer than " + MAX_BODY_CHARS
					+ " characters and was cut off at that length. The kept text is the start of what was "
					+ "received, verbatim.");
		}
		if (attributesTruncated) {
			caveats.add("At least one Log Record carried more than " + MAX_ATTRIBUTES
					+ " attributes and only the first " + MAX_ATTRIBUTES + " are returned.");
		}
		return new LogSearchResult(window, records, page.nextCursor(), caveats);
	}

	/**
	 * The log stream as this Tool binds it: the controller's own factory, so the
	 * plan and its guards are the log page's own. Named here so {@code QueryPlans}
	 * can {@code EXPLAIN} the Tool's shape and {@code McpToolQueryReuseTest} can
	 * assert it is not a copy.
	 */
	static SearchQuery buildLogSearchQuery(List<Long> project, List<String> environment, List<String> level,
			String traceId, String release, String query, List<String> attr, Instant from, Instant to, String cursor) {
		return LogController.buildLogQuery(project, environment, level, traceId, release, query, attr, from, to,
				cursor);
	}

	/**
	 * Attributes flattened to strings, in the order they were stored. Values are
	 * rendered rather than typed because an Attribute holds whatever the SDK put
	 * there — a number, a bool, an object — and a typed union would cost the caller
	 * a branch on every read for information it can see in the text.
	 */
	private static Map<String, String> attributes(JsonNode node) {
		if (node == null || !node.isObject() || node.isEmpty()) {
			return Map.of();
		}
		Map<String, String> attributes = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			if (attributes.size() == MAX_ATTRIBUTES) {
				break;
			}
			JsonNode value = entry.getValue();
			attributes.put(entry.getKey(), value.isValueNode() ? value.asString() : value.toString());
		}
		return attributes;
	}

	private static int size(JsonNode node) {
		return node == null || !node.isObject() ? 0 : node.size();
	}

}
