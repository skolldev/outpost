package dev.outpost.query;

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
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The MCP Surface's {@code get_issue_context} Tool: one call that returns an
 * Issue, its latest Event, that Event's stack and breadcrumbs, the Log Records
 * around it and a summary of its Trace.
 *
 * <p>It lives in {@code dev.outpost.query} rather than a feature package of its
 * own so it can call the controllers' package-private {@code build…Query}
 * factories — see ADR-0016. The surrounding Log Records are
 * {@link LogController#buildLogQuery} with a window bound, which is a question
 * the log stream already asks and therefore arrives with its guard written; the
 * two statements below are genuinely new and are guarded by
 * {@code IssueContextPerformanceTest}.
 *
 * <p><b>ADR-0014 binds this payload.</b> Nothing here is summarized, ranked or
 * diagnosed: the Tool reports what was received and the calling agent draws the
 * conclusions. Three consequences visible in the code:
 *
 * <ul>
 * <li><b>The Event is named, not chosen.</b> The key is {@code latest_event}, not
 * {@code representative_event}, because "most representative" is a judgement no
 * stored column supports. The selection rule travels in the field name, which
 * survives truncation and re-summarization in a way a footnote does not.
 * <li><b>Counts carry their units.</b> {@code events_received} rather than the
 * {@code event_count} the UI wire contract uses, {@code transactions_received}
 * rather than {@code count}: the name has to say that these are signals
 * received, not requests served.
 * <li><b>{@code event.data} is never returned whole</b> — only the primary
 * exception's frames and the breadcrumbs are projected out of it, and
 * {@link #caveats} names every top-level key that was dropped so the caller
 * knows what it is not being shown.
 * </ul>
 *
 * <p>The payload is built as nested {@code LinkedHashMap}s with literal
 * {@code snake_case} keys, as the query controllers next door build theirs, and
 * here there is a second reason on top of that consistency: the MCP transport
 * serializes a Tool result through its <em>own</em> {@code JsonMapper}, not the
 * application's, so the global {@code SNAKE_CASE} strategy that makes a record
 * DTO come out right on {@code /api/internal/**} does not apply. A record would
 * serialize camelCase here unless every component carried an annotation.
 *
 * <p>{@link #caveats} entries are sentences rather than codes, deliberately. The
 * consumer is a language model with no access to a lookup table, and the ADR-0014
 * argument for field names applies equally here: a sentence carries its own
 * meaning wherever it ends up, a code carries none.
 */
@Component
public class IssueContextTool {

	/**
	 * Minutes <em>before</em> the Event that Log Records are read over when the
	 * caller names no window. Small on purpose: these Log Records are context for
	 * one Event, and a wide window returns the busy minutes of an unrelated request
	 * instead of the ones leading to the failure.
	 */
	static final int DEFAULT_LOG_WINDOW_MINUTES = 5;

	/** The widest window accepted; anything larger is clamped and the clamp disclosed. */
	static final int MAX_LOG_WINDOW_MINUTES = 60;

	/** Frames kept, counted from the throw site inwards. */
	static final int MAX_STACK_FRAMES = 40;

	/** Breadcrumbs kept, counted back from the Event. */
	static final int MAX_BREADCRUMBS = 20;

	/**
	 * Keys of {@code event.data} the payload projects. Everything else is dropped
	 * and named in a caveat, so "what was omitted" is a fact the caller is told
	 * rather than one it has to infer from what arrived.
	 */
	private static final List<String> PROJECTED_EVENT_DATA_KEYS = List.of("exception", "breadcrumbs");

	/**
	 * Keys of {@code event.data} the {@code latest_event} columns already carry.
	 * Excluded from the omission caveat because naming them would be false: the
	 * caller has the value, it just did not arrive under this key. The caveat is
	 * only worth reading if everything in it is genuinely missing.
	 */
	private static final List<String> MIRRORED_EVENT_DATA_KEYS = List.of("event_id", "timestamp", "level",
			"environment", "release", "message", "logentry");

	private final JdbcTemplate jdbc;

	private final ObjectMapper mapper;

	/**
	 * A statement timeout bounds this path, per the MCP Surface's performance rules.
	 * It is a backstop for the unexpected rather than the plan for the expected —
	 * ADR-0001 and ADR-0003 put these queries on the same single Postgres the ingest
	 * pipeline is writing to, so a runaway agent query is a runaway ingest queue.
	 */
	public IssueContextTool(DataSource dataSource, ObjectMapper mapper,
			@Value("${outpost.mcp.query-timeout-seconds:15}") int queryTimeoutSeconds) {
		// Its own JdbcTemplate rather than the shared one, because the timeout is the
		// point: setting it on the injected bean would put it on every controller too.
		this.jdbc = new JdbcTemplate(dataSource);
		this.jdbc.setQueryTimeout(queryTimeoutSeconds);
		this.mapper = mapper;
	}

	// ------------------------------------------------------------------- tool

	/**
	 * Parameter names are the wire contract — the MCP input schema takes its
	 * property names straight from them — so they are {@code snake_case} like every
	 * other JSON field this server produces, rather than Java's usual camelCase.
	 */
	@McpTool(name = "get_issue_context", title = "Get Issue context",
			// Read-only and non-destructive, which is the whole v1 posture: a client that
			// gates writes behind a confirmation must not gate this. The defaults are the
			// opposite of every one of these, so stating them is not decoration.
			annotations = @McpTool.McpAnnotations(title = "Get Issue context", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = """
					Everything needed to reason about one Outpost Issue in a single call: the Issue itself, its \
					latest Event, that Event's exception and stack frames, the breadcrumbs leading up to it, the \
					Log Records recorded around it, and a summary of its Trace. Reports received telemetry only — \
					no root cause, no suggested fix. Read the `caveats` array: it names what was truncated, \
					defaulted, or omitted, including which parts of the raw event payload are not returned here.""")
	public Map<String, Object> getIssueContext(
			@McpToolParam(description = "Outpost Issue id, as shown in the issue URL.") long issue_id,
			@McpToolParam(required = false,
					description = "Minutes before the Event to read Log Records over. Defaults to "
							+ DEFAULT_LOG_WINDOW_MINUTES + ", clamped to " + MAX_LOG_WINDOW_MINUTES
							+ ".") Integer log_window_minutes) {

		SearchQuery search = buildIssueContextQuery(issue_id);
		Context context = jdbc.query(search.sql(), this::mapContext, search.params().toArray())
			.stream()
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("no Issue with id " + issue_id));

		List<String> caveats = new ArrayList<>();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("issue", context.issue());

		if (context.eventId() == null) {
			// An Issue outlives its Events: the counters on `issue` are cumulative while
			// `event` is bounded by retention. Saying so beats returning empty arrays.
			caveats.add("This Issue has no Event left in retention, so no Event, stack, breadcrumbs, "
					+ "Log Records or Trace are returned. The Issue's own counters are cumulative and still stand.");
			body.put("latest_event", null);
			body.put("exception", null);
			body.put("breadcrumbs", List.of());
			body.put("log_window", null);
			body.put("log_records", List.of());
			body.put("trace", null);
			body.put("caveats", caveats);
			return body;
		}

		body.put("latest_event", context.event());
		body.put("exception", exception(context, caveats));
		body.put("breadcrumbs", breadcrumbs(context.data(), caveats));

		Window window = window(context.eventTimestamp(), log_window_minutes, caveats);
		Map<String, Object> logWindow = new LinkedHashMap<>();
		logWindow.put("start", window.start().toString());
		logWindow.put("end", window.end().toString());
		logWindow.put("minutes_before_event", window.minutes());
		body.put("log_window", logWindow);
		body.put("log_records", logRecords(context, window, caveats));
		body.put("trace", trace(context.traceId(), caveats));

		caveats.addAll(omittedEventDataKeys(context.data()));
		body.put("caveats", caveats);
		return body;
	}

	// ------------------------------------------------------------------ query

	/**
	 * The Issue, its Project and its latest Event, in one statement. This is the
	 * join ADR-0016 warns about: no controller performs it, so it does not inherit
	 * a guard from the reuse rule and gets its own in
	 * {@code IssueContextPerformanceTest}.
	 *
	 * <p>The Event is reached through a {@code LATERAL} rather than a second round
	 * trip so the guard can {@code EXPLAIN} the whole lookup as the one thing it is.
	 * {@code LEFT JOIN} because {@code event} is bounded by retention and
	 * {@code issue} is not: an Issue whose Events have aged out still answers.
	 *
	 * <p>{@code ORDER BY "timestamp" DESC, id DESC} rather than by timestamp alone,
	 * so two Events arriving in the same microsecond do not make the payload depend
	 * on which partition the planner reached first.
	 */
	static SearchQuery buildIssueContextQuery(long issueId) {
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
				    FROM event WHERE issue_id = i.id
				    ORDER BY "timestamp" DESC, id DESC
				    LIMIT 1
				) e ON true
				WHERE i.id = ?
				""", List.of(issueId));
	}

	/**
	 * One Trace reduced to its root and three counts, which is what an agent needs
	 * to decide whether the Trace is worth a second call. Also new SQL: the trace
	 * detail endpoint fans out into four statements returning every row, and a
	 * summary is a different question with a different plan.
	 *
	 * <p>The three counts are uncorrelated, so Postgres evaluates each once as an
	 * {@code InitPlan} rather than per row — the distinction {@link
	 * dev.outpost.support.PlanFacts#correlatedSubplans()} exists to police, and the
	 * reason they are not written as a join.
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
	 * The Log Records around the Event: {@link LogController#buildLogQuery} bound to
	 * the Event's Project and window. Reused rather than rewritten precisely because
	 * it is a question the log stream already asks, so it arrives with V11's ordering
	 * index and {@code LogQueryPerformanceTest}'s guard behind it — the reuse rule in
	 * ADR-0016.
	 */
	static SearchQuery buildSurroundingLogQuery(long projectId, Instant from, Instant to) {
		return LogController.buildLogQuery(List.of(projectId), null, null, null, null, null, null, from, to, null);
	}

	// ------------------------------------------------------------- projections

	/** The primary exception's identity and the frames nearest the throw site. */
	private static Map<String, Object> exception(Context context, List<String> caveats) {
		// Only these two statuses warrant a caveat. `none` means there was nothing to
		// symbolicate — a JVM stack, say — and is not a gap; and there is no pending
		// state to disclose, because Symbolicator runs synchronously in the ingest
		// worker, so a stored Event has already been through it.
		String status = context.symbolicationStatus();
		if (Symbolicator.STATUS_PARTIAL.equals(status) || Symbolicator.STATUS_MISSING_SOURCEMAP.equals(status)) {
			caveats.add("The stack is not symbolicated (symbolication_status=" + status
					+ "). Frames may name generated files, minified function names and generated line numbers "
					+ "rather than source, so do not treat them as source locations. This is recoverable: "
					+ "uploading source maps for release " + context.event().get("release")
					+ " re-symbolicates the stored Events of that release.");
		}

		JsonNode values = context.data().path("exception").path("values");
		if (!values.isArray() || values.isEmpty()) {
			return null;
		}
		// The last entry of the chain is the exception that was thrown; the earlier
		// ones are its causes. Same choice the pipeline's fingerprinter makes.
		JsonNode primary = values.get(values.size() - 1);
		JsonNode frames = primary.path("stacktrace").path("frames");

		Map<String, Object> exception = new LinkedHashMap<>();
		exception.put("type", text(primary, "type"));
		exception.put("value", text(primary, "value"));
		exception.put("module", text(primary, "module"));
		exception.put("chained_exceptions", values.size());
		exception.put("frames_in_stack", frames.isArray() ? frames.size() : 0);
		exception.put("frames", frames(frames, caveats));
		return exception;
	}

	/**
	 * Frames newest first — the throw site, then its callers — because that is the
	 * end a reader starts from. Truncation therefore drops the outermost frames,
	 * which are the ones least likely to name the defect.
	 */
	private static List<Map<String, Object>> frames(JsonNode frames, List<String> caveats) {
		if (!frames.isArray() || frames.isEmpty()) {
			return List.of();
		}
		List<Map<String, Object>> kept = new ArrayList<>();
		for (int i = frames.size() - 1; i >= 0 && kept.size() < MAX_STACK_FRAMES; i--) {
			JsonNode frame = frames.get(i);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("function", text(frame, "function"));
			row.put("module", text(frame, "module"));
			row.put("filename", text(frame, "filename"));
			row.put("abs_path", text(frame, "abs_path"));
			row.put("lineno", number(frame, "lineno"));
			row.put("colno", number(frame, "colno"));
			row.put("in_app", frame.hasNonNull("in_app") ? frame.get("in_app").asBoolean() : null);
			row.put("context_line", text(frame, "context_line"));
			kept.add(row);
		}
		if (frames.size() > kept.size()) {
			caveats.add("Only the " + kept.size() + " frames nearest the throw site are returned, of "
					+ frames.size() + " in the stack.");
		}
		return kept;
	}

	/** Breadcrumbs in the order they were recorded, truncated from the far end. */
	private static List<Map<String, Object>> breadcrumbs(JsonNode data, List<String> caveats) {
		JsonNode raw = data.path("breadcrumbs");
		// SDKs send either {"values": [...]} or a bare array; the UI accepts both.
		JsonNode values = raw.isArray() ? raw : raw.path("values");
		if (!values.isArray() || values.isEmpty()) {
			return List.of();
		}
		int from = Math.max(0, values.size() - MAX_BREADCRUMBS);
		List<Map<String, Object>> kept = new ArrayList<>();
		for (int i = from; i < values.size(); i++) {
			JsonNode crumb = values.get(i);
			Map<String, Object> row = new LinkedHashMap<>();
			// Passed through as received: SDKs send either epoch seconds or ISO-8601 here,
			// and normalizing one into the other would be this Tool inventing a fact.
			row.put("timestamp", text(crumb, "timestamp"));
			row.put("type", text(crumb, "type"));
			row.put("category", text(crumb, "category"));
			row.put("level", text(crumb, "level"));
			row.put("message", text(crumb, "message"));
			kept.add(row);
		}
		if (from > 0) {
			caveats.add("Only the " + kept.size() + " breadcrumbs immediately before the Event are returned, of "
					+ values.size() + " recorded.");
		}
		return kept;
	}

	/**
	 * Log Records from the Event's Project over the window, oldest first. The
	 * underlying log page reads newest-first and caps at its own page size, so a
	 * window busier than that page loses its earliest records — the ones furthest
	 * from the Event. See {@link Window} for why the window ends at the Event rather
	 * than straddling it, which is what makes that the right end to lose.
	 */
	private List<Map<String, Object>> logRecords(Context context, Window window, List<String> caveats) {
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

		KeysetPage.Page page = LogController.logPage().paginate(rows);
		if (page.nextCursor() != null) {
			caveats.add("Only the " + page.rows().size() + " Log Records closest to the Event are returned; "
					+ "more matched the window, and the earliest of them were dropped. Narrow "
					+ "log_window_minutes to be sure of reaching further back without truncation.");
		}
		List<Map<String, Object>> chronological = new ArrayList<>(page.rows());
		Collections.reverse(chronological);
		return chronological;
	}

	/** The Trace summary, or null with a caveat saying which of the two reasons applies. */
	private Map<String, Object> trace(String traceId, List<String> caveats) {
		if (traceId == null || traceId.isBlank()) {
			caveats.add("The Event carries no trace_id, so it cannot be placed in a Trace.");
			return null;
		}
		SearchQuery search = buildTraceSummaryQuery(traceId);
		List<Map<String, Object>> rows = jdbc.query(search.sql(), (rs, i) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("trace_id", rs.getString("trace_id"));
			row.put("root_project_id", rs.getLong("project_id"));
			row.put("root_name", rs.getString("root_name"));
			row.put("root_op", rs.getString("root_op"));
			row.put("root_status", rs.getString("status"));
			row.put("start_ts", rs.getTimestamp("start_ts").toInstant().toString());
			row.put("end_ts", rs.getTimestamp("end_ts").toInstant().toString());
			row.put("root_duration_ms", rs.getDouble("duration_ms"));
			row.put("transactions_received", rs.getLong("transactions_received"));
			row.put("spans_received", rs.getLong("spans_received"));
			row.put("error_events_received", rs.getLong("error_events_received"));
			return row;
		}, search.params().toArray());
		if (rows.isEmpty()) {
			// Same rule the trace detail endpoint applies: a Trace is only known once a
			// Transaction has arrived for it. Errors and logs may reference one that has not.
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

	// ------------------------------------------------------------------ window

	/**
	 * The Log Record window, plus the width actually used after defaulting and
	 * clamping.
	 *
	 * <p><b>It ends at the Event and does not straddle it,</b> which is the one
	 * decision here worth arguing. The window is answered by the log list's own
	 * keyset — newest first, capped at its page size — so whatever the window is,
	 * overflow discards its <em>earliest</em> records. A window ending at the Event
	 * therefore drops the records furthest from the failure, which is the same rule
	 * {@link #frames} applies to a long stack. A straddling window would keep the
	 * records after the Event and drop the ones leading to it, and no caveat can
	 * repair a selection that threw away the useful half.
	 *
	 * <p>The aftermath is not lost, it is reached differently: everything on the
	 * Event's Trace is one {@code trace_id} away, and {@code trace.trace_id} is in
	 * this same payload.
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
		// The upper bound is half-open in the log query, so a Log Record written in the
		// same instant as the Event would fall outside it; a second of slack keeps the
		// line that reports the failure in the window that is meant to explain it.
		return new Window(eventTimestamp.minus(Duration.ofMinutes(minutes)), eventTimestamp.plusSeconds(1), minutes);
	}

	// ------------------------------------------------------------------ shared

	/**
	 * One row of {@link #buildIssueContextQuery}, split into the two payload objects
	 * it carries plus the fields the rest of the call needs as inputs.
	 */
	private record Context(Map<String, Object> issue, Map<String, Object> event, long projectId, UUID eventId,
			Instant eventTimestamp, String traceId, String symbolicationStatus, JsonNode data) {
	}

	private Context mapContext(ResultSet rs, int rowNum) throws SQLException {
		Map<String, Object> issue = new LinkedHashMap<>();
		issue.put("id", rs.getLong("id"));
		issue.put("project_id", rs.getLong("project_id"));
		issue.put("project_slug", rs.getString("project_slug"));
		issue.put("project_name", rs.getString("project_name"));
		issue.put("project_platform", rs.getString("platform"));
		issue.put("fingerprint", rs.getString("fingerprint"));
		issue.put("title", rs.getString("title"));
		issue.put("culprit", rs.getString("culprit"));
		issue.put("level", rs.getString("level"));
		issue.put("status", rs.getString("status"));
		issue.put("first_seen", rs.getTimestamp("first_seen").toInstant().toString());
		issue.put("last_seen", rs.getTimestamp("last_seen").toInstant().toString());
		// Not `event_count`: the number counts Events received since the Issue was
		// opened, and retention may since have deleted some of them.
		issue.put("events_received", rs.getLong("event_count"));

		UUID eventId = rs.getObject("event_id", UUID.class);
		if (eventId == null) {
			return new Context(issue, null, rs.getLong("project_id"), null, null, null, null,
					mapper.createObjectNode());
		}
		Instant timestamp = rs.getTimestamp("event_timestamp").toInstant();
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("id", eventId.toString());
		event.put("timestamp", timestamp.toString());
		event.put("environment", rs.getString("environment"));
		event.put("release", rs.getString("release"));
		event.put("level", rs.getString("event_level"));
		event.put("message", rs.getString("message"));
		event.put("exception_type", rs.getString("exception_type"));
		event.put("user_ident", rs.getString("user_ident"));
		event.put("trace_id", rs.getString("trace_id"));
		event.put("symbolication_status", rs.getString("symbolication_status"));
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
