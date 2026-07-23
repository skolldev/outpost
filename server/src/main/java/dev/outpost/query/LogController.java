package dev.outpost.query;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.outpost.pipeline.LogTail;
import dev.outpost.pipeline.ProcessedLog;
import java.time.Instant;
import java.util.ArrayList;
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

	/** Log list keyset by {@code (timestamp, id)}; the SQL column is quoted, the row key is not. */
	private static final KeysetPage PAGE = KeysetPage.of(KeysetPage.KeyColumn.instant("\"timestamp\"", "timestamp"),
			KeysetPage.KeyColumn.uuid("id"), PAGE_SIZE);

	private final JdbcTemplate jdbc;
	private final LogTail tail;
	private final ObjectMapper mapper;

	public LogController(JdbcTemplate jdbc, LogTail tail, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.tail = tail;
		this.mapper = mapper;
	}

	@GetMapping("/logs")
	public Map<String, Object> logs(@RequestParam(required = false) List<Long> project,
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

		QuerySupport.appendInClause(sql, "project_id", project, params);
		QuerySupport.appendInClause(sql, "environment", environment, params);
		QuerySupport.appendInClause(sql, "level", level, params);
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
		KeysetPage.Tail tail = PAGE.build(cursor);
		sql.append(tail.sql());
		params.addAll(tail.params());

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
			row.put("attributes", QuerySupport.parseJson(mapper, rs.getString("attributes")));
			row.put("release", rs.getString("release"));
			return row;
		}, params.toArray());

		KeysetPage.Page page = PAGE.paginate(rows);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("logs", page.rows());
		body.put("next_cursor", page.nextCursor());
		return body;
	}

	/** SSE live tail (§9.3) — same filters, applied in-process to newly stored records. */
	@GetMapping(value = "/logs", params = "live=true")
	public SseEmitter tail(@RequestParam(required = false) List<Long> project,
			@RequestParam(required = false) List<String> environment,
			@RequestParam(required = false) List<String> level,
			@RequestParam(name = "trace_id", required = false) String traceId,
			@RequestParam(required = false) String release,
			@RequestParam(required = false) String query,
			@RequestParam(required = false) List<String> attr) {

		List<AttrFilter> attrFilters = parseAttrFilters(attr);
		String bodyNeedle = query != null && !query.isBlank() ? query.toLowerCase(Locale.ROOT) : null;
		Predicate<ProcessedLog> filter = record -> (project == null || project.isEmpty()
				|| project.contains(record.projectId()))
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
}
