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
 * The Performance leaderboard: {@code txn} aggregated into Transaction Groups —
 * the recurring activity that Transactions sharing a (Project, name, op) are
 * instances of — ranked by the total time each one accounts for, or by whichever
 * of {@link #SORTS} the request asks for — and the detail view one of those rows
 * opens into, which is the same statistics for a single group.
 *
 * <p>Session-authenticated with no Admin restriction: Members inspect telemetry.
 *
 * <p>Two constraints from ADR-0014 shape what this does <em>not</em> return.
 * {@code name} is grouped exactly as the SDK sent it — never normalized, at
 * ingest or here — so a Project that does not parameterize its URLs gets one
 * group per URL and the honest disclosure is the group count, not a rewrite. And
 * nothing derived from {@code txn.status} appears: "is it broken" is answered by
 * Issues, from error Events, and a second weaker answer here would disagree with
 * it.
 */
@RestController
@RequestMapping("/api/internal")
public class TransactionGroupController {

	/**
	 * The most groups one response carries. There is no cursor and there will not
	 * be one: <b>keyset pagination is impossible on an aggregate</b>. A page is
	 * seeked to by a key the previous page ended on, and none of these rows exists
	 * until every Transaction in the window has been read and grouped — so there is
	 * nothing to seek on, and "the next 100" costs exactly what the first 100 cost.
	 * This is a deliberate divergence from the {@link KeysetPage} every other list
	 * surface in Outpost uses (ADR-0015); the way past the limit is to filter, not
	 * to page.
	 */
	private static final int MAX_GROUPS = 100;

	/**
	 * The fewest Transactions a Transaction Group must contain to be ranked at all.
	 *
	 * <p>A percentile over one sample is not a percentile — it is that one duration
	 * wearing three different labels — so a group with a single 30-second Transaction
	 * in it reports a p99 of 30 seconds and outranks every real problem on the page.
	 * Excluded groups are not hidden: they are still counted in
	 * {@link Leaderboard#distinctGroups()}, which is what the cardinality warning
	 * reads.
	 */
	private static final int MIN_SAMPLES = 5;

	/**
	 * The widest window this endpoint will aggregate over (ADR-0015). The global
	 * range filter offers "All time", which yields no {@code from} and therefore no
	 * {@code start_ts} predicate — and an aggregate with no {@code start_ts}
	 * predicate reads every weekly partition of {@code txn} that has ever existed
	 * and sorts every duration in it.
	 */
	private static final Duration MAX_WINDOW = Duration.ofDays(30);

	/**
	 * The sorts this endpoint answers, each mapped to the expression it orders by.
	 *
	 * <p><b>This map is the whitelist, and it is the reason no user input reaches the
	 * SQL.</b> The request carries a key, never an expression: an unrecognised key is
	 * rejected by {@link #leaderboard} before a statement is built, and
	 * {@link #buildLeaderboardQuery} throws rather than interpolating one it does not
	 * know — so a future caller that forgets the check cannot turn this into an
	 * injection point either. Every value here is a literal written in this file.
	 *
	 * <p>All four sort descending, because every one of them is a "worst first"
	 * question: which group costs the most in aggregate, which has the worst tail,
	 * which is uniformly slowest, which is called most. An ascending order would rank
	 * by "least interesting", which nothing asks for.
	 *
	 * <p>The percentile sorts subscript the array the ordered-set aggregate already
	 * produced ({@code [1]} is p50, {@code [2]} p95) rather than computing a second
	 * one. That is also why the statement wraps the aggregate in a subquery: an
	 * {@code ORDER BY} in the same query level cannot see an output alias inside a
	 * larger expression, so {@code percentiles[2]} there would be read as an input
	 * column and fail.
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
	 * Every statistic a Transaction Group reports, as one select list shared by the
	 * leaderboard and the detail view.
	 *
	 * <p>Shared rather than written twice because the detail view is the header the
	 * leaderboard row opens into: two copies of this list would let the same group
	 * report a different p95 on the two screens the user reads in sequence, and the
	 * difference would be invisible until someone compared them. One ordered-set
	 * aggregate, not three — {@code percentile_cont(ARRAY[…])} sorts each group's
	 * durations once and reads all three probes off that single sort — and no
	 * {@code min}, which measures cache hits.
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
	 * The leaderboard plus the window it was actually computed over, and the two
	 * facts the client needs to know what it is <em>not</em> looking at.
	 *
	 * <p>The window is echoed because the server may have narrowed it, and
	 * {@code rangeClamped} says so out loud. Clamping silently was the alternative,
	 * and it is the shape that produces "the numbers are wrong" reports: the figures
	 * would quietly describe less time than the filter the user can see.
	 *
	 * <p>{@code truncated} says the list was cut at {@link #MAX_GROUPS}, so the user
	 * knows they are reading the top of a longer list rather than all of it.
	 *
	 * @param distinctGroups every distinct (Project, name, op) in the window,
	 * <b>counted before the sample floor and before the limit</b>. Both exclusions
	 * are deliberately invisible to it, because this number exists to answer "why
	 * does my Performance view look like noise" — and the Project that asks is the
	 * one emitting a Transaction Group per unparameterized URL, whose groups hold one
	 * or two Transactions each and are therefore exactly what the floor removes.
	 * Counted after the floor it would come back near zero on the only data that
	 * needs it, and the warning it feeds would never fire. Names are reported, never
	 * rewritten (ADR-0014).
	 */
	public record Leaderboard(Instant from, Instant to, boolean rangeClamped, long distinctGroups, boolean truncated,
			List<TransactionGroup> groups) {
	}

	/**
	 * One bucket of the duration trend: the same two percentiles the header reports,
	 * over the Transactions that landed in this interval, and how many there were.
	 *
	 * <p>p99 is deliberately absent where the header carries it. A p99 over a
	 * six-hour bucket is computed from a fiftieth of the samples the header's is, so
	 * it is mostly noise — it would be the jumpiest line on the chart while saying
	 * the least, and three overlaid series is one more than the eye separates.
	 *
	 * <p>{@code count} is what says how much a point is worth: a bucket holding four
	 * Transactions has a p95 that is one of them, and a spike drawn from it is not a
	 * regression. It is reported rather than filtered on, because a quiet interval is
	 * a fact about the endpoint and dropping it would draw a continuous line across a
	 * gap.
	 */
	public record TrendPoint(Instant start, long count, double p50Ms, double p95Ms) {
	}

	/**
	 * The bucketed series behind the detail view's chart: the grid it is binned on,
	 * and the non-empty buckets in it.
	 *
	 * <p><b>{@code from} is not {@link GroupDetail#from()}, and the difference is the
	 * point of the field.</b> {@code date_bin} bins from a fixed origin, so a bucket
	 * starts at {@code origin + k·width} and never at the instant the request asked
	 * for; the client places points by {@code (start - from) / width}, which only
	 * comes out whole against the grid. So this is the requested window's start
	 * <em>floored onto that grid</em> — earlier than the window by less than one
	 * bucket.
	 *
	 * <p>The query behind it is still bound by the window the header reports, not by
	 * this instant. That is the opposite of what {@code LogController}'s timeline does
	 * — it widens its window to the grid so every bar is whole — and the reason is
	 * that these two things are read together: the chart and the statistics above it
	 * describe <b>exactly the same Transactions</b>, and a chart quietly averaging in
	 * six hours the header excluded is the disagreement this whole endpoint is written
	 * to avoid. The cost is that the first bucket, and the last, hold part of an
	 * interval — visible in their {@code count}, and harmless to a percentile.
	 *
	 * <p>Empty buckets are absent rather than zero-filled: a bucket with no
	 * Transactions has no p50, and reporting one as {@code 0} would draw a
	 * catastrophic-looking dip where the truth is "nothing happened".
	 */
	public record Trend(Instant from, long bucketSeconds, List<TrendPoint> points) {
	}

	/**
	 * One Transaction Group's statistics, the window they were computed over, and the
	 * same statistics bucketed across it.
	 *
	 * <p>The window is echoed and {@code rangeClamped} raised for the same reason
	 * {@link Leaderboard} does it: this view is reached from a row on that one, over the
	 * same global range filter, and a detail header that quietly covered a different
	 * window than the list it opened from would disagree with the number the user just
	 * clicked.
	 *
	 * <p>The trend rides along in this response rather than in a second endpoint — the
	 * opposite of the split ADR-0011 chose for the Log Timeline. That split existed
	 * because the log list re-runs on every {@code cursor} and would have recomputed a
	 * full-window aggregate to fetch 100 more rows; this view does not paginate, so one
	 * request is both correct and cheaper.
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
		// Rejected, never coerced to the default: a client asking for a ranking this
		// endpoint cannot produce would otherwise be handed a different one silently, and
		// read it as the one it asked for.
		if (!SORTS.containsKey(sort)) {
			return ResponseEntity.badRequest()
				.body(Map.of("detail", "sort must be one of " + String.join(", ", SORTS.keySet())));
		}

		Window window = Window.resolve(from, to);
		SearchQuery search = buildLeaderboardQuery(project, environment, release, query, sort, window.from(),
				window.to());

		List<TransactionGroup> groups = jdbc.sql(search.sql()).params(search.params()).query((rs, i) -> {
			// percentile_cont(ARRAY[…]) returns one array per group, in the order the
			// probes were given: p50, p95, p99.
			Array percentiles = rs.getArray("percentiles");
			Double[] p = (Double[]) percentiles.getArray();
			return new TransactionGroup(rs.getLong("project_id"), rs.getString("name"), rs.getString("op"),
					rs.getLong("txn_count"), rs.getDouble("total_ms"), rs.getDouble("avg_ms"), rs.getDouble("max_ms"),
					p[0], p[1], p[2]);
		}).list();

		// The query asks for one more group than it will return, so whether the list was
		// cut is answered by the rows already in hand rather than by counting them again.
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
	 * One Transaction Group's statistics over the resolved window, for the detail view
	 * a leaderboard row opens into.
	 *
	 * <p>The identity travels in query params rather than in the path because
	 * transaction names contain slashes — {@code GET /api/checkout/{id}} is a name, not
	 * a path — and percent-encoding a name into a segment would put the same string in
	 * two shapes on the wire for no gain. The path is the collection's, with
	 * {@code /detail} under it, as the API contract in #158 states it.
	 *
	 * <p><b>An absent {@code op} means {@code op} is null, not "any op".</b> (Project,
	 * name, op) is the whole key, so "any op" identifies a set of Transaction Groups
	 * rather than one, and averaging them is exactly what the key exists to prevent —
	 * the same route measured as a pageload and as a navigation are not one number.
	 *
	 * <p><b>{@link #MIN_SAMPLES} is not applied here.</b> The floor keeps a group too
	 * small to have a percentile out of a <em>ranking</em>, where it would take a slot
	 * from a real problem; this view ranks nothing, and the request names one group. The
	 * count is on screen beside the percentiles, which is what says how much they are
	 * worth.
	 *
	 * <p>Two statements, not one. The summary and the {@link Trend} read the same rows
	 * under the same predicates, so a single {@code GROUP BY bucket} could carry both if
	 * the bucketed rows were rolled up in Java — but percentiles do not roll up, which is
	 * the whole reason ADR-0015 forbids pre-aggregating them: a p95 of six-hour p95s is
	 * not the window's p95. The second pass is what buys a header that agrees with
	 * itself.
	 *
	 * @return 404 when no Transaction in the window matches the key, which is what a
	 * shared link to a group that has since gone quiet looks like
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

		// Blank is null: a query param the client left empty and one it omitted mean the
		// same group, and no SDK sends an op of "".
		String resolvedOp = op != null && !op.isBlank() ? op : null;
		Window window = Window.resolve(from, to);
		SearchQuery search = buildDetailQuery(project, name, resolvedOp, environment, release, window.from(),
				window.to());

		// An aggregate with no GROUP BY returns exactly one row whatever the window holds,
		// so "no such group here" is a count of zero rather than an empty result set — and
		// every other statistic is null in that row, which is why they are read after it.
		Optional<TransactionGroup> group = jdbc.sql(search.sql()).params(search.params()).query((rs, i) -> {
			long count = rs.getLong("txn_count");
			if (count == 0) {
				return null;
			}
			// percentile_cont(ARRAY[…]) returns one array, in the order the probes were
			// given: p50, p95, p99.
			Double[] p = (Double[]) rs.getArray("percentiles").getArray();
			return new TransactionGroup(project, name, resolvedOp, count, rs.getDouble("total_ms"),
					rs.getDouble("avg_ms"), rs.getDouble("max_ms"), p[0], p[1], p[2]);
		}).optional();

		// The trend is only issued once the group is known to exist: a 404 has no chart to
		// draw, and running the second aggregate to produce an empty list is a full pass
		// over the window for a response that will not show it.
		return group
			.<ResponseEntity<?>>map(found -> ResponseEntity.ok(new GroupDetail(window.from(), window.to(),
					window.clamped(), found, trend(project, name, resolvedOp, environment, release, window))))
			.orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new GroupDetailNotFound(window.from(), window.to(), window.clamped(),
						"no Transactions in this Transaction Group for the current filters")));
	}

	/**
	 * The bucketed series behind the chart, on the grid {@link Trend} describes.
	 *
	 * <p>The width comes from {@link TimeBuckets} rather than from a ladder of this
	 * endpoint's own — the same rungs the Log Timeline draws at, so two charts of the
	 * same 30-day range agree about how wide a bucket is. Over the 30-day cap ADR-0015
	 * puts on this window that is 6 hours, ~120 points.
	 */
	private Trend trend(long project, String name, String op, List<String> environment, String release,
			Window window) {
		Duration bucket = TimeBuckets.width(window.from(), window.to());
		SearchQuery search = buildTrendQuery(project, name, op, environment, release, window.from(), window.to());

		List<TrendPoint> points = jdbc.sql(search.sql()).params(search.params()).query((rs, i) -> {
			// percentile_cont(ARRAY[…]) returns one array per bucket, in probe order: p50,
			// p95. No p99 — see TrendPoint for why the chart stops at two series.
			Double[] p = (Double[]) rs.getArray("percentiles").getArray();
			return new TrendPoint(rs.getTimestamp("bucket").toInstant(), rs.getLong("txn_count"), p[0], p[1]);
		}).list();

		return new Trend(TimeBuckets.alignDown(window.from(), bucket), bucket.toSeconds(), points);
	}

	/**
	 * The window a request is answered over, and whether the server had to narrow it
	 * to get there. Public so a guard can read the window the endpoint would bind
	 * rather than recompute one the endpoint never runs.
	 */
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
	 * The leaderboard query the controller runs, extracted per {@link SearchQuery}
	 * so the guard {@code EXPLAIN}s this statement rather than a copy of it.
	 *
	 * <p><b>One ordered-set aggregate, not three.</b>
	 * {@code percentile_cont(ARRAY[…])} sorts each group's durations once and reads
	 * all three probes off that single sort; three separate {@code percentile_cont}
	 * calls would sort the same rows three times for the same answer.
	 *
	 * <p><b>{@code min} is deliberately absent.</b> The fastest Transaction in a
	 * group is a cache hit or a 304, and reporting it invites reading the spread
	 * between min and max as the distribution — which is what the percentiles are
	 * for.
	 *
	 * <p><b>The {@code HAVING} runs before the {@code ORDER BY}, so the floor applies
	 * to every ranking this list can be sorted by</b> — including the percentile
	 * sorts, where a one-sample group is at its most misleading. Filtering after the
	 * limit instead would let those groups take slots from real ones and hand back a
	 * short page.
	 *
	 * <p>Ties break on the whole grouping key — (project_id, name, op) — so that
	 * groups accounting for identical total time do not swap places between two
	 * identical requests. With a {@code LIMIT} on top, an unstable order is a list
	 * whose <em>membership</em> changes on refresh, and the default view spans every
	 * Project, so leaving {@code project_id} out would let two Projects' identically
	 * named groups trade the last row.
	 *
	 * <p><b>The aggregate is wrapped in a subquery so the ranking can name a
	 * percentile.</b> {@code ORDER BY} at the same query level resolves an output
	 * alias only when it stands alone, so {@code percentiles[2]} beside the aggregate
	 * would be read as an input column and fail — and repeating the ordered-set
	 * aggregate in the {@code ORDER BY} instead would sort every group's durations a
	 * second time for a number the first sort already produced. The wrapper costs a
	 * subquery-scan node and nothing else: the grouping below it is unchanged, and the
	 * ordering above it is a top-N over at most {@link #MAX_GROUPS} + 1 rows.
	 *
	 * @param sort a key of {@link #SORTS}; anything else throws rather than reaching
	 * the statement, so the ordering can never be interpolated from a request
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

		// One more than MAX_GROUPS: the extra row is never returned, it only says whether
		// there was anything past the limit. The LIMIT with no cursor beside it is
		// deliberate, not an unfinished pagination — see MAX_GROUPS for why an aggregate
		// has no key to seek on.
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
	 * One Transaction Group's statistics, extracted per {@link SearchQuery} so the guard
	 * {@code EXPLAIN}s this statement rather than a copy of it.
	 *
	 * <p><b>No {@code GROUP BY}.</b> The key is fully bound — one Project, one name, one
	 * op — so grouping would produce the single row the aggregate already produces, and
	 * only that way round does an empty window come back as a count of zero rather than
	 * as no row at all. That distinction is what lets the endpoint tell "this group has
	 * gone quiet" apart from an error.
	 *
	 * <p>The key predicates come from {@link #appendKey}, which records why the op one
	 * is two branches rather than one expression.
	 *
	 * <p>The filters are the leaderboard's, through the same helper: a detail view
	 * narrowed differently from the list it was opened from would report statistics the
	 * user cannot reconcile with the row they clicked. {@code query} is the exception it
	 * does not take — a substring search over names is how the leaderboard is narrowed to
	 * find this group, and once found the group is identified by its exact name.
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
	 * The same statistics bucketed across the window, for the chart that turns a p95
	 * into "the p95 changed on Tuesday" — extracted per {@link SearchQuery} so the
	 * guard {@code EXPLAIN}s this statement rather than a copy of it.
	 *
	 * <p><b>Two probes, not the header's three.</b> The array is the only reason this
	 * is one ordered-set aggregate rather than two, and every probe added to it is a
	 * series drawn on the chart — p99 is left out because at a fiftieth of the
	 * header's sample count it is noise wearing a percentile's name.
	 *
	 * <p><b>The predicates are the detail view's, unchanged.</b> The bucketed rows and
	 * the summary above them are read together on one screen, so they are read from
	 * the same Transactions: the {@code date_bin} in the select list is the only
	 * difference between the two statements, and the window is deliberately
	 * <em>not</em> widened onto the bucket grid the way {@code LogController}'s
	 * timeline widens its own (see {@link Trend}).
	 *
	 * <p>Ordered, where the timeline's equivalent is not. The timeline's client places
	 * sparse bars by index arithmetic and reads nothing from their order; a line has to
	 * be drawn between points in time order, and sorting at most ~150 already-grouped
	 * rows in Postgres is cheaper than shipping the ordering rule to the browser as
	 * something else that can drift.
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
	 * The two predicates that pin one Transaction Group, shared by the summary and the
	 * trend so they can never name different groups.
	 *
	 * <p><b>The op predicate is built in Java rather than written as {@code IS NOT
	 * DISTINCT FROM}.</b> That operator would express "equal, or both null" in one
	 * expression and one parameter, and Postgres cannot use an index for it — the scan
	 * would fall back to reading every op under this (Project, name) and filtering.
	 * Both {@code = ?} and {@code IS NULL} are index conditions on the third column of
	 * {@code idx_txn_performance}, whose first two these statements have already bound.
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
	 * How many distinct Transaction Groups the window holds, which is the honest
	 * disclosure ADR-0014 requires in place of normalizing names: a Project emitting
	 * {@code GET /api/orders/12345} as its own group learns that it has four hundred
	 * thousand of them, and fixes its routing integration.
	 *
	 * <p><b>A second pass over {@code txn}, deliberately.</b> The leaderboard's own
	 * statement could carry this as {@code count(*) OVER ()}, but only over the groups
	 * that survive {@link #MIN_SAMPLES} — and the number is worthless after the floor,
	 * for the reason {@link Leaderboard#distinctGroups()} records. Reaching it inside
	 * one statement means a CTE referenced twice, which spools every group into a
	 * tuplestore that spills for the same reason the sort {@code V15} removed did.
	 * Two plain statements each stay index-only against {@code idx_txn_performance};
	 * this one is the cheaper of them, because it reads no {@code duration_ms} and
	 * builds no per-group sort.
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
	 * The predicates both statements share. They have to stay identical: a
	 * {@code distinct_groups} counted over a different window than the list it
	 * annotates is a warning about data the user is not looking at.
	 *
	 * <p>The window is closed on the left and open on the right, matching the
	 * convention {@code LogController} settled on: a Transaction landing exactly on a
	 * boundary must belong to one side of it, not both.
	 *
	 * <p>{@code release} matches exactly and {@code query} matches a case-insensitive
	 * substring of the name, as they do on the Traces page — the same two filters over
	 * the same table should not mean two different things depending on which page
	 * asked. Both narrow the Transactions that are aggregated; neither joins the
	 * grouping key, so a Transaction Group spanning three Releases stays one group and
	 * a Release filter reports what that group cost on that version.
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

	/** The window the endpoint would answer this request over, for the guards. */
	static Window window(Instant from, Instant to) {
		return Window.resolve(from, to);
	}

	/**
	 * Every sort the endpoint accepts, for the guards — read from the whitelist rather
	 * than restated, so a sort added here is a sort the plan guards cover on the same
	 * commit rather than one nobody explained.
	 */
	static List<String> sortKeys() {
		return List.copyOf(SORTS.keySet());
	}

	/**
	 * The two numbers that decide what a leaderboard response is <em>not</em> showing,
	 * read from the constants that enforce them rather than restated. The MCP Surface's
	 * {@code performance_overview} Tool discloses both in its {@code caveats}, and a
	 * disclosure carrying its own copy of a limit is one that goes quietly wrong the
	 * day the limit changes.
	 */
	static int maxGroups() {
		return MAX_GROUPS;
	}

	static int minSamples() {
		return MIN_SAMPLES;
	}

}
