package dev.outpost.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The MCP Surface's {@code get_trace} Tool: the Transactions, Spans, error
 * Events and Log Records sharing one Trace ID, across all Projects. Each list
 * is capped, keeping the earliest (root-nearest) rows rather than summarizing,
 * and Span/Transaction attribute payloads are omitted.
 */
@Component
public class TraceTool {

	public record TraceResult(String trace_id, long transactions_received, long spans_received,
			long error_events_received, long log_records_received, List<TransactionPayload> transactions,
			List<SpanPayload> spans, List<ErrorEventPayload> error_events, List<LogRecordPayload> log_records,
			List<String> caveats) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TransactionPayload(String id, @Nullable String project_slug, String environment,
			@Nullable String release, String span_id, @Nullable String parent_span_id, String name,
			@Nullable String op, String start_ts, String end_ts, double duration_ms, @Nullable String status) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SpanPayload(@Nullable String project_slug, String span_id, @Nullable String parent_span_id,
			@Nullable String op, @Nullable String description, String start_ts, String end_ts, double duration_ms,
			@Nullable String status) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ErrorEventPayload(String id, long issue_id, @Nullable String project_slug, String environment,
			String timestamp, @Nullable String span_id, @Nullable String level, @Nullable String message,
			@Nullable String exception_type) {
	}

	/**
	 * One Log Record correlated with the Trace. No {@code id} (unlike
	 * {@link ErrorEventPayload}, whose id {@code get_event_raw} takes) and no
	 * {@code trace_id}, since every row here shares the one the result states once.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LogRecordPayload(String timestamp, @Nullable String project_slug, String environment, String level,
			String body, @Nullable String span_id) {
	}

	/** Rows kept per list, counted from the start of the Trace. */
	static final int MAX_TRANSACTIONS = 100;

	static final int MAX_SPANS = 200;

	static final int MAX_ERROR_EVENTS = 50;

	static final int MAX_LOG_RECORDS = 100;

	private final JdbcTemplate jdbc;

	private final ToolSupport support;

	public TraceTool(ToolSupport support) {
		this.jdbc = support.jdbc();
		this.support = support;
	}

	@McpTool(name = "get_trace", title = "Get Trace", generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(title = "Get Trace", readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false),
			description = """
					One Trace: every Transaction, Span, error Event and Log Record sharing its Trace ID, across all \
					Projects. Spans nest by parent_span_id and are ordered by start_ts, so the waterfall can be \
					rebuilt from them. Trace IDs appear on get_issue_context's Event and Trace, on search_logs \
					records, and on find_transactions results. Long Traces are truncated — compare each list's \
					length with the matching `*_received` count, and read the `caveats` array.""")
	public TraceResult getTrace(
			@McpToolParam(description = "32-character hex Trace ID.") String trace_id) {

		if (trace_id == null || trace_id.isBlank()) {
			throw new IllegalArgumentException("trace_id is required");
		}
		String traceId = trace_id.trim();
		ToolSupport.Projects projects = support.projects();

		List<TransactionPayload> transactions = jdbc.query(TraceController.TRANSACTIONS_BY_TRACE,
				(rs, row) -> new TransactionPayload(rs.getObject("id", UUID.class).toString(),
						projects.slug(rs.getLong("project_id")), rs.getString("environment"), rs.getString("release"),
						rs.getString("span_id"), rs.getString("parent_span_id"), rs.getString("name"),
						rs.getString("op"), rs.getTimestamp("start_ts").toInstant().toString(),
						rs.getTimestamp("end_ts").toInstant().toString(), rs.getDouble("duration_ms"),
						rs.getString("status")),
				traceId);

		List<SpanPayload> spans = jdbc.query(TraceController.SPANS_BY_TRACE,
				(rs, row) -> new SpanPayload(projects.slug(rs.getLong("project_id")), rs.getString("span_id"),
						rs.getString("parent_span_id"), rs.getString("op"), rs.getString("description"),
						rs.getTimestamp("start_ts").toInstant().toString(),
						rs.getTimestamp("end_ts").toInstant().toString(), rs.getDouble("duration_ms"),
						rs.getString("status")),
				traceId);

		List<ErrorEventPayload> errors = jdbc.query(TraceController.EVENTS_BY_TRACE,
				(rs, row) -> new ErrorEventPayload(rs.getObject("id", UUID.class).toString(), rs.getLong("issue_id"),
						projects.slug(rs.getLong("project_id")), rs.getString("environment"),
						rs.getTimestamp("timestamp").toInstant().toString(), rs.getString("span_id"),
						rs.getString("level"), rs.getString("message"), rs.getString("exception_type")),
				traceId);

		List<LogRecordPayload> logs = jdbc.query(TraceController.LOGS_BY_TRACE,
				(rs, row) -> new LogRecordPayload(rs.getTimestamp("timestamp").toInstant().toString(),
						projects.slug(rs.getLong("project_id")), rs.getString("environment"), rs.getString("level"),
						ToolSupport.truncate(rs.getString("body"), LogSearchTool.MAX_BODY_CHARS),
						rs.getString("span_id")),
				traceId);

		if (transactions.isEmpty() && spans.isEmpty() && errors.isEmpty() && logs.isEmpty()) {
			throw new IllegalArgumentException("no Trace with trace_id " + traceId
					+ " — nothing has been received carrying it. Trace IDs are 32 hex characters and Traces age "
					+ "out with the retention policy.");
		}

		List<String> caveats = new ArrayList<>();
		if (transactions.isEmpty()) {
			caveats.add("No Transaction has arrived for this Trace, so it has no root and no duration. The Events "
					+ "and Log Records below reference the Trace ID without one having been recorded.");
		}
		caveats.add("Transaction and Span attribute payloads are not returned by this Tool. Only the columns above "
				+ "are projected; a Span's own attributes, including any recorded statement or URL, are not here.");

		// Counts are taken before capping, so *_received reflects the full Trace.
		return new TraceResult(traceId, transactions.size(), spans.size(), errors.size(), logs.size(),
				cap(transactions, MAX_TRANSACTIONS, "Transactions", caveats),
				cap(spans, MAX_SPANS, "Spans", caveats), cap(errors, MAX_ERROR_EVENTS, "error Events", caveats),
				cap(logs, MAX_LOG_RECORDS, "Log Records", caveats), caveats);
	}

	/** Keeps the first {@code limit} rows (chronological order), adding a caveat when any were cut. */
	private static <T> List<T> cap(List<T> rows, int limit, String what, List<String> caveats) {
		if (rows.size() <= limit) {
			return rows;
		}
		caveats.add("This Trace holds " + rows.size() + " " + what + " and only the earliest " + limit
				+ " are returned. The full count is reported alongside the list.");
		return rows.subList(0, limit);
	}

}
