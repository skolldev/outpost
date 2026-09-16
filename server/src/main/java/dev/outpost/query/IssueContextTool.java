package dev.outpost.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.outpost.symbolication.Symbolicator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
 * The MCP Surface's {@code get_issue_context} Tool: an Issue, its latest Event, that Event's
 * stack and breadcrumbs, surrounding Log Records and a Trace summary, in one call. Record
 * fields are literal {@code snake_case} — the MCP transport serializes results through its own
 * {@code JsonMapper}, not the application's global {@code SNAKE_CASE} strategy, so camelCase
 * names would ship wrong.
 */
@Component
public class IssueContextTool {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record IssueContextResult(IssuePayload issue, @Nullable EventPayload latest_event,
			@Nullable ExceptionPayload exception, List<BreadcrumbPayload> breadcrumbs,
			@Nullable LogWindowPayload log_window, List<LogRecordPayload> log_records,
			@Nullable TracePayload trace, List<String> caveats) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record IssuePayload(long id, long project_id, String project_slug, String project_name,
			@Nullable String project_platform, String fingerprint, String title, @Nullable String culprit,
			String level, String status, String first_seen, String last_seen, long events_received) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record EventPayload(String id, String timestamp, String environment, @Nullable String release,
			@Nullable String level, @Nullable String message, @Nullable String exception_type,
			@Nullable String user_ident, @Nullable String trace_id, @Nullable String symbolication_status) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ExceptionPayload(@Nullable String type, @Nullable String value, @Nullable String module,
			int chained_exceptions, int frames_in_stack, List<FramePayload> frames) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FramePayload(@Nullable String function, @Nullable String module, @Nullable String filename,
			@Nullable String abs_path, @Nullable Integer lineno, @Nullable Integer colno,
			@Nullable Boolean in_app, @Nullable String context_line) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record BreadcrumbPayload(@Nullable String timestamp, @Nullable String type, @Nullable String category,
			@Nullable String level, @Nullable String message) {
	}

	public record LogWindowPayload(String start, String end, int minutes_before_event) {
	}

	/**
	 * One Log Record from the window before the Event. Has no {@code id}, since a Log Record's id
	 * is not a parameter of any Tool on this surface; follow {@code trace_id} via {@code
	 * get_trace} instead.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LogRecordPayload(String timestamp, String environment, String level, String body,
			@Nullable String trace_id, @Nullable String span_id) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TracePayload(String trace_id, long root_project_id, String root_name, @Nullable String root_op,
			@Nullable String root_status, String start_ts, String end_ts, double root_duration_ms,
			long transactions_received, long spans_received, long error_events_received) {
	}

	/**
	 * Minutes before the Event that Log Records are read over when the caller names no window;
	 * kept small so a wide window doesn't pull in an unrelated request's traffic.
	 */
	static final int DEFAULT_LOG_WINDOW_MINUTES = 5;

	/** The widest window accepted; anything larger is clamped and the clamp disclosed. */
	static final int MAX_LOG_WINDOW_MINUTES = 60;

	/** Frames kept, counted from the throw site inwards. */
	static final int MAX_STACK_FRAMES = 40;

	/** Breadcrumbs kept, counted back from the Event. */
	static final int MAX_BREADCRUMBS = 20;

	/**
	 * Log Records kept, counted back from the Event. Capped well below the log page's own page
	 * size, since these records are just one section of a larger result; use {@code
	 * log_window_minutes} to reach further back instead.
	 */
	static final int MAX_LOG_RECORDS = 25;

	/**
	 * Keys of {@code event.data} this payload projects; everything else is dropped and named in a
	 * caveat.
	 */
	private static final List<String> PROJECTED_EVENT_DATA_KEYS = List.of("exception", "breadcrumbs");

	/**
	 * Keys of {@code event.data} already carried by the {@code latest_event} columns, so excluded
	 * from the omission caveat — the caller has the value, just not under this key.
	 */
	private static final List<String> MIRRORED_EVENT_DATA_KEYS = List.of("event_id", "timestamp", "level",
			"environment", "release", "message", "logentry");

	private final JdbcTemplate jdbc;

	private final ToolSupport support;

	private final ObjectMapper mapper;

	/**
	 * Queries through {@link ToolSupport}'s {@code JdbcTemplate}, which is where the
	 * statement timeout that bounds the whole MCP path lives.
	 */
	public IssueContextTool(ToolSupport support, ObjectMapper mapper) {
		this.jdbc = support.jdbc();
		this.support = support;
		this.mapper = mapper;
	}

	/**
	 * Parameter names are the wire contract — the MCP schema takes them straight from these — so
	 * they stay {@code snake_case} rather than Java's usual camelCase.
	 */
	@McpTool(name = "get_issue_context", title = "Get Issue context", generateOutputSchema = true,
			// Defaults for these annotations are the opposite of what's declared here, so this is not decoration.
			annotations = @McpTool.McpAnnotations(title = "Get Issue context", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = """
					Everything needed to reason about one Outpost Issue in a single call: the Issue itself, its \
					latest Event — optionally the latest within one Environment — that Event's exception and stack \
					frames, the breadcrumbs leading up to it, the Log Records recorded around it, and a summary of \
					its Trace. Reports received telemetry only — no root cause, no suggested fix. Read the `caveats` \
					array: it names what was truncated, defaulted, or omitted, including which parts of the raw \
					event payload are not returned here.""")
	public IssueContextResult getIssueContext(
			@McpToolParam(description = "Outpost Issue id, as shown in the issue URL.") long issue_id,
			@McpToolParam(required = false, description = "Environment Name from list_projects; scopes latest_event "
					+ "to the Issue's latest Event in that Environment. Useful when an Issue spans environments and "
					+ "the overall latest Event is from the wrong one.") String environment,
			@McpToolParam(required = false,
					description = "Minutes before the Event to read Log Records over. Defaults to "
							+ DEFAULT_LOG_WINDOW_MINUTES + ", clamped to " + MAX_LOG_WINDOW_MINUTES
							+ ".") Integer log_window_minutes) {

		String scopedEnvironment = environment == null || environment.isBlank() ? null : environment;
		if (scopedEnvironment != null) {
			support.requireKnownEnvironments(List.of(scopedEnvironment));
		}
		SearchQuery search = buildIssueContextQuery(issue_id, scopedEnvironment);
		Context context = jdbc.query(search.sql(), this::mapContext, search.params().toArray())
			.stream()
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("no Issue with id " + issue_id));

		List<String> caveats = new ArrayList<>();
		if (context.eventId() == null) {
			// Issue counters are cumulative; Events are bounded by retention, so an Issue can outlive all its Events.
			caveats.add(scopedEnvironment == null
					? "This Issue has no Event left in retention, so no Event, stack, breadcrumbs, Log Records or "
							+ "Trace are returned. The Issue's own counters are cumulative and still stand."
					: "This Issue has no Event in retention for Environment '" + scopedEnvironment + "' — it may "
							+ "never have occurred there, or those Events may have aged out. Omit environment for "
							+ "the Issue's latest Event overall.");
			return new IssueContextResult(context.issue(), null, null, List.of(), null, List.of(), null, caveats);
		}
		if (scopedEnvironment != null) {
			caveats.add("latest_event is the latest Event received in Environment '" + scopedEnvironment
					+ "', not the Issue's latest overall. Omit environment for that one.");
		}

		ExceptionPayload exception = exception(context, caveats);
		List<BreadcrumbPayload> breadcrumbs = breadcrumbs(context.data(), caveats);
		Window window = window(context.eventTimestamp(), log_window_minutes, caveats);
		LogWindowPayload logWindow = new LogWindowPayload(window.start().toString(), window.end().toString(),
				window.minutes());
		List<LogRecordPayload> logRecords = logRecords(context, window, caveats);
		TracePayload trace = trace(context.traceId(), caveats);
		caveats.addAll(omittedEventDataKeys(context.data()));
		return new IssueContextResult(context.issue(), context.event(), exception, breadcrumbs, logWindow, logRecords,
				trace, caveats);
	}

	/**
	 * The Issue, its Project and its latest Event, in one statement. {@code LEFT JOIN} because
	 * {@code event} is bounded by retention while {@code issue} is not — an Issue whose Events
	 * have aged out still answers — and the tie-break on {@code id DESC} keeps the result
	 * deterministic when two Events land in the same microsecond.
	 */
	static SearchQuery buildIssueContextQuery(long issueId, @Nullable String environment) {
		String environmentPredicate = environment == null ? "" : " AND environment = ?";
		List<Object> params = environment == null ? List.of(issueId) : List.of(environment, issueId);
		return new SearchQuery("""
				SELECT i.id, i.project_id, p.slug AS project_slug, p.name AS project_name, p.platform,
				       i.fingerprint, i.title, i.culprit, i.level, i.status, i.first_seen, i.last_seen,
				       i.event_count,
				       e.id AS event_id, e."timestamp" AS event_timestamp, e.environment, e.release,
				       e.level AS event_level, e.message, e.exception_type, e.user_ident, e.trace_id,
				       e.symbolication_status, e.data
				FROM issue i
				JOIN project p ON p.id = i.project_id
				LEFT JOIN LATERAL (
				    SELECT id, "timestamp", environment, release, level, message, exception_type, user_ident,
				           trace_id, symbolication_status, data
				    FROM event WHERE issue_id = i.id%s
				    ORDER BY "timestamp" DESC, id DESC
				    LIMIT 1
				) e ON true
				WHERE i.id = ?
				""".formatted(environmentPredicate), params);
	}

	/**
	 * One Trace reduced to its root and three counts. The three counts are uncorrelated
	 * subqueries, evaluated once each as an {@code InitPlan} rather than per row — rewriting them
	 * as a join or correlated subquery changes that.
	 */
	static SearchQuery buildTraceSummaryQuery(String traceId) {
		return new SearchQuery("""
				SELECT t.project_id, t.trace_id, t.name AS root_name, t.op AS root_op, t.start_ts, t.end_ts,
				       t.duration_ms, t.status,
				       (SELECT count(*) FROM txn x WHERE x.trace_id = ?) AS transactions_received,
				       (SELECT count(*) FROM span s WHERE s.trace_id = ?) AS spans_received,
				       (SELECT count(*) FROM event v WHERE v.trace_id = ?) AS error_events_received
				FROM txn t WHERE t.trace_id = ?
				ORDER BY (t.parent_span_id IS NULL) DESC, t.start_ts
				LIMIT 1
				""", List.of(traceId, traceId, traceId, traceId));
	}

	/**
	 * The Log Records around the Event: {@link LogController#buildLogQuery} bound to the Event's
	 * Project and window (ADR-0016).
	 */
	static SearchQuery buildSurroundingLogQuery(long projectId, Instant from, Instant to) {
		return LogController.buildLogQuery(List.of(projectId), null, null, null, null, null, null, from, to, null);
	}

	/** The primary exception's identity and the frames nearest the throw site. */
	private static ExceptionPayload exception(Context context, List<String> caveats) {
		// "none" isn't a gap (nothing to symbolicate) and there's no pending state — Symbolicator runs synchronously during ingest.
		String status = context.symbolicationStatus();
		if (Symbolicator.STATUS_PARTIAL.equals(status) || Symbolicator.STATUS_MISSING_SOURCEMAP.equals(status)) {
			caveats.add("The stack is not symbolicated (symbolication_status=" + status
					+ "). Frames may name generated files, minified function names and generated line numbers "
					+ "rather than source, so do not treat them as source locations. This is recoverable: "
					+ "uploading source maps for release " + context.event().release()
					+ " re-symbolicates the stored Events of that release.");
		}

		JsonNode values = context.data().path("exception").path("values");
		if (!values.isArray() || values.isEmpty()) {
			return null;
		}
		// Last entry in the chain is what was thrown; earlier entries are its causes.
		JsonNode primary = values.get(values.size() - 1);
		JsonNode frames = primary.path("stacktrace").path("frames");

		return new ExceptionPayload(text(primary, "type"), text(primary, "value"), text(primary, "module"),
				values.size(), frames.isArray() ? frames.size() : 0, frames(frames, caveats));
	}

	/**
	 * Frames newest first (throw site, then callers); truncation drops the outermost frames first.
	 */
	private static List<FramePayload> frames(JsonNode frames, List<String> caveats) {
		if (!frames.isArray() || frames.isEmpty()) {
			return List.of();
		}
		List<FramePayload> kept = new ArrayList<>();
		for (int i = frames.size() - 1; i >= 0 && kept.size() < MAX_STACK_FRAMES; i--) {
			JsonNode frame = frames.get(i);
			kept.add(new FramePayload(text(frame, "function"), text(frame, "module"), text(frame, "filename"),
					text(frame, "abs_path"), number(frame, "lineno"), number(frame, "colno"),
					frame.hasNonNull("in_app") ? frame.get("in_app").asBoolean() : null,
					text(frame, "context_line")));
		}
		if (frames.size() > kept.size()) {
			caveats.add("Only the " + kept.size() + " frames nearest the throw site are returned, of "
					+ frames.size() + " in the stack.");
		}
		return kept;
	}

	/** Breadcrumbs in the order they were recorded, truncated from the far end. */
	private static List<BreadcrumbPayload> breadcrumbs(JsonNode data, List<String> caveats) {
		JsonNode raw = data.path("breadcrumbs");
		// SDKs send either {"values": [...]} or a bare array; the UI accepts both.
		JsonNode values = raw.isArray() ? raw : raw.path("values");
		if (!values.isArray() || values.isEmpty()) {
			return List.of();
		}
		int from = Math.max(0, values.size() - MAX_BREADCRUMBS);
		List<BreadcrumbPayload> kept = new ArrayList<>();
		for (int i = from; i < values.size(); i++) {
			JsonNode crumb = values.get(i);
			// Passed through as received (epoch seconds or ISO-8601) — normalizing would be inventing a fact.
			kept.add(new BreadcrumbPayload(text(crumb, "timestamp"), text(crumb, "type"), text(crumb, "category"),
					text(crumb, "level"), text(crumb, "message")));
		}
		if (from > 0) {
			caveats.add("Only the " + kept.size() + " breadcrumbs immediately before the Event are returned, of "
					+ values.size() + " recorded.");
		}
		return kept;
	}

	/**
	 * Log Records from the Event's Project over the window, oldest first. The underlying page
	 * reads newest-first and caps at its own size, so in a busy window the earliest records —
	 * furthest from the Event — are the ones lost; see {@link Window} for why that's the right end
	 * to lose.
	 */
	private List<LogRecordPayload> logRecords(Context context, Window window, List<String> caveats) {
		SearchQuery search = buildSurroundingLogQuery(context.projectId(), window.start(), window.end());
		List<Map<String, Object>> rows = jdbc.query(search.sql(), (rs, i) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", rs.getObject("id", UUID.class).toString());
			row.put("timestamp", rs.getTimestamp("timestamp").toInstant().toString());
			row.put("environment", rs.getString("environment"));
			row.put("level", rs.getString("level"));
			row.put("body", rs.getString("body"));
			row.put("trace_id", rs.getString("trace_id"));
			row.put("span_id", rs.getString("span_id"));
			return row;
		}, search.params().toArray());

		KeysetPage.Page page = LogController.logPage().paginate(rows, MAX_LOG_RECORDS);
		if (page.nextCursor() != null) {
			caveats.add("Only the " + page.rows().size() + " Log Records closest to the Event are returned; "
					+ "more matched the window, and the earliest of them were dropped. Narrow "
					+ "log_window_minutes to be sure of reaching further back without truncation.");
		}
		List<Map<String, Object>> chronological = new ArrayList<>(page.rows());
		Collections.reverse(chronological);
		boolean bodyTruncated = false;
		List<LogRecordPayload> records = new ArrayList<>();
		for (Map<String, Object> row : chronological) {
			String body = (String) row.get("body");
			bodyTruncated |= body != null && body.length() > LogSearchTool.MAX_BODY_CHARS;
			records.add(new LogRecordPayload((String) row.get("timestamp"), (String) row.get("environment"),
					(String) row.get("level"), ToolSupport.truncate(body, LogSearchTool.MAX_BODY_CHARS),
					(String) row.get("trace_id"), (String) row.get("span_id")));
		}
		if (bodyTruncated) {
			caveats.add("At least one Log Record body was longer than " + LogSearchTool.MAX_BODY_CHARS
					+ " characters and was cut off at that length. The kept text is the start of what was "
					+ "received, verbatim.");
		}
		return records;
	}

	/** The Trace summary, or null with a caveat saying which of the two reasons applies. */
	private TracePayload trace(String traceId, List<String> caveats) {
		if (traceId == null || traceId.isBlank()) {
			caveats.add("The Event carries no trace_id, so it cannot be placed in a Trace.");
			return null;
		}
		SearchQuery search = buildTraceSummaryQuery(traceId);
		List<TracePayload> rows = jdbc.query(search.sql(), (rs, i) -> new TracePayload(rs.getString("trace_id"),
				rs.getLong("project_id"), rs.getString("root_name"), rs.getString("root_op"), rs.getString("status"),
				rs.getTimestamp("start_ts").toInstant().toString(), rs.getTimestamp("end_ts").toInstant().toString(),
				rs.getDouble("duration_ms"), rs.getLong("transactions_received"), rs.getLong("spans_received"),
				rs.getLong("error_events_received")), search.params().toArray());
		if (rows.isEmpty()) {
			// A Trace exists only once a Transaction has arrived for it; errors/logs may reference one that hasn't.
			caveats.add("The Event carries trace_id " + traceId
					+ " but no Transaction has arrived for it, so no Trace summary is available.");
			return null;
		}
		return rows.get(0);
	}

	/** Top-level keys of {@code event.data} this payload dropped, named one by one. */
	private static List<String> omittedEventDataKeys(JsonNode data) {
		List<String> omitted = new ArrayList<>();
		data.propertyNames().forEach(key -> {
			if (!PROJECTED_EVENT_DATA_KEYS.contains(key) && !MIRRORED_EVENT_DATA_KEYS.contains(key)) {
				omitted.add(key);
			}
		});
		if (omitted.isEmpty()) {
			return List.of();
		}
		Collections.sort(omitted);
		return List.of("The raw event payload is not returned whole. Only its exception and breadcrumbs were "
				+ "projected; these keys were present and are not included: " + String.join(", ", omitted) + ".");
	}

	/**
	 * The Log Record window, plus the width actually used after defaulting and clamping. Ends at
	 * the Event rather than straddling it — the underlying page reads newest-first, so a
	 * straddling window would drop the earliest records (those leading to the failure) instead of
	 * the least useful ones.
	 */
	private record Window(Instant start, Instant end, int minutes) {
	}

	private static Window window(Instant eventTimestamp, Integer requested, List<String> caveats) {
		int minutes = DEFAULT_LOG_WINDOW_MINUTES;
		if (requested == null) {
			caveats.add("log_window_minutes was not supplied, so the default of " + DEFAULT_LOG_WINDOW_MINUTES
					+ " minutes before the Event was applied.");
		}
		else if (requested < 1) {
			caveats.add("log_window_minutes was " + requested + ", which is below the 1-minute minimum; "
					+ "1 minute before the Event was used.");
			minutes = 1;
		}
		else if (requested > MAX_LOG_WINDOW_MINUTES) {
			caveats.add("log_window_minutes was clamped from " + requested + " to the " + MAX_LOG_WINDOW_MINUTES
					+ "-minute maximum.");
			minutes = MAX_LOG_WINDOW_MINUTES;
		}
		else {
			minutes = requested;
		}
		// Half-open upper bound would exclude a record at the same instant as the Event; the extra second keeps it in.
		return new Window(eventTimestamp.minus(Duration.ofMinutes(minutes)), eventTimestamp.plusSeconds(1), minutes);
	}

	/**
	 * One row of {@link #buildIssueContextQuery}, split into the two payload objects
	 * it carries plus the fields the rest of the call needs as inputs.
	 */
	private record Context(IssuePayload issue, EventPayload event, long projectId, UUID eventId,
			Instant eventTimestamp, String traceId, String symbolicationStatus, JsonNode data) {
	}

	private Context mapContext(ResultSet rs, int rowNum) throws SQLException {
		// events_received counts Events received since the Issue opened; retention may since have deleted some.
		IssuePayload issue = new IssuePayload(rs.getLong("id"), rs.getLong("project_id"),
				rs.getString("project_slug"), rs.getString("project_name"), rs.getString("platform"),
				rs.getString("fingerprint"), rs.getString("title"), rs.getString("culprit"), rs.getString("level"),
				rs.getString("status"), rs.getTimestamp("first_seen").toInstant().toString(),
				rs.getTimestamp("last_seen").toInstant().toString(), rs.getLong("event_count"));

		UUID eventId = rs.getObject("event_id", UUID.class);
		if (eventId == null) {
			return new Context(issue, null, rs.getLong("project_id"), null, null, null, null,
					mapper.createObjectNode());
		}
		Instant timestamp = rs.getTimestamp("event_timestamp").toInstant();
		EventPayload event = new EventPayload(eventId.toString(), timestamp.toString(), rs.getString("environment"),
				rs.getString("release"), rs.getString("event_level"), rs.getString("message"),
				rs.getString("exception_type"), rs.getString("user_ident"), rs.getString("trace_id"),
				rs.getString("symbolication_status"));
		return new Context(issue, event, rs.getLong("project_id"), eventId, timestamp, rs.getString("trace_id"),
				rs.getString("symbolication_status"), QuerySupport.parseJson(mapper, rs.getString("data")));
	}

	private static String text(JsonNode node, String field) {
		return node.hasNonNull(field) ? node.get(field).asString() : null;
	}

	private static Integer number(JsonNode node, String field) {
		return node.hasNonNull(field) && node.get(field).isNumber() ? node.get(field).asInt() : null;
	}
}
