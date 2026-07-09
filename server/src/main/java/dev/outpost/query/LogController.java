package dev.outpost.query;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.outpost.pipeline.LogTail;
import dev.outpost.pipeline.ProcessedLog;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Log query API (§8): keyset-paginated descending stream with level / env /
 * trace / attribute / body-substring filters; {@code ?live=true} upgrades to
 * an SSE tail fed by the ingest pipeline with the same filters applied.
 * Attribute filters come as {@code attr=key=value} (value optional — bare
 * {@code attr=key} matches presence).
 */
@RestController
@RequestMapping("/api/internal")
public class LogController {

	private static final int PAGE_SIZE = 100;

	private final JdbcTemplate jdbc;
	private final LogTail tail;
	private final ObjectMapper mapper;

	public LogController(JdbcTemplate jdbc, LogTail tail, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.tail = tail;
		this.mapper = mapper;
	}

	@GetMapping("/logs")
	public Map<String, Object> logs(@RequestParam(required = false) Long project,
			@RequestParam(required = false) List<String> environment,
			@RequestParam(required = false) List<String> level,
			@RequestParam(name = "trace_id", required = false) String traceId,
			@RequestParam(required = false) String release,
			@RequestParam(required = false) String query,
			@RequestParam(required = false) List<String> attr,
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			@RequestParam(required = false) String cursor) {

		StringBuilder sql = new StringBuilder("""
				SELECT id, project_id, environment, "timestamp", trace_id, span_id, level, severity_number,
				       body, attributes, release
				FROM log_record WHERE 1=1
				""");
		List<Object> params = new ArrayList<>();

		if (project != null) {
			sql.append(" AND project_id = ?");
			params.add(project);
		}
		if (environment != null && !environment.isEmpty()) {
			sql.append(" AND environment IN (").append(placeholders(environment.size())).append(")");
			params.addAll(environment);
		}
		if (level != null && !level.isEmpty()) {
			sql.append(" AND level IN (").append(placeholders(level.size())).append(")");
			params.addAll(level);
		}
		if (traceId != null && !traceId.isBlank()) {
			sql.append(" AND trace_id = ?");
			params.add(traceId);
		}
		if (release != null && !release.isBlank()) {
			sql.append(" AND release = ?");
			params.add(release);
		}
		if (query != null && !query.isBlank()) {
			sql.append(" AND body ILIKE ?");
			params.add("%" + query + "%");
		}
		for (AttrFilter filter : parseAttrFilters(attr)) {
			if (filter.value() == null) {
				sql.append(" AND jsonb_exists(attributes, ?)");
				params.add(filter.key());
			}
			else {
				sql.append(" AND attributes->>? = ?");
				params.add(filter.key());
				params.add(filter.value());
			}
		}
		if (from != null) {
			sql.append(" AND \"timestamp\" >= ?");
			params.add(java.sql.Timestamp.from(from));
		}
		if (to != null) {
			sql.append(" AND \"timestamp\" <= ?");
			params.add(java.sql.Timestamp.from(to));
		}
		if (cursor != null && !cursor.isBlank()) {
			String[] parts = decodeCursor(cursor);
			sql.append(" AND (\"timestamp\", id) < (?, ?)");
			params.add(java.sql.Timestamp.from(Instant.parse(parts[0])));
			params.add(UUID.fromString(parts[1]));
		}
		sql.append(" ORDER BY \"timestamp\" DESC, id DESC LIMIT ").append(PAGE_SIZE + 1);

		List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, i) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", rs.getObject("id", UUID.class));
			row.put("project_id", rs.getLong("project_id"));
			row.put("environment", rs.getString("environment"));
			row.put("timestamp", rs.getTimestamp("timestamp").toInstant());
			row.put("trace_id", rs.getString("trace_id"));
			row.put("span_id", rs.getString("span_id"));
			row.put("level", rs.getString("level"));
			row.put("severity_number", rs.getObject("severity_number", Integer.class));
			row.put("body", rs.getString("body"));
			row.put("attributes", parseJson(rs.getString("attributes")));
			row.put("release", rs.getString("release"));
			return row;
		}, params.toArray());

		boolean hasMore = rows.size() > PAGE_SIZE;
		if (hasMore) {
			rows = rows.subList(0, PAGE_SIZE);
		}
		String nextCursor = hasMore && !rows.isEmpty() ? encodeCursor(
				rows.get(rows.size() - 1).get("timestamp").toString(), rows.get(rows.size() - 1).get("id").toString())
				: null;
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("logs", rows);
		body.put("next_cursor", nextCursor);
		return body;
	}

	/** SSE live tail (§9.3) — same filters, applied in-process to newly stored records. */
	@GetMapping(value = "/logs", params = "live=true")
	public SseEmitter tail(@RequestParam(required = false) Long project,
			@RequestParam(required = false) List<String> environment,
			@RequestParam(required = false) List<String> level,
			@RequestParam(name = "trace_id", required = false) String traceId,
			@RequestParam(required = false) String release,
			@RequestParam(required = false) String query,
			@RequestParam(required = false) List<String> attr) {

		List<AttrFilter> attrFilters = parseAttrFilters(attr);
		String bodyNeedle = query != null && !query.isBlank() ? query.toLowerCase(Locale.ROOT) : null;
		Predicate<ProcessedLog> filter = record -> (project == null || record.projectId() == project)
				&& (environment == null || environment.isEmpty() || environment.contains(record.environment()))
				&& (level == null || level.isEmpty() || level.contains(record.level()))
				&& (traceId == null || traceId.isBlank() || traceId.equals(record.traceId()))
				&& (release == null || release.isBlank() || release.equals(record.release()))
				&& (bodyNeedle == null || record.body().toLowerCase(Locale.ROOT).contains(bodyNeedle))
				&& attrFilters.stream().allMatch(f -> matchesAttr(record, f));
		return tail.subscribe(filter);
	}

	private static boolean matchesAttr(ProcessedLog record, AttrFilter filter) {
		JsonNode value = record.attributes().get(filter.key());
		if (filter.value() == null) {
			return value != null && !value.isNull();
		}
		return value != null && value.asText().equals(filter.value());
	}

	private static List<AttrFilter> parseAttrFilters(List<String> attr) {
		if (attr == null) {
			return List.of();
		}
		return attr.stream().filter(a -> a != null && !a.isBlank()).map(a -> {
			int eq = a.indexOf('=');
			return eq < 0 ? new AttrFilter(a, null) : new AttrFilter(a.substring(0, eq), a.substring(eq + 1));
		}).toList();
	}

	private record AttrFilter(String key, String value) {
	}

	private JsonNode parseJson(String json) {
		try {
			return mapper.readTree(json);
		}
		catch (Exception e) {
			return mapper.createObjectNode();
		}
	}

	private static String placeholders(int n) {
		return String.join(",", java.util.Collections.nCopies(n, "?"));
	}

	private static String encodeCursor(String sortValue, String id) {
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString((sortValue + "|" + id).getBytes(StandardCharsets.UTF_8));
	}

	private static String[] decodeCursor(String cursor) {
		String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
		String[] parts = decoded.split("\\|", 2);
		if (parts.length != 2) {
			throw new IllegalArgumentException("invalid cursor");
		}
		return parts;
	}
}
