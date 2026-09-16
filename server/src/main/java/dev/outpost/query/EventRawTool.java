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
 * The MCP Surface's {@code get_event_raw} Tool: one Event's stored payload, returned verbatim
 * and unredacted, including any request headers, cookies or user context the SDK sent.
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
				// Valid JSON but not an object (rare); disclose it rather than silently returning empty data.
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

	@SuppressWarnings("unchecked")
	private Map<String, Object> data(JsonNode stored) {
		return stored.isObject() ? mapper.convertValue(stored, LinkedHashMap.class) : Map.of();
	}

}
