package dev.outpost.query;

import java.sql.Array;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Performance leaderboard: {@code txn} aggregated into Transaction Groups —
 * the recurring activity that Transactions sharing a (Project, name, op) are
 * instances of — ranked by the total time each one accounts for, or by whichever
 * of {@link #SORTS} the request asks for.
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
				       count(*) AS txn_count,
				       sum(duration_ms) AS total_ms,
				       avg(duration_ms) AS avg_ms,
				       max(duration_ms) AS max_ms,
				       percentile_cont(ARRAY[0.5, 0.95, 0.99]::double precision[])
				           WITHIN GROUP (ORDER BY duration_ms) AS percentiles
				FROM txn WHERE 1=1
				""");
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

}
