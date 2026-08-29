package dev.outpost.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
 * <p><b>The statement is the log page's; the payload is not.</b> Reuse stops at
 * the SQL, and it stops there because the two readers want opposite things. A
 * human scrolling the Logs page wants every row the stream produced, because
 * collapsing or eliding any of them hides the stream they came to read. An agent
 * asking what is breaking is spending a context window per call, and the
 * difference between the two is the difference between a page of a hundred
 * near-identical rows being the answer and being the reason there is no room
 * left for one. So this Tool takes a {@link #DEFAULT_LIMIT} well under the page
 * size, drops the fields of a Log Record that no Tool on this surface can spend,
 * hoists into {@link Common} the ones every returned record agreed on, and
 * withholds Sentry-prefixed attributes unless the caller explicitly filters on
 * one.
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
	// where a string is declared fails it. `common` is absent by the same mechanism
	// when the records agreed on nothing.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LogSearchResult(ToolSupport.Window window, @Nullable Common common,
			List<LogRecordPayload> log_records, @Nullable String next_cursor, List<String> caveats) {
	}

	/**
	 * The fields every returned Log Record carried the same value for, reported
	 * once instead of on each of them.
	 *
	 * <p>A structural fact stated as a field rather than as a caveat, for
	 * ADR-0014's reason: the alternative is a sentence at the bottom of an array
	 * telling the reader to go and merge two objects, which is both longer than the
	 * repetition it replaces and the first thing a re-summarization drops. A field
	 * named {@code common} that holds exactly the values the records omit needs no
	 * sentence.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Common(@Nullable String project_slug, @Nullable String environment, @Nullable String release) {

		boolean isEmpty() {
			return project_slug == null && environment == null && release == null;
		}
	}

	/**
	 * One Log Record.
	 *
	 * <p>No {@code id}: a Log Record's id is not a parameter of any Tool on this
	 * surface — unlike an Event id, which {@code get_event_raw} spends — so
	 * returning it costs a UUID per record for something the caller can only look
	 * at. {@code trace_id} is the identifier that leads somewhere from here, via
	 * {@code get_trace}.
	 *
	 * <p>No {@code severity_number} either: it is the numeric spelling of
	 * {@code level}, and {@code level} is the spelling the filter parameter and the
	 * caveats speak.
	 *
	 * <p>{@code project_slug}, {@code environment} and {@code release} are absent
	 * on the record when every returned record shared them — see {@link Common}.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LogRecordPayload(String timestamp, @Nullable String project_slug, @Nullable String environment,
			String level, String body, @Nullable String trace_id, @Nullable String span_id, @Nullable String release,
			Map<String, String> attributes) {
	}

	/** Characters of {@code body} kept. A body is often a stack trace; a page of them is a context window. */
	static final int MAX_BODY_CHARS = 2_000;

	/** Attribute keys kept per Log Record, in the order the SDK sent them. */
	static final int MAX_ATTRIBUTES = 25;

	/**
	 * Log Records returned when the caller names no limit. Far below the page size
	 * the statement fetches, because these are the fattest rows on the surface — a
	 * body alone runs to {@link #MAX_BODY_CHARS} — and a caller that wants more has
	 * {@code next_cursor} to say so. The statement reads the same index either way,
	 * so the limit trims the payload, not the work.
	 */
	static final int DEFAULT_LIMIT = 25;

	/** The log page's own page size: the statement's {@code LIMIT}, and so the ceiling on this one. */
	static final int MAX_LIMIT = LogController.PAGE_SIZE;

	/**
	 * Sentry-prefixed attributes withheld from the payload unless the caller names
	 * one in {@code attribute_filters}.
	 *
	 * <p>Withheld from the <em>payload</em> only — {@code attribute_filters} still
	 * matches on them, so a caller that genuinely wants to slice by SDK version
	 * can, and a key it named is returned even when it appears here.
	 */
	private static final String WITHHELD_ATTRIBUTE_PREFIX = "sentry";

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
					as cursor for the next page. Any of project_slug, environment and release that every returned \
					record shared is reported once in `common` and omitted from the records themselves. Read the \
					`caveats` array — it names the window that was applied, any body or attribute truncation, and \
					any attributes withheld.""")
	public LogSearchResult searchLogs(
			@McpToolParam(required = false,
					description = "Project slugs from list_projects. Omit for every Project.") List<String> project_slugs,
			@McpToolParam(required = false, description = "Environment Names from list_projects. Matched exactly. "
					+ "Omit for every Environment.") List<String> environments,
			@McpToolParam(required = false, description = "Severity levels, e.g. error, warn, info. Matched exactly "
					+ "against the level text the SDK sent — an SDK that logs 'warning' does not match 'warn'. Omit "
					+ "for every level.") List<String> levels,
			@McpToolParam(required = false,
					description = "32-character hex Trace ID; returns only Log Records correlated with that Trace.") String trace_id,
			@McpToolParam(required = false, description = "Exact release version, e.g. shop@1.4.2 — list_projects "
					+ "shows each Project's recent versions.") String release,
			@McpToolParam(required = false,
					description = "Case-insensitive substring of the Log Record body.") String query,
			@McpToolParam(required = false, description = "Attribute filters as key=value, or a bare key to match "
					+ "records that carry it at all. Attribute names are visible on returned records, and a key "
					+ "named here is returned even when it would otherwise be withheld.") List<String> attribute_filters,
			@McpToolParam(required = false, description = "Start of the window: an ISO-8601 instant, or an ISO-8601 "
					+ "duration such as PT1H or P2D meaning that far back from `to`. Defaults to "
					+ ToolSupport.DEFAULT_WINDOW_DAYS + " days before `to`.") String from,
			@McpToolParam(required = false,
					description = "End of the window as an ISO-8601 instant. Defaults to now.") String to,
			@McpToolParam(required = false, description = "Log Records to return. Defaults to " + DEFAULT_LIMIT
					+ ", at most " + MAX_LIMIT + ".") Integer limit,
			@McpToolParam(required = false, description = "next_cursor from a previous call.") String cursor) {

		List<String> caveats = new ArrayList<>();
		ToolSupport.Projects projects = support.projects();
		List<Long> projectIds = projects.resolve(project_slugs);
		// Refused rather than bound, like an unknown slug: an exact-match filter for a
		// value nothing carries returns an empty result that reads as "nothing matched".
		support.requireKnownEnvironments(environments);
		support.requireKnownRelease(release);
		ToolSupport.Window window = ToolSupport.window(from, to, caveats);
		int size = ToolSupport.limit(limit, DEFAULT_LIMIT, MAX_LIMIT, "Log Records", caveats);

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
			mapped.put("body", rs.getString("body"));
			mapped.put("attributes", QuerySupport.parseJson(mapper, rs.getString("attributes")));
			mapped.put("release", rs.getString("release"));
			return mapped;
		}, search.params().toArray());

		KeysetPage.Page page = LogController.logPage().paginate(rows, size);
		if (page.nextCursor() != null) {
			caveats.add("More Log Records matched than the " + size + " returned, and the ones returned are the "
					+ "newest of them. Pass next_cursor back as cursor to read further back in time, or raise "
					+ "limit to at most " + MAX_LIMIT + ".");
		}

		// Hoisted only when there are records to agree with each other: splitting a
		// single record across two objects saves nothing and costs the reader a merge.
		Common common = page.rows().size() < 2 ? null
				: new Common(constant(page.rows(), row -> projects.slug((Long) row.get("project_id"))),
						constant(page.rows(), row -> (String) row.get("environment")),
						constant(page.rows(), row -> (String) row.get("release")));
		if (common != null && common.isEmpty()) {
			common = null;
		}

		Set<String> requestedAttributes = requestedAttributes(attribute_filters);
		boolean bodyTruncated = false;
		boolean attributesTruncated = false;
		Set<String> attributesWithheld = new LinkedHashSet<>();
		List<LogRecordPayload> records = new ArrayList<>();
		for (Map<String, Object> row : page.rows()) {
			String body = (String) row.get("body");
			bodyTruncated |= body != null && body.length() > MAX_BODY_CHARS;
			Attributes attributes = attributes((JsonNode) row.get("attributes"), requestedAttributes);
			attributesTruncated |= attributes.truncated();
			attributesWithheld.addAll(attributes.withheld());
			String slug = projects.slug((Long) row.get("project_id"));
			String environment = (String) row.get("environment");
			String recordRelease = (String) row.get("release");
			records.add(new LogRecordPayload(row.get("timestamp").toString(),
					common != null && common.project_slug() != null ? null : slug,
					common != null && common.environment() != null ? null : environment, (String) row.get("level"),
					ToolSupport.truncate(body, MAX_BODY_CHARS), (String) row.get("trace_id"),
					(String) row.get("span_id"), common != null && common.release() != null ? null : recordRelease,
					attributes.kept()));
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
		if (!attributesWithheld.isEmpty()) {
			caveats.add("Sentry-prefixed attributes were withheld from the records: "
					+ String.join(", ", sorted(attributesWithheld)) + ". They are still matched by "
					+ "attribute_filters, and naming one there returns it.");
		}
		if (records.isEmpty()) {
			// Levels are the one filter that can be wrong without being refused —
			// they are free text an SDK chose, so there is no catalogue to validate
			// against, and 'warning' for 'warn' silently matches nothing.
			caveats.add("No Log Record matched between " + window.from() + " and " + window.to() + "."
					+ (levels == null || levels.isEmpty() ? ""
							: " Levels are matched exactly against the text the SDK sent — 'warn' and 'warning' are "
									+ "different levels — so a level filter that misses may be spelled differently "
									+ "than the records are; the level field is visible on any unfiltered result."));
		}
		return new LogSearchResult(window, common, records, page.nextCursor(), caveats);
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

	/** The one value {@code field} took on every row, or null if the rows disagreed or any was null. */
	@Nullable
	private static String constant(List<Map<String, Object>> rows, Function<Map<String, Object>, String> field) {
		String first = field.apply(rows.get(0));
		if (first == null) {
			return null;
		}
		for (Map<String, Object> row : rows) {
			if (!first.equals(field.apply(row))) {
				return null;
			}
		}
		return first;
	}

	/**
	 * The attribute keys the caller filtered on, which are returned whatever else
	 * is withheld: a filter the caller cannot see the result of reads as an
	 * unfiltered one.
	 */
	private static Set<String> requestedAttributes(@Nullable List<String> filters) {
		if (filters == null || filters.isEmpty()) {
			return Set.of();
		}
		Set<String> keys = new LinkedHashSet<>();
		for (String filter : filters) {
			if (filter == null || filter.isBlank()) {
				continue;
			}
			int equals = filter.indexOf('=');
			keys.add((equals < 0 ? filter : filter.substring(0, equals)).trim());
		}
		return keys;
	}

	/** The attributes kept for one Log Record, and why the rest are not there. */
	private record Attributes(Map<String, String> kept, boolean truncated, Set<String> withheld) {
	}

	/**
	 * Attributes flattened to strings, in the order they were stored, less the
	 * Sentry-prefixed keys the caller did not ask for. Values are rendered
	 * rather than typed because an Attribute holds whatever the SDK put there — a
	 * number, a bool, an object — and a typed union would cost the caller a branch
	 * on every read for information it can see in the text.
	 */
	private static Attributes attributes(@Nullable JsonNode node, Set<String> requested) {
		if (node == null || !node.isObject() || node.isEmpty()) {
			return new Attributes(Map.of(), false, Set.of());
		}
		Map<String, String> attributes = new LinkedHashMap<>();
		boolean truncated = false;
		Set<String> withheld = new LinkedHashSet<>();
		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			if (entry.getKey().startsWith(WITHHELD_ATTRIBUTE_PREFIX) && !requested.contains(entry.getKey())) {
				withheld.add(entry.getKey());
				continue;
			}
			if (attributes.size() == MAX_ATTRIBUTES) {
				truncated = true;
				break;
			}
			JsonNode value = entry.getValue();
			attributes.put(entry.getKey(), value.isValueNode() ? value.asString() : value.toString());
		}
		return new Attributes(attributes, truncated, withheld);
	}

	private static List<String> sorted(Set<String> values) {
		return values.stream().sorted().toList();
	}

}
