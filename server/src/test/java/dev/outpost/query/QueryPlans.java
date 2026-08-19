package dev.outpost.query;

import dev.outpost.support.PlanFacts;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The one place outside {@code dev.outpost.query} that can reach the controllers'
 * query builders. {@link SearchQuery} and {@link KeysetPage} stay package-private
 * in production — widening them so a benchmark in another package could see them
 * would trade a real encapsulation boundary for test convenience — so this
 * test-side class does the widening instead, and only for the harness.
 *
 * <p>It also owns cursor <em>walking</em>. Nothing here synthesizes a cursor:
 * {@code KeysetPage.encode} is private, and reaching a deep page the way a user
 * does is both the honest measurement and the one that needs no production
 * change. At guard scale that is a handful of cheap round-trips.
 */
public final class QueryPlans {

	private QueryPlans() {
	}

	/** A built statement, in types every package can see. */
	public record Built(String sql, List<Object> params) {

		public PlanFacts explain(JdbcClient jdbc) {
			return PlanFacts.explain(jdbc, sql, params);
		}

		/**
		 * Every column, mapped generically — the caller wants the keyset columns for
		 * {@link KeysetPage#paginate}, and a per-endpoint mapper here would be a second
		 * copy of code the controllers already have.
		 */
		public List<Map<String, Object>> rows(JdbcClient jdbc) {
			return jdbc.sql(sql).params(params).query((rs, i) -> {
				ResultSetMetaData metadata = rs.getMetaData();
				Map<String, Object> row = new LinkedHashMap<>();
				for (int column = 1; column <= metadata.getColumnCount(); column++) {
					Object value = rs.getObject(column);
					row.put(metadata.getColumnLabel(column),
							value instanceof Timestamp timestamp ? timestamp.toInstant() : value);
				}
				return row;
			}).list();
		}

		static Built of(SearchQuery query) {
			return new Built(query.sql(), query.params());
		}
	}

	// ------------------------------------------------------------------ issues

	public static Built issueList(List<Long> project, List<String> environment, String status, String release,
			Instant from, Instant to, String query, String sort, String cursor) {
		return Built.of(IssueController.buildIssueQuery(project, environment, status, release, from, to, query, sort,
				cursor));
	}

	public static Built sparkline(List<Long> issueIds, Instant since) {
		return Built.of(IssueController.buildSparklineQuery(issueIds, since));
	}

	public static Built usersAffected(List<Long> issueIds) {
		return Built.of(IssueController.buildUsersAffectedQuery(issueIds));
	}

	public static Built environmentRollup(List<Long> issueIds) {
		return Built.of(IssueController.buildEnvironmentRollupQuery(issueIds));
	}

	/**
	 * The window the sparkline is bound by, from the controller that owns it. Read
	 * rather than recomputed: it is the bind parameter that decides which partitions
	 * the aggregate reads, so a caller with its own copy would {@code EXPLAIN} a
	 * plan the controller never runs.
	 */
	public static Instant sparklineSince() {
		return IssueController.sparklineSince();
	}

	/**
	 * The ids the aggregates would be handed for a page of {@code list} — the same
	 * query, not an unfiltered stand-in. A filtered list returns different issues,
	 * and its aggregates are only comparable to its own page.
	 */
	public static List<Long> issueIdsOnPage(JdbcClient jdbc, Built list) {
		return list.rows(jdbc).stream().map(row -> (Long) row.get("id")).limit(IssueController.pageSize()).toList();
	}

	/** Walks real cursors to page {@code pages}, returning the cursor that opens it. */
	public static String issueCursorAtPage(JdbcClient jdbc, String sort, int pages) {
		return walk(jdbc, IssueController.issuePage(sort),
				cursor -> issueList(null, null, null, null, null, null, null, sort, cursor), pages);
	}

	// -------------------------------------------------------------------- logs

	public static Built logs(List<Long> project, List<String> environment, List<String> level, String traceId,
			String release, String query, List<String> attr, Instant from, Instant to, String cursor) {
		return Built
			.of(LogController.buildLogQuery(project, environment, level, traceId, release, query, attr, from, to, cursor));
	}

	public static Built logTimeline(List<Long> project, List<String> environment, List<String> level, String traceId,
			String release, String query, List<String> attr, Instant from, Instant to) {
		return Built.of(LogController.buildTimelineQuery(project, environment, level, traceId, release, query, attr,
				from, to));
	}

	/**
	 * The bucket width the timeline would draw this window at, from the controller
	 * that owns the ladder. Read rather than recomputed, for the reason
	 * {@link #sparklineSince()} is: it is a bind parameter, and a guard with its own
	 * copy would {@code EXPLAIN} a grouping the controller never runs.
	 */
	public static Duration timelineBucket(Instant from, Instant to) {
		return LogController.timelineBucket(from, to);
	}

	public static String logCursorAtPage(JdbcClient jdbc, int pages) {
		return logCursorAtPage(jdbc, pages, cursor -> logs(null, null, null, null, null, null, null, null, null, cursor));
	}

	/**
	 * Walks to page {@code pages} of the log list <em>as {@code build} shapes it</em>.
	 * A deep page reached through an unfiltered walk is not the deep page a filtered
	 * request reaches: the cursor it ends on is a different row, over a different
	 * span of time, and explaining a filtered query at it measures a request nobody
	 * makes.
	 */
	public static String logCursorAtPage(JdbcClient jdbc, int pages, Function<String, Built> build) {
		return walk(jdbc, LogController.logPage(), build, pages);
	}

	// ------------------------------------------------------- transaction groups

	public static Built transactionGroups(List<Long> project, List<String> environment, Instant from, Instant to) {
		return Built.of(TransactionGroupController.buildLeaderboardQuery(project, environment, from, to));
	}

	/**
	 * The cardinality count the leaderboard endpoint issues alongside the list. It is
	 * a second pass over the same window, so the page's cost is the two together and
	 * guarding only the first would understate it by half.
	 */
	public static Built transactionGroupCardinality(List<Long> project, List<String> environment, Instant from,
			Instant to) {
		return Built.of(TransactionGroupController.buildDistinctGroupQuery(project, environment, from, to));
	}

	/**
	 * The window the leaderboard would answer this request over, from the controller
	 * that owns the 30-day cap. Read rather than recomputed, for the reason
	 * {@link #sparklineSince()} is: it decides the bind parameters that prune the
	 * partitions, so a guard computing its own would {@code EXPLAIN} a window the
	 * controller never runs.
	 */
	public static TransactionGroupController.Window transactionGroupWindow(Instant from, Instant to) {
		return TransactionGroupController.window(from, to);
	}

	// ---------------------------------------------------------- traces/releases

	public static Built traceSearch(List<Long> project, List<String> environment, String release, String query,
			Double minDuration, Double maxDuration, Boolean hasErrors, Instant from, Instant to, String cursor) {
		return Built.of(TraceController.buildSearchQuery(project, environment, release, query, minDuration, maxDuration,
				hasErrors, from, to, cursor));
	}

	public static Built releaseList(long project) {
		return Built.of(ReleaseController.buildReleaseListQuery(project));
	}

	/**
	 * The four statements {@code GET /traces/{id}} fans out into, as the controller
	 * has them. Returned as a list because the page's cost is their sum — reporting
	 * one of four would be a number nobody waits for.
	 */
	public static List<Built> traceDetail(String traceId) {
		return TraceController.traceDetailQueries()
			.stream()
			.map(sql -> new Built(sql, List.<Object>of(traceId)))
			.toList();
	}

	/**
	 * Event detail: the row itself plus the two neighbour lookups the endpoint
	 * always issues alongside it, which is where its cost actually is.
	 */
	public static List<Built> eventDetail(UUID id, long issueId, Instant timestamp) {
		List<Object> neighbour = List.of(issueId, Timestamp.from(timestamp), id);
		return List.of(new Built(IssueController.EVENT_BY_ID, List.of(id)),
				new Built(IssueController.NEWER_EVENT_IN_ISSUE, neighbour),
				new Built(IssueController.OLDER_EVENT_IN_ISSUE, neighbour));
	}

	// ----------------------------------------------------------------- walking

	/**
	 * Pages forward {@code pages - 1} times and returns the cursor for page
	 * {@code pages}. Running out of rows early throws rather than returning
	 * {@code null}: a "deep page" scenario that silently became page 3 would report
	 * a fast number for a query nobody asked about.
	 */
	private static String walk(JdbcClient jdbc, KeysetPage page, Function<String, Built> build, int pages) {
		String cursor = null;
		for (int i = 1; i < pages; i++) {
			KeysetPage.Page result = page.paginate(build.apply(cursor).rows(jdbc));
			if (result.nextCursor() == null) {
				throw new IllegalStateException(
						"ran out of rows walking to page " + pages + "; the dataset ended at page " + i);
			}
			cursor = result.nextCursor();
		}
		return cursor;
	}

}
