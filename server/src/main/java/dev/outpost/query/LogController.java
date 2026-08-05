package dev.outpost.query;

import tools.jackson.databind.JsonNode;
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

	private static final int PAGE_SIZE = 100;

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

	/**
	 * The keyset the log stream is paged by. Package-visible so a guard can walk
	 * real cursors to a deep page rather than synthesize one.
	 */
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
	 * Every filter the log stream understands, shared by the list and the timeline.
	 *
	 * <p>Shared rather than duplicated because the timeline's only claim is that it
	 * counts <em>the rows the list would return</em>. Two copies of this that drifted
	 * by one predicate would draw a chart of a different query than the one below
	 * it, and nothing in either result would say so.
	 *
	 * <p>The window is half-open — {@code from <= t < to}. The upper bound used to be
	 * inclusive, which no caller could observe until the timeline made {@code to} a
	 * bucket boundary: a record landing exactly on one would be returned by both of
	 * the selections either side of it.
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
	 *
	 * <p>Separate from the list rather than folded into its response because the two
	 * have different lifecycles: the list re-runs on every {@code cursor}, and
	 * recomputing a full-window aggregate to fetch 100 more rows is the one shape
	 * this endpoint must never take.
	 *
	 * <p>It deliberately does <b>not</b> accept the logs page's brush selection. The
	 * chart spans the range so a selection can be seen in context; feeding the
	 * selection back in would collapse it onto itself on every drag (ADR 0011).
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
		// No `from` is the range picker's "All time". The left edge is then the oldest
		// week still retained — a catalogue read, where min("timestamp") would scan
		// every partition of the largest table in the product.
		Instant lower = from != null ? from
				: partitions.earliestPartitionStart(PartitionManager.LOG_RECORD).orElse(upper);
		if (lower.isAfter(upper)) {
			lower = upper;
		}
		// Snapped onto the bucket grid before anything else sees it, so the window the
		// response reports is the window its buckets actually start on. The first bar
		// therefore covers a whole bucket, reaching slightly further back than asked.
		Duration bucket = timelineBucket(lower, upper);
		lower = alignDown(lower, bucket);

		SearchQuery search = buildTimelineQuery(project, environment, level, traceId, release, query, attr, lower,
				upper);
		Map<Instant, Map<String, Long>> buckets = new LinkedHashMap<>();
		jdbc.query(search.sql(), rs -> {
			buckets.computeIfAbsent(rs.getTimestamp("bucket").toInstant(), start -> new LinkedHashMap<>())
				.put(rs.getString("level"), rs.getLong("n"));
		}, search.params().toArray());

		// The same width the query binned on: recomputing it here would be correct today
		// only because `alignDown` can't push the window onto a finer rung, which is not
		// an argument a later change to the ladder would keep true.
		return new Timeline(lower, upper, bucket.toSeconds(),
				buckets.entrySet().stream().map(entry -> new TimelineBucket(entry.getKey(), entry.getValue())).toList());
	}

	/**
	 * Bucket widths the timeline may draw at, smallest first. 7d is the last rung
	 * deliberately: past it the honest failure is more bars, not a bucket silently
	 * becoming a month.
	 */
	private static final List<Duration> TIMELINE_RUNGS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
			Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(4), Duration.ofHours(6),
			Duration.ofDays(1), Duration.ofDays(7));

	private static final int MAX_TIMELINE_BUCKETS = 150;

	/**
	 * Monday 1970-01-05 — the origin every bucket is binned from. Midnight UTC, so
	 * every sub-day rung lands on a clock boundary; a Monday, so the weekly rung
	 * agrees with the weeks {@code PartitionManager} cuts partitions on rather than
	 * starting on the epoch's Thursday.
	 */
	private static final Instant TIMELINE_ORIGIN = Instant.parse("1970-01-05T00:00:00Z");

	/**
	 * The bucket width a window of this length is drawn at: the smallest rung
	 * yielding at most {@value #MAX_TIMELINE_BUCKETS} bars once the window has been
	 * aligned to that rung's grid.
	 *
	 * <p>A rule rather than a {@code range -> rung} map because the window is not
	 * always one of the range picker's: "All time" is however long this installation
	 * has retained logs, and a future zoom-to-selection would hand it arbitrary
	 * windows. Package-visible for the same reason {@code IssueController
	 * .sparklineSince} is — it decides a bind parameter, so a guard computing its
	 * own would plan a query the controller never runs.
	 *
	 * <p>The bar count is a <em>ceiling</em>, and it counts from {@link
	 * #alignDown(Instant, Duration) the aligned start}, because both are what the
	 * client will draw: a window covering 150.9 rungs occupies 151 bars, and one
	 * starting mid-bucket occupies one more than its length suggests.
	 */
	static Duration timelineBucket(Instant from, Instant to) {
		for (Duration rung : TIMELINE_RUNGS) {
			if (barCount(from, to, rung) <= MAX_TIMELINE_BUCKETS) {
				return rung;
			}
		}
		return TIMELINE_RUNGS.getLast();
	}

	/** How many whole buckets of {@code rung} the aligned window spans. */
	private static long barCount(Instant from, Instant to, Duration rung) {
		long width = rung.toSeconds();
		long span = to.getEpochSecond() - alignDown(from, rung).getEpochSecond();
		return Math.max(1, Math.ceilDiv(span, width));
	}

	/**
	 * {@code instant} floored onto the grid {@code date_bin} bins to.
	 *
	 * <p>Load-bearing, and the reason is easy to miss. {@code date_bin} bins from
	 * {@link #TIMELINE_ORIGIN}, so a bucket always starts at {@code origin + k·width}
	 * — never at whatever instant the request happened to ask for. The client places
	 * buckets by {@code (bucket.start - response.from) / width}, so unless the
	 * {@code from} it is handed sits on that same grid, every bucket lands one index
	 * low, the first one floors to {@code -1} and is dropped, and the whole chart
	 * shifts a bar left. Aligning here — and echoing the aligned instant — is what
	 * makes the response's own arithmetic come out whole.
	 */
	private static Instant alignDown(Instant instant, Duration bucket) {
		long width = bucket.toSeconds();
		long offset = instant.getEpochSecond() - TIMELINE_ORIGIN.getEpochSecond();
		return TIMELINE_ORIGIN.plusSeconds(Math.floorDiv(offset, width) * width);
	}

	/**
	 * Counts per bucket per level over {@code [from, to)}, under the same filters
	 * the list runs.
	 *
	 * <p><b>This is the first query in the log feature whose cost is O(matching
	 * rows) rather than O(page), and no index changes that</b> — {@code V11} bought
	 * an ordered walk that stops once 100 rows are in hand, and an aggregate cannot
	 * stop early. What bounds it is the window, which is why {@code from} and
	 * {@code to} are required here while the list treats both as optional.
	 *
	 * <p>Deliberately unordered: the client places sparse buckets by index arithmetic
	 * on {@code from} and the bucket width, so an {@code ORDER BY} would buy a sort
	 * nothing reads.
	 */
	static SearchQuery buildTimelineQuery(List<Long> project, List<String> environment, List<String> level,
			String traceId, String release, String query, List<String> attr, Instant from, Instant to) {

		StringBuilder sql = new StringBuilder("""
				SELECT date_bin(?::interval, "timestamp", ?) AS bucket, level, count(*) AS n
				FROM log_record WHERE 1=1
				""");
		List<Object> params = new ArrayList<>();
		params.add(timelineBucket(from, to).toSeconds() + " seconds");
		params.add(java.sql.Timestamp.from(TIMELINE_ORIGIN));

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
