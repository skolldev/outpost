package dev.outpost.query;

import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trace query API (§8). {@code GET /traces} searches root transactions with the
 * common filter layer plus duration range and "has errors"; {@code GET
 * /traces/{trace_id}} fans out across txn / span / event / log_record by
 * {@code trace_id} — a cross-project join done at query time, since nothing is
 * linked at ingest (§6.4). The detail payload is waterfall-ready: the UI nests
 * spans by {@code parent_span_id} and orders by {@code start_ts}.
 */
@RestController
@RequestMapping("/api/internal")
public class TraceController {

	private static final int PAGE_SIZE = 50;

	/** Trace search pages by {@code (start_ts, id)} descending over the deduped trace rows. */
	private static final KeysetPage PAGE = KeysetPage.of(KeysetPage.KeyColumn.instant("start_ts"),
			KeysetPage.KeyColumn.uuid("id"), PAGE_SIZE);

	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;

	public TraceController(JdbcTemplate jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	/** Trace search over root transactions (§9.4 list). */
	@GetMapping("/traces")
	public Map<String, Object> traces(@RequestParam(required = false) List<Long> project,
			@RequestParam(required = false) List<String> environment,
			@RequestParam(required = false) String release,
			@RequestParam(required = false) String query,
			@RequestParam(name = "min_duration", required = false) Double minDuration,
			@RequestParam(name = "max_duration", required = false) Double maxDuration,
			@RequestParam(name = "has_errors", required = false) Boolean hasErrors,
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			@RequestParam(required = false) String cursor) {

		SearchQuery search = buildSearchQuery(project, environment, release, query, minDuration, maxDuration,
				hasErrors, from, to, cursor);

		List<Map<String, Object>> rows = jdbc.query(search.sql(), (rs, i) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", rs.getObject("id", UUID.class));
			row.put("project_id", rs.getLong("project_id"));
			row.put("environment", rs.getString("environment"));
			row.put("release", rs.getString("release"));
			row.put("trace_id", rs.getString("trace_id"));
			row.put("name", rs.getString("name"));
			row.put("op", rs.getString("op"));
			row.put("start_ts", rs.getTimestamp("start_ts").toInstant());
			row.put("end_ts", rs.getTimestamp("end_ts").toInstant());
			row.put("duration_ms", rs.getDouble("duration_ms"));
			row.put("status", rs.getString("status"));
			row.put("span_count", rs.getLong("span_count"));
			row.put("error_count", rs.getLong("error_count"));
			return row;
		}, search.params().toArray());

		KeysetPage.Page page = PAGE.paginate(rows);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("traces", page.rows());
		body.put("next_cursor", page.nextCursor());
		return body;
	}

	/** The trace-search SQL and its ordered bind parameters. */
	record SearchQuery(String sql, List<Object> params) {
	}

	/**
	 * Builds the trace-search SQL (§9.4 list) and its bind parameters. Extracted
	 * so {@code TraceSearchPerformanceTest} can {@code EXPLAIN} the exact query
	 * the controller runs — a regression guard that copied the SQL would keep
	 * passing if the real query regressed.
	 *
	 * <p>One row per trace_id. A distributed trace has many transactions sharing
	 * a trace_id (browser pageload + backend request…); we represent each trace
	 * by its root transaction (parent_span_id IS NULL), or — when a filter only
	 * matches a continuation — by the best-matching transaction, so filtering by
	 * the backend project still surfaces the trace. Filters apply per-transaction;
	 * span/error counts span the whole trace.
	 */
	static SearchQuery buildSearchQuery(List<Long> project, List<String> environment, String release, String query,
			Double minDuration, Double maxDuration, Boolean hasErrors, Instant from, Instant to, String cursor) {

		StringBuilder inner = new StringBuilder("""
				SELECT DISTINCT ON (t.trace_id)
				       t.id, t.project_id, t.environment, t.release, t.trace_id, t.name, t.op,
				       t.start_ts, t.end_ts, t.duration_ms, t.status
				FROM txn t WHERE 1=1
				""");
		List<Object> params = new ArrayList<>();

		QuerySupport.appendInClause(inner, "t.project_id", project, params);
		QuerySupport.appendInClause(inner, "t.environment", environment, params);
		if (release != null && !release.isBlank()) {
			inner.append(" AND t.release = ?");
			params.add(release);
		}
		if (query != null && !query.isBlank()) {
			inner.append(" AND t.name ILIKE ?");
			params.add("%" + query + "%");
		}
		if (minDuration != null) {
			inner.append(" AND t.duration_ms >= ?");
			params.add(minDuration);
		}
		if (maxDuration != null) {
			inner.append(" AND t.duration_ms <= ?");
			params.add(maxDuration);
		}
		if (Boolean.TRUE.equals(hasErrors)) {
			inner.append(" AND EXISTS (SELECT 1 FROM event e WHERE e.trace_id = t.trace_id)");
		}
		if (from != null) {
			inner.append(" AND t.start_ts >= ?");
			params.add(java.sql.Timestamp.from(from));
		}
		if (to != null) {
			inner.append(" AND t.start_ts <= ?");
			params.add(java.sql.Timestamp.from(to));
		}
		// DISTINCT ON must lead its ORDER BY with trace_id; prefer the root, then the
		// earliest transaction, as the representative row for each trace.
		inner.append(" ORDER BY t.trace_id, (t.parent_span_id IS NULL) DESC, t.start_ts");

		StringBuilder page = new StringBuilder("SELECT * FROM (").append(inner).append(") traces WHERE 1=1");
		KeysetPage.Tail tail = PAGE.build(cursor);
		page.append(tail.sql());
		params.addAll(tail.params());

		// Count spans/errors only for the paginated representative rows — computing
		// them inside the DISTINCT ON scan runs both correlated subqueries once per
		// candidate transaction (thousands) just to discard all but one row per
		// trace; here they run for at most PAGE_SIZE+1 rows. Order is re-asserted
		// because the outer SELECT does not inherit the subquery's ordering.
		String sql = "SELECT p.*,\n"
				+ "       (SELECT count(*) FROM span s WHERE s.trace_id = p.trace_id) AS span_count,\n"
				+ "       (SELECT count(*) FROM event e WHERE e.trace_id = p.trace_id) AS error_count\n"
				+ "FROM (" + page + ") p ORDER BY p.start_ts DESC, p.id DESC";

		return new SearchQuery(sql, params);
	}

	/**
	 * Everything sharing a trace_id across all projects, in one payload:
	 * transactions + spans (waterfall-ready), error events, log records (§8).
	 * Fan-out by trace_id — cheaper and simpler than one mega-join, and each
	 * table already has a trace_id index.
	 */
	@GetMapping("/traces/{trace_id}")
	public ResponseEntity<Map<String, Object>> trace(@PathVariable("trace_id") String traceId) {
		List<Map<String, Object>> transactions = jdbc.query("""
				SELECT id, project_id, environment, release, trace_id, span_id, parent_span_id, name, op,
				       start_ts, end_ts, duration_ms, status, data
				FROM txn WHERE trace_id = ? ORDER BY start_ts
				""", (rs, i) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", rs.getObject("id", UUID.class));
			row.put("project_id", rs.getLong("project_id"));
			row.put("environment", rs.getString("environment"));
			row.put("release", rs.getString("release"));
			row.put("trace_id", rs.getString("trace_id"));
			row.put("span_id", rs.getString("span_id"));
			row.put("parent_span_id", rs.getString("parent_span_id"));
			row.put("name", rs.getString("name"));
			row.put("op", rs.getString("op"));
			row.put("start_ts", rs.getTimestamp("start_ts").toInstant());
			row.put("end_ts", rs.getTimestamp("end_ts").toInstant());
			row.put("duration_ms", rs.getDouble("duration_ms"));
			row.put("status", rs.getString("status"));
			row.put("data", QuerySupport.parseJson(mapper, rs.getString("data")));
			return row;
		}, traceId);

		if (transactions.isEmpty()) {
			// A trace is only "known" once at least one transaction has arrived. Errors
			// or logs may reference a trace_id with no transaction; treat as not found.
			boolean referenced = !jdbc.queryForList("SELECT 1 FROM event WHERE trace_id = ? LIMIT 1", traceId).isEmpty()
					|| !jdbc.queryForList("SELECT 1 FROM span WHERE trace_id = ? LIMIT 1", traceId).isEmpty();
			if (!referenced) {
				return ResponseEntity.notFound().build();
			}
		}

		List<Map<String, Object>> spans = jdbc.query("""
				SELECT id, txn_id, project_id, trace_id, span_id, parent_span_id, op, description,
				       start_ts, end_ts, duration_ms, status, data
				FROM span WHERE trace_id = ? ORDER BY start_ts
				""", (rs, i) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", rs.getObject("id", UUID.class));
			row.put("txn_id", rs.getObject("txn_id", UUID.class));
			row.put("project_id", rs.getLong("project_id"));
			row.put("trace_id", rs.getString("trace_id"));
			row.put("span_id", rs.getString("span_id"));
			row.put("parent_span_id", rs.getString("parent_span_id"));
			row.put("op", rs.getString("op"));
			row.put("description", rs.getString("description"));
			row.put("start_ts", rs.getTimestamp("start_ts").toInstant());
			row.put("end_ts", rs.getTimestamp("end_ts").toInstant());
			row.put("duration_ms", rs.getDouble("duration_ms"));
			row.put("status", rs.getString("status"));
			row.put("data", QuerySupport.parseJson(mapper, rs.getString("data")));
			return row;
		}, traceId);

		List<Map<String, Object>> errors = jdbc.query("""
				SELECT id, project_id, issue_id, environment, "timestamp", trace_id, level, message, exception_type,
				       data #>> '{contexts,trace,span_id}' AS span_id
				FROM event WHERE trace_id = ? ORDER BY "timestamp"
				""", (rs, i) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", rs.getObject("id", UUID.class));
			row.put("project_id", rs.getLong("project_id"));
			row.put("issue_id", rs.getLong("issue_id"));
			row.put("environment", rs.getString("environment"));
			row.put("timestamp", rs.getTimestamp("timestamp").toInstant());
			row.put("span_id", rs.getString("span_id"));
			row.put("level", rs.getString("level"));
			row.put("message", rs.getString("message"));
			row.put("exception_type", rs.getString("exception_type"));
			return row;
		}, traceId);

		List<Map<String, Object>> logs = jdbc.query("""
				SELECT id, project_id, environment, "timestamp", trace_id, span_id, level, severity_number,
				       body, attributes, release
				FROM log_record WHERE trace_id = ? ORDER BY "timestamp"
				""", (rs, i) -> {
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
		}, traceId);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("trace_id", traceId);
		body.put("transactions", transactions);
		body.put("spans", spans);
		body.put("errors", errors);
		body.put("logs", logs);
		return ResponseEntity.ok(body);
	}
}
