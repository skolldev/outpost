package dev.outpost.query;

import tools.jackson.databind.ObjectMapper;
import dev.outpost.db.PartitionManager;
import dev.outpost.pipeline.LogTail;
import dev.outpost.pipeline.ProcessedLog;
import java.time.Duration;
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
 * Log query API: keyset-paginated descending stream with level / env /
 * trace / attribute / body-substring filters; {@code ?live=true} upgrades to
 * an SSE tail fed by the ingest pipeline with the same filters applied.
 * Attribute filters come as {@code attr=key=value} (value optional — bare
 * {@code attr=key} matches presence).
 */
@RestController
@RequestMapping("/api/internal")
public class LogController {

	/** The list page size, and therefore the ceiling on any per-call limit built over this keyset. */
	static final int PAGE_SIZE = 100;

	/** Log list keyset by {@code (timestamp, id)}; the SQL column is quoted, the row key is not. */
	private static final KeysetPage PAGE = KeysetPage.of(KeysetPage.KeyColumn.instant("\"timestamp\"", "timestamp"),
			KeysetPage.KeyColumn.uuid("id"), PAGE_SIZE);

	private final JdbcTemplate jdbc;
	private final LogTail tail;
	private final ObjectMapper mapper;
	private final PartitionManager partitions;

	public LogController(JdbcTemplate jdbc, LogTail tail, ObjectMapper mapper, PartitionManager partitions) {
		this.jdbc = jdbc;
		this.tail = tail;
		this.mapper = mapper;
		this.partitions = partitions;
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

		SearchQuery search = buildLogQuery(project, environment, level, traceId, release, query, attr, from, to, cursor);

		List<Map<String, Object>> rows = jdbc.query(search.sql(), (rs, i) -> {
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
		}, search.params().toArray());

		KeysetPage.Page page = PAGE.paginate(rows);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("logs", page.rows());
		body.put("next_cursor", page.nextCursor());
		return body;
	}

	static KeysetPage logPage() {
		return PAGE;
	}

	/** The log-list query the controller runs, extracted per {@link SearchQuery}. */
	static SearchQuery buildLogQuery(List<Long> project, List<String> environment, List<String> level, String traceId,
			String release, String query, List<String> attr, Instant from, Instant to, String cursor) {

		StringBuilder sql = new StringBuilder("""
				SELECT id, project_id, environment, "timestamp", trace_id, span_id, level, severity_number,
				       body, attributes, release
				FROM log_record WHERE 1=1
				""");
		List<Object> params = new ArrayList<>();

		appendFilters(sql, params, project, environment, level, traceId, release, query, attr, from, to);

		KeysetPage.Tail tail = PAGE.build(cursor);
		sql.append(tail.sql());
		params.addAll(tail.params());
		return new SearchQuery(sql.toString(), params);
	}

	/**
	 * Filters shared by the log list and timeline so both count the same rows.
	 * Window is half-open (from <= t < to) so a record on a bucket boundary isn't counted twice.
	 */
	private static void appendFilters(StringBuilder sql, List<Object> params, List<Long> project,
			List<String> environment, List<String> level, String traceId, String release, String query,
			List<String> attr, Instant from, Instant to) {

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
		for (LogAttributeFilter filter : LogAttributeFilter.parse(attr)) {
			filter.appendSql(sql, params);
		}
		if (from != null) {
			sql.append(" AND \"timestamp\" >= ?");
			params.add(java.sql.Timestamp.from(from));
		}
		if (to != null) {
			sql.append(" AND \"timestamp\" < ?");
			params.add(java.sql.Timestamp.from(to));
		}
	}

	// ---------------------------------------------------------------- timeline

	/** One bucket's per-level counts. Levels with no records are absent, not zero. */
	record TimelineBucket(Instant start, Map<String, Long> counts) {
	}

	/**
	 * The window the server resolved, the width it drew it at, and the non-empty
	 * buckets in it. The window is echoed because the client cannot compute it: on
	 * "All time" the left edge is a fact about this installation's retention.
	 */
	record Timeline(Instant from, Instant to, long bucketSeconds, List<TimelineBucket> buckets) {
	}

	/**
	 * Log counts per bucket per level, under the same filters as {@code GET /logs}.
	 * Kept as a separate endpoint — not accepting the list's cursor or brush
	 * selection — so it isn't recomputed on every page scroll and a selection can't
	 * collapse onto itself on every drag (ADR 0011).
	 */
	@GetMapping("/logs/timeline")
	public Timeline timeline(@RequestParam(required = false) List<Long> project,
			@RequestParam(required = false) List<String> environment,
			@RequestParam(required = false) List<String> level,
			@RequestParam(name = "trace_id", required = false) String traceId,
			@RequestParam(required = false) String release, @RequestParam(required = false) String query,
			@RequestParam(required = false) List<String> attr, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to) {

		Instant upper = to != null ? to : Instant.now();
		// No `from` means "All time": use the oldest retained week, not min("timestamp") (a full scan).
		Instant lower = from != null ? from
				: partitions.earliestPartitionStart(PartitionManager.LOG_RECORD).orElse(upper);
		if (lower.isAfter(upper)) {
			lower = upper;
		}
		// Snapped onto the bucket grid first, so the first bar may start slightly earlier than the requested window.
		Duration bucket = TimeBuckets.width(lower, upper);
		lower = TimeBuckets.alignDown(lower, bucket);

		SearchQuery search = buildTimelineQuery(project, environment, level, traceId, release, query, attr, lower,
				upper);
		Map<Instant, Map<String, Long>> buckets = new LinkedHashMap<>();
		jdbc.query(search.sql(), rs -> {
			buckets.computeIfAbsent(rs.getTimestamp("bucket").toInstant(), start -> new LinkedHashMap<>())
				.put(rs.getString("level"), rs.getLong("n"));
		}, search.params().toArray());

		// Reuses the width the query binned on; do not recompute it separately.
		return new Timeline(lower, upper, bucket.toSeconds(),
				buckets.entrySet().stream().map(entry -> new TimelineBucket(entry.getKey(), entry.getValue())).toList());
	}

	/**
	 * Counts per bucket per level over {@code [from, to)}, under the same filters
	 * the list runs; required here (unlike the list) since an aggregate can't stop
	 * early. Deliberately unordered — the client places buckets by index arithmetic
	 * on {@code from} and the bucket width, so an {@code ORDER BY} would sort rows
	 * nothing reads.
	 */
	static SearchQuery buildTimelineQuery(List<Long> project, List<String> environment, List<String> level,
			String traceId, String release, String query, List<String> attr, Instant from, Instant to) {

		StringBuilder sql = new StringBuilder("""
				SELECT date_bin(?::interval, "timestamp", ?) AS bucket, level, count(*) AS n
				FROM log_record WHERE 1=1
				""");
		List<Object> params = new ArrayList<>();
		params.add(TimeBuckets.width(from, to).toSeconds() + " seconds");
		params.add(TimeBuckets.originParam());

		appendFilters(sql, params, project, environment, level, traceId, release, query, attr, from, to);

		sql.append(" GROUP BY bucket, level");
		return new SearchQuery(sql.toString(), params);
	}

	/** SSE live tail — same filters, applied in-process to newly stored records. */
	@GetMapping(value = "/logs", params = "live=true")
	public SseEmitter tail(@RequestParam(required = false) List<Long> project,
			@RequestParam(required = false) List<String> environment,
			@RequestParam(required = false) List<String> level,
			@RequestParam(name = "trace_id", required = false) String traceId,
			@RequestParam(required = false) String release,
			@RequestParam(required = false) String query,
			@RequestParam(required = false) List<String> attr) {

		List<LogAttributeFilter> attrFilters = LogAttributeFilter.parse(attr);
		String bodyNeedle = query != null && !query.isBlank() ? query.toLowerCase(Locale.ROOT) : null;
		Predicate<ProcessedLog> filter = record -> (project == null || project.isEmpty()
				|| project.contains(record.projectId()))
				&& (environment == null || environment.isEmpty() || environment.contains(record.environment()))
				&& (level == null || level.isEmpty() || level.contains(record.level()))
				&& (traceId == null || traceId.isBlank() || traceId.equals(record.traceId()))
				&& (release == null || release.isBlank() || release.equals(record.release()))
				&& (bodyNeedle == null || record.body().toLowerCase(Locale.ROOT).contains(bodyNeedle))
				&& attrFilters.stream().allMatch(f -> f.matches(record));
		return tail.subscribe(filter);
	}
}
