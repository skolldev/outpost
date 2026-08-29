package dev.outpost.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
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
 * The MCP Surface's {@code get_event_raw} Tool: one Event's stored payload,
 * whole.
 *
 * <p>It is a separate Tool rather than a flag on {@code get_issue_context}
 * because the two are different sizes of answer, and the caller should have to
 * decide which it wants. Every other Tool projects {@code event.data} and names
 * what it dropped; this one hands over the document the SDK sent, which is the
 * only way to answer a question about a key no projection anticipated — a custom
 * context, a tag, an SDK-specific block. The cost is that a large Event is a
 * large result, which is why {@link EventRawResult#data_bytes()} is reported
 * beside it.
 *
 * <p>The statement is {@link IssueController#EVENT_BY_ID}, the event detail
 * page's own (ADR-0016). The page's two neighbour lookups are deliberately not
 * issued: they exist so a human can step through an Issue's Events, and an agent
 * navigates by {@code find_issues} instead. Skipping them makes this call a third
 * of the cost of the page it reuses.
 *
 * <p><b>{@code data} is returned verbatim and unredacted.</b> It is whatever the
 * SDK sent, including request headers, cookies and user context if the SDK was
 * configured to send them. The caveat says so on every call: an agent handed a
 * document has no way to know what an Outpost installation's SDKs put in it.
 */
@Component
public class EventRawTool {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record EventRawResult(String id, long issue_id, @Nullable String project_slug, String timestamp,
			String environment, @Nullable String release, @Nullable String level, @Nullable String message,
			@Nullable String exception_type, @Nullable String user_ident, @Nullable String trace_id,
			@Nullable String symbolication_status, int data_bytes, Map<String, Object> data, List<String> caveats) {
	}

	private final JdbcTemplate jdbc;

	private final ToolSupport support;

	private final ObjectMapper mapper;

	public EventRawTool(ToolSupport support, ObjectMapper mapper) {
		this.jdbc = support.jdbc();
		this.support = support;
		this.mapper = mapper;
	}

	@McpTool(name = "get_event_raw", title = "Get raw Event payload", generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(title = "Get raw Event payload", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = """
					One Event's stored payload exactly as the SDK sent it, under `data`. This is the only Tool that \
					returns the raw payload whole — every other Tool projects it and names what it left out. Call it \
					when get_issue_context did not carry the key you need; expect a large result. Event ids come \
					from get_issue_context and get_trace.""")
	public EventRawResult getEventRaw(
			@McpToolParam(description = "Event id as a UUID, from get_issue_context or get_trace.") String event_id) {

		UUID id = parse(event_id);
		ToolSupport.Projects projects = support.projects();

		List<EventRawResult> rows = jdbc.query(IssueController.EVENT_BY_ID, (rs, row) -> {
			String json = rs.getString("data");
			List<String> caveats = new ArrayList<>();
			caveats.add("data is the payload this Event arrived with, returned verbatim and unredacted. Depending "
					+ "on how the sending SDK is configured it may contain request headers, cookies, query "
					+ "strings or user identifiers.");
			String status = rs.getString("symbolication_status");
			if (status != null && !"none".equals(status) && !"symbolicated".equals(status)) {
				caveats.add("symbolication_status is " + status + ", so any stack frames inside data are the "
						+ "frames as received. Outpost's symbolicated view of them is in get_issue_context; "
						+ "this Tool does not rewrite the stored payload.");
			}
			JsonNode stored = QuerySupport.parseJson(mapper, json);
			if (!stored.isObject() && json != null && !json.isBlank()) {
				// The one Tool whose whole job is completeness must not answer an
				// unreadable payload with an empty object and no explanation: data_bytes
				// beside it would say the row held something, and nothing would say what.
				// The column is jsonb, so this is not malformed JSON — it is valid JSON
				// that is not an object, which no SDK sends and no other Tool can render.
				caveats.add("The stored payload is not a JSON object but a " + stored.getNodeType()
						+ ", so data is empty even though data_bytes reports what the column holds. "
						+ "Every Outpost SDK sends an object; this row is anomalous.");
			}
			return new EventRawResult(rs.getObject("id", UUID.class).toString(), rs.getLong("issue_id"),
					projects.slug(rs.getLong("project_id")), rs.getTimestamp("timestamp").toInstant().toString(),
					rs.getString("environment"), rs.getString("release"), rs.getString("level"),
					rs.getString("message"), rs.getString("exception_type"), rs.getString("user_ident"),
					rs.getString("trace_id"), status, json == null ? 0 : json.getBytes(StandardCharsets.UTF_8).length,
					data(stored), caveats);
		}, id);

		if (rows.isEmpty()) {
			throw new IllegalArgumentException("no Event with id " + id
					+ " — Events are bounded by the retention policy while the Issues that group them are not, "
					+ "so an Event named by an older result may already have been deleted.");
		}
		return rows.get(0);
	}

	private static UUID parse(String eventId) {
		if (eventId == null || eventId.isBlank()) {
			throw new IllegalArgumentException("event_id is required");
		}
		try {
			return UUID.fromString(eventId.trim());
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("event_id must be a UUID, such as "
					+ "0f4c2f4e-9b3a-4a1e-8f2b-6d5c1a2b3c4d; got '" + eventId + "'");
		}
	}

	/**
	 * The stored document as a map, or empty when the column does not hold an
	 * object. Degrading rather than failing, for the reason
	 * {@link QuerySupport#parseJson} degrades: one anomalous row is not a reason to
	 * fail the call — but unlike that helper, this one's caller discloses the
	 * degradation, because an empty {@code data} is the single answer this Tool
	 * must never give silently.
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> data(JsonNode stored) {
		return stored.isObject() ? mapper.convertValue(stored, LinkedHashMap.class) : Map.of();
	}

}
