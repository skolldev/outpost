package dev.outpost.query;

import java.sql.Array;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Performance leaderboard: {@code txn} aggregated into Transaction Groups
 * (Transactions sharing a Project, name, op), ranked by {@link #SORTS}, plus the
 * single-group detail view a leaderboard row opens into. Session-authenticated,
 * no Admin restriction; per ADR-0014, {@code name} is never normalized and
 * {@code txn.status} is never surfaced here (that's answered by Issues).
 */
@RestController
@RequestMapping("/api/internal")
public class TransactionGroupController {

	/**
	 * The most groups one response carries; there is no cursor by design — keyset
	 * pagination doesn't apply since these rows don't exist until every Transaction
	 * in the window is grouped (ADR-0015). Past the limit, filter rather than page.
	 */
	private static final int MAX_GROUPS = 100;

	/**
	 * The fewest Transactions a Transaction Group must contain to be ranked; below
	 * this, a single-sample group's duration would masquerade as its p50/p95/p99 and
	 * outrank real problems. Excluded groups still count toward
	 * {@link Leaderboard#distinctGroups()}.
	 */
	private static final int MIN_SAMPLES = 5;

	/**
	 * The widest window this endpoint will aggregate over (ADR-0015). "All time"
	 * yields no {@code start_ts} predicate, and without one the aggregate would
	 * read every weekly partition of {@code txn} ever created.
	 */
	private static final Duration MAX_WINDOW = Duration.ofDays(30);

	/**
	 * The accepted sort keys, mapped to the SQL expression each orders by — also
	 * the injection whitelist, since every value here is a literal, never taken
	 * from the request. Percentile sorts index the ordered-set aggregate's array
	 * ({@code [1]}=p50, {@code [2]}=p95), wrapped in a subquery because
	 * {@code ORDER BY} can't reference {@code percentiles[2]} directly.
	 */
	private static final Map<String, String> SORTS = sorts();

	/** What {@code sort} means when a request does not say — ADR-0015's "where fixing something pays off most". */
	private static final String DEFAULT_SORT = "total_ms";

	private static Map<String, String> sorts() {
		Map<String, String> sorts = new LinkedHashMap<>();
		sorts.put("total_ms", "total_ms");
		sorts.put("p95", "percentiles[2]");
		sorts.put("p50", "percentiles[1]");
		sorts.put("count", "txn_count");
		return Collections.unmodifiableMap(sorts);
	}

	/**
	 * Every statistic a Transaction Group reports, shared by the leaderboard and
	 * detail view so the two screens can't disagree about the same group's numbers.
	 * One ordered-set aggregate reads all three percentile probes off a single
	 * sort; {@code min} is omitted since it just measures cache hits.
	 */
	private static final String STATISTICS = """
			count(*) AS txn_count,
			       sum(duration_ms) AS total_ms,
			       avg(duration_ms) AS avg_ms,
			       max(duration_ms) AS max_ms,
			       percentile_cont(ARRAY[0.5, 0.95, 0.99]::double precision[])
			           WITHIN GROUP (ORDER BY duration_ms) AS percentiles""";

	/** One Transaction Group's duration statistics over the resolved window. */
	public record TransactionGroup(long projectId, String name, String op, long count, double totalMs, double avgMs,
			double maxMs, double p50Ms, double p95Ms, double p99Ms) {
	}

	/**
	 * The leaderboard plus the window it was actually computed over, with
	 * {@code rangeClamped} true when the server narrowed it and {@code truncated}
	 * true when the list was cut at {@link #MAX_GROUPS}.
	 *
	 * @param distinctGroups every distinct (Project, name, op) in the window,
	 * counted before the sample floor — counting after would read near zero on
	 * the exact data this exists to warn about (ADR-0014)
	 */
	public record Leaderboard(Instant from, Instant to, boolean rangeClamped, long distinctGroups, boolean truncated,
			List<TransactionGroup> groups) {
	}

	/**
	 * One duration-trend bucket: p50 and p95 (no p99, too noisy at this sample
	 * size) over the Transactions in this interval, plus how many there were.
	 * {@code count} is reported rather than filtered on, so a quiet interval
	 * draws a gap rather than a false continuous line.
	 */
	public record TrendPoint(Instant start, long count, double p50Ms, double p95Ms) {
	}

	/**
	 * {@code from} here is the requested window's start floored onto the bucket
	 * grid, not {@link GroupDetail#from()} — the query itself still runs against
	 * the unwidened window, unlike {@code LogController}'s timeline, so the chart
	 * and the header describe exactly the same Transactions. Empty buckets are
	 * omitted rather than zero-filled, since a bucket with no Transactions has no p50.
	 */
	public record Trend(Instant from, long bucketSeconds, List<TrendPoint> points) {
	}

	/**
	 * One Transaction Group's statistics, the window they were computed over
	 * (echoed with {@code rangeClamped} for the same reason {@link Leaderboard}
	 * does), and the same statistics bucketed across it. Unlike the Log Timeline's
	 * split (ADR-0011), the trend rides along in this response rather than a second
	 * endpoint, since this view doesn't paginate and so has no cursor to recompute against.
	 */
	public record GroupDetail(Instant from, Instant to, boolean rangeClamped, TransactionGroup group, Trend trend) {
	}

	/** A missing group still carries the resolved window, so a clamp is never silent. */
	public record GroupDetailNotFound(Instant from, Instant to, boolean rangeClamped, String detail) {
	}

	private final JdbcClient jdbc;

	public TransactionGroupController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/transaction-groups")
	public ResponseEntity<?> leaderboard(@RequestParam(required = false) List<Long> project,
			@RequestParam(required = false) List<String> environment, @RequestParam(required = false) String release,
			@RequestParam(required = false) String query, @RequestParam(defaultValue = DEFAULT_SORT) String sort,
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
		if (from != null && to != null && !from.isBefore(to)) {
			return ResponseEntity.badRequest().body(Map.of("detail", "from must be before to"));
		}
		// Rejected, not coerced to the default — silently substituting a ranking would look like the one the client asked for.
		if (!SORTS.containsKey(sort)) {
			return ResponseEntity.badRequest()
				.body(Map.of("detail", "sort must be one of " + String.join(", ", SORTS.keySet())));
		}

		Window window = Window.resolve(from, to);
		SearchQuery search = buildLeaderboardQuery(project, environment, release, query, sort, window.from(),
				window.to());

		List<TransactionGroup> groups = jdbc.sql(search.sql()).params(search.params()).query((rs, i) -> {
			// percentile_cont(ARRAY[...]) returns one array per group, in probe order: p50, p95, p99.
			Array percentiles = rs.getArray("percentiles");
			Double[] p = (Double[]) percentiles.getArray();
			return new TransactionGroup(rs.getLong("project_id"), rs.getString("name"), rs.getString("op"),
					rs.getLong("txn_count"), rs.getDouble("total_ms"), rs.getDouble("avg_ms"), rs.getDouble("max_ms"),
					p[0], p[1], p[2]);
		}).list();

		// Asks for one more group than it returns, so truncation is read off the rows in hand rather than counted again.
		boolean truncated = groups.size() > MAX_GROUPS;
		if (truncated) {
			groups = groups.subList(0, MAX_GROUPS);
		}

		SearchQuery cardinality = buildDistinctGroupQuery(project, environment, release, query, window.from(),
				window.to());
		long distinctGroups = jdbc.sql(cardinality.sql()).params(cardinality.params()).query(Long.class).single();

		return ResponseEntity
			.ok(new Leaderboard(window.from(), window.to(), window.clamped(), distinctGroups, truncated, groups));
	}

	/**
	 * One Transaction Group's statistics over the resolved window, for the detail
	 * view a leaderboard row opens into. An absent {@code op} means {@code op} is
	 * null, not "any op" — the key is (Project, name, op), so averaging across ops
	 * would conflate a route's pageload with its navigation; {@link #MIN_SAMPLES}
	 * doesn't apply here since this view ranks nothing.
	 *
	 * @return 404 when no Transaction in the window matches the key
	 */
	@GetMapping("/transaction-groups/detail")
	public ResponseEntity<?> detail(@RequestParam long project, @RequestParam String name,
			@RequestParam(required = false) String op, @RequestParam(required = false) List<String> environment,
			@RequestParam(required = false) String release, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to) {
		if (from != null && to != null && !from.isBefore(to)) {
			return ResponseEntity.badRequest().body(Map.of("detail", "from must be before to"));
		}
		if (name.isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("detail", "name is required"));
		}

		// Blank is null: an empty and an omitted op param mean the same group; no SDK sends op="".
		String resolvedOp = op != null && !op.isBlank() ? op : null;
		Window window = Window.resolve(from, to);
		SearchQuery search = buildDetailQuery(project, name, resolvedOp, environment, release, window.from(),
				window.to());

		// No GROUP BY means the aggregate always returns exactly one row, so a missing group is a zero count, not an empty result.
		Optional<TransactionGroup> group = jdbc.sql(search.sql()).params(search.params()).query((rs, i) -> {
			long count = rs.getLong("txn_count");
			if (count == 0) {
				return null;
			}
			// percentile_cont(ARRAY[...]) returns one array, in probe order: p50, p95, p99.
			Double[] p = (Double[]) rs.getArray("percentiles").getArray();
			return new TransactionGroup(project, name, resolvedOp, count, rs.getDouble("total_ms"),
					rs.getDouble("avg_ms"), rs.getDouble("max_ms"), p[0], p[1], p[2]);
		}).optional();

		// Trend is only computed once the group is known to exist, since a 404 has no chart to draw and running the aggregate anyway would be a wasted full pass.
		return group
			.<ResponseEntity<?>>map(found -> ResponseEntity.ok(new GroupDetail(window.from(), window.to(),
					window.clamped(), found, trend(project, name, resolvedOp, environment, release, window))))
			.orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new GroupDetailNotFound(window.from(), window.to(), window.clamped(),
						"no Transactions in this Transaction Group for the current filters")));
	}

	/**
	 * The bucketed series behind the chart, on the grid {@link Trend} describes.
	 * Bucket width comes from {@link TimeBuckets} — the same rungs the Log Timeline
	 * uses — so at the 30-day cap (ADR-0015) that's 6 hours, ~120 points.
	 */
	private Trend trend(long project, String name, String op, List<String> environment, String release,
			Window window) {
		Duration bucket = TimeBuckets.width(window.from(), window.to());
		SearchQuery search = buildTrendQuery(project, name, op, environment, release, window.from(), window.to());

		List<TrendPoint> points = jdbc.sql(search.sql()).params(search.params()).query((rs, i) -> {
			// percentile_cont(ARRAY[...]) returns one array per bucket, in probe order: p50, p95 (no p99 — see TrendPoint).
			Double[] p = (Double[]) rs.getArray("percentiles").getArray();
			return new TrendPoint(rs.getTimestamp("bucket").toInstant(), rs.getLong("txn_count"), p[0], p[1]);
		}).list();

		return new Trend(TimeBuckets.alignDown(window.from(), bucket), bucket.toSeconds(), points);
	}

	/** The window a request is answered over, and whether the server had to narrow it to get there. */
	public record Window(Instant from, Instant to, boolean clamped) {

		static Window resolve(Instant from, Instant to) {
			Instant upper = to != null ? to : Instant.now();
			Instant earliest = upper.minus(MAX_WINDOW);
			if (from != null && !from.isBefore(earliest)) {
				return new Window(from, upper, false);
			}
			return new Window(earliest, upper, true);
		}
	}

	/**
	 * The leaderboard query the controller runs, extracted per {@link SearchQuery}.
	 * {@code HAVING} runs before {@code ORDER BY} so {@link #MIN_SAMPLES} applies
	 * to every sort including the percentile ones, and ties break on the full
	 * grouping key (project_id, name, op) so identical-total-time groups don't swap
	 * places — and, with a {@code LIMIT} on top, so list membership stays stable
	 * across identical requests.
	 *
	 * @param sort a key of {@link #SORTS}; any other value throws rather than reaching the statement
	 */
	static SearchQuery buildLeaderboardQuery(List<Long> project, List<String> environment, String release, String query,
			String sort, Instant from, Instant to) {

		String order = SORTS.get(sort);
		if (order == null) {
			throw new IllegalArgumentException("unknown sort: " + sort);
		}

		StringBuilder sql = new StringBuilder("""
				SELECT * FROM (
				SELECT project_id, name, op,
				       %s
				FROM txn WHERE 1=1
				""".formatted(STATISTICS));
		List<Object> params = new ArrayList<>();
		appendFilters(sql, project, environment, release, query, from, to, params);

		// One more than MAX_GROUPS: the extra row, never returned, only signals truncation.
		sql.append("""

				GROUP BY project_id, name, op
				HAVING count(*) >= %d
				) g
				ORDER BY %s DESC, project_id, name, op
				LIMIT %d
				""".formatted(MIN_SAMPLES, order, MAX_GROUPS + 1));
		return new SearchQuery(sql.toString(), params);
	}

	/**
	 * One Transaction Group's statistics, extracted per {@link SearchQuery}. No
	 * {@code GROUP BY} — the key is fully bound, so an empty window comes back as a
	 * count of zero rather than no row at all, which is what lets the endpoint tell
	 * a quiet group apart from an error.
	 */
	static SearchQuery buildDetailQuery(long project, String name, String op, List<String> environment, String release,
			Instant from, Instant to) {

		StringBuilder sql = new StringBuilder("""
				SELECT %s
				FROM txn WHERE 1=1
				""".formatted(STATISTICS));
		List<Object> params = new ArrayList<>();
		appendFilters(sql, List.of(project), environment, release, null, from, to, params);
		appendKey(sql, name, op, params);
		return new SearchQuery(sql.toString(), params);
	}

	/**
	 * The same statistics bucketed across the window, for the chart — extracted per
	 * {@link SearchQuery}. Predicates are unchanged from the detail view's (window
	 * deliberately not widened onto the bucket grid, unlike {@code LogController}'s
	 * timeline — see {@link Trend}), and results are ordered here since a line
	 * chart, unlike the timeline's sparse bars, must be drawn in time order.
	 */
	static SearchQuery buildTrendQuery(long project, String name, String op, List<String> environment, String release,
			Instant from, Instant to) {

		StringBuilder sql = new StringBuilder("""
				SELECT date_bin(?::interval, start_ts, ?) AS bucket,
				       count(*) AS txn_count,
				       percentile_cont(ARRAY[0.5, 0.95]::double precision[])
				           WITHIN GROUP (ORDER BY duration_ms) AS percentiles
				FROM txn WHERE 1=1
				""");
		List<Object> params = new ArrayList<>();
		params.add(TimeBuckets.width(from, to).toSeconds() + " seconds");
		params.add(TimeBuckets.originParam());

		appendFilters(sql, List.of(project), environment, release, null, from, to, params);
		appendKey(sql, name, op, params);

		sql.append("\nGROUP BY bucket ORDER BY bucket");
		return new SearchQuery(sql.toString(), params);
	}

	/**
	 * The two predicates that pin one Transaction Group, shared by the summary and
	 * the trend so they can never name different groups. Built in Java as two
	 * branches rather than {@code IS NOT DISTINCT FROM}: that operator can't use
	 * the index on {@code idx_txn_performance}'s third column, where {@code = ?}
	 * and {@code IS NULL} can.
	 */
	private static void appendKey(StringBuilder sql, String name, String op, List<Object> params) {
		sql.append(" AND name = ?");
		params.add(name);
		if (op == null) {
			sql.append(" AND op IS NULL");
		}
		else {
			sql.append(" AND op = ?");
			params.add(op);
		}
	}

	/**
	 * How many distinct Transaction Groups the window holds — the honest disclosure
	 * ADR-0014 requires in place of normalizing names. A second pass over
	 * {@code txn}, deliberately: folding this into the leaderboard's own statement
	 * would only count groups surviving {@link #MIN_SAMPLES}, which is worthless
	 * for the reason {@link Leaderboard#distinctGroups()} records.
	 */
	static SearchQuery buildDistinctGroupQuery(List<Long> project, List<String> environment, String release,
			String query, Instant from, Instant to) {

		StringBuilder inner = new StringBuilder("""
				SELECT project_id, name, op FROM txn WHERE 1=1
				""");
		List<Object> params = new ArrayList<>();
		appendFilters(inner, project, environment, release, query, from, to, params);
		inner.append("\nGROUP BY project_id, name, op");

		return new SearchQuery("SELECT count(*) FROM (%s) g".formatted(inner), params);
	}

	/**
	 * The predicates both statements share; they must stay identical, or
	 * {@code distinct_groups} would warn about a different window than the list it
	 * annotates. Window is closed-open ({@code [from, to)}), matching
	 * {@code LogController}'s convention; neither {@code release} nor {@code query}
	 * joins the grouping key, so a Transaction Group spanning three Releases stays one group.
	 */
	private static void appendFilters(StringBuilder sql, List<Long> project, List<String> environment, String release,
			String query, Instant from, Instant to, List<Object> params) {
		QuerySupport.appendInClause(sql, "project_id", project, params);
		QuerySupport.appendInClause(sql, "environment", environment, params);
		if (release != null && !release.isBlank()) {
			sql.append(" AND release = ?");
			params.add(release);
		}
		if (query != null && !query.isBlank()) {
			sql.append(" AND name ILIKE ?");
			params.add("%" + query + "%");
		}
		sql.append(" AND start_ts >= ?");
		params.add(java.sql.Timestamp.from(from));
		sql.append(" AND start_ts < ?");
		params.add(java.sql.Timestamp.from(to));
	}

	/** The window the endpoint would answer this request over. */
	static Window window(Instant from, Instant to) {
		return Window.resolve(from, to);
	}

	static List<String> sortKeys() {
		return List.copyOf(SORTS.keySet());
	}

	/**
	 * {@link #MAX_GROUPS} and {@link #MIN_SAMPLES}, exposed for the MCP Surface's
	 * {@code performance_overview} Tool to disclose in its caveats — reading the
	 * constants here avoids a copy going stale if the limits change.
	 */
	static int maxGroups() {
		return MAX_GROUPS;
	}

	static int minSamples() {
		return MIN_SAMPLES;
	}

}
