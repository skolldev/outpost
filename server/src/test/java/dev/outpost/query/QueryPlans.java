package dev.outpost.query;

import dev.outpost.support.PlanFacts;
import dev.outpost.uptime.UptimeStatusService;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
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
	 * The bucket width a window is binned at, from the shared ladder both the log
	 * timeline and the Transaction Group trend bind. Read rather than recomputed, for
	 * the reason {@link #sparklineSince()} is: it is a bind parameter, and a guard
	 * with its own copy would {@code EXPLAIN} a grouping no endpoint runs.
	 */
	public static Duration timelineBucket(Instant from, Instant to) {
		return TimeBuckets.width(from, to);
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

	public static Built transactionGroups(List<Long> project, List<String> environment, String release, String query,
			String sort, Instant from, Instant to) {
		return Built
			.of(TransactionGroupController.buildLeaderboardQuery(project, environment, release, query, sort, from, to));
	}

	/** The sorts the leaderboard whitelists, so a guard covers every ranking the UI can ask for. */
	public static List<String> transactionGroupSorts() {
		return TransactionGroupController.sortKeys();
	}

	/**
	 * The cardinality count the leaderboard endpoint issues alongside the list. It is
	 * a second pass over the same window, so the page's cost is the two together and
	 * guarding only the first would understate it by half.
	 */
	public static Built transactionGroupCardinality(List<Long> project, List<String> environment, String release,
			String query, Instant from, Instant to) {
		return Built
			.of(TransactionGroupController.buildDistinctGroupQuery(project, environment, release, query, from, to));
	}

	/**
	 * The statistics behind one Transaction Group's detail view (#162). It reads the
	 * same window as the leaderboard it is opened from, so it is guarded alongside it —
	 * a detail view is one row of that list, and nothing about the aggregate underneath
	 * is cheaper for having its key bound unless the index says so.
	 */
	public static Built transactionGroupDetail(long project, String name, String op, List<String> environment,
			String release, Instant from, Instant to) {
		return Built.of(TransactionGroupController.buildDetailQuery(project, name, op, environment, release, from, to));
	}

	/**
	 * The bucketed series the detail view returns alongside those statistics (#163).
	 * A second aggregate over the same rows under the same predicates, so the page's
	 * cost is the two together — and it is the one of the pair that groups, which is
	 * where a spill would come from.
	 */
	public static Built transactionGroupTrend(long project, String name, String op, List<String> environment,
			String release, Instant from, Instant to) {
		return Built.of(TransactionGroupController.buildTrendQuery(project, name, op, environment, release, from, to));
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

	// --------------------------------------------------------------- mcp tools

	/**
	 * The Issue + Project + latest-Event join behind {@code get_issue_context}. The
	 * MCP Surface's Tools reuse the controllers' factories wherever the question is
	 * one the UI already asks (ADR-0016), which leaves exactly the statements below
	 * needing guards of their own — the reuse rule is not an exemption from guarding.
	 */
	public static Built issueContext(long issueId) {
		return Built.of(IssueContextTool.buildIssueContextQuery(issueId));
	}

	/**
	 * The Trace summary the same Tool returns. Separate from {@link #traceDetail}
	 * rather than derived from it: the detail endpoint reads every row of four
	 * tables, and reducing a Trace to a root and three counts is a different
	 * question with a different plan.
	 */
	public static Built traceSummary(String traceId) {
		return Built.of(IssueContextTool.buildTraceSummaryQuery(traceId));
	}

	/**
	 * The surrounding Log Records, as the Tool binds them — the log list's own
	 * factory with a Project and a window and no cursor. Guarded here as well as in
	 * {@code LogQueryPerformanceTest} because the Tool's window is minutes wide
	 * rather than the UI's fourteen days, and that is a different shape arriving at
	 * the same index.
	 */
	public static Built surroundingLogs(long projectId, Instant from, Instant to) {
		return Built.of(IssueContextTool.buildSurroundingLogQuery(projectId, from, to));
	}

	/**
	 * The two widths the Tool can read Log Records over, from the Tool that owns
	 * them: the default it applies when an agent names none, and the maximum it
	 * clamps to. Read rather than recomputed, for the reason {@link #sparklineSince()}
	 * is — they are the bind parameters that decide which partitions are pruned.
	 */
	public static List<Duration> surroundingLogWindows() {
		return List.of(Duration.ofMinutes(IssueContextTool.DEFAULT_LOG_WINDOW_MINUTES),
				Duration.ofMinutes(IssueContextTool.MAX_LOG_WINDOW_MINUTES));
	}

	/**
	 * The window every list Tool applies when the caller supplies no {@code from},
	 * from the class that owns it. Read rather than restated, for the reason
	 * {@link #sparklineSince()} is read: it is the bind parameter that decides which
	 * partitions are pruned, and it is the whole subject of ADR-0016's warning that
	 * an agent omits what a user never picks. A guard with its own copy would
	 * {@code EXPLAIN} a window no Tool binds and would keep passing after the default
	 * widened.
	 */
	public static Duration toolWindow() {
		return Duration.ofDays(ToolSupport.DEFAULT_WINDOW_DAYS);
	}

	/** The Issue list as {@code find_issues} binds it, through the Tool's own factory. */
	public static Built findIssues(List<Long> project, List<String> environment, String status, String release,
			Instant from, Instant to, String query, String sort, String cursor) {
		return Built.of(IssueSearchTool.buildIssueSearchQuery(project, environment, status, release, from, to, query,
				sort, cursor));
	}

	/**
	 * Every ranking {@code find_issues} accepts, paired with the controller key it
	 * resolves to — read from the Tool's own whitelist, so a ranking added there is
	 * one the guards cover on the same commit. The Tool renames them:
	 * {@code events_received} rather than the controller's {@code count}, because
	 * ADR-0014 will not have a caller learn a second name for a number it can read
	 * off a row.
	 */
	public static List<String> findIssuesSorts() {
		return IssueSearchTool.sortKeys().stream().map(IssueSearchTool::controllerSort).toList();
	}

	/** The status {@code find_issues} applies when the caller names none. */
	public static String toolIssueStatus() {
		return IssueSearchTool.DEFAULT_STATUS;
	}

	/** Both statuses the Tool answers, so a guard covers the tab #126 originally missed. */
	public static List<String> toolIssueStatuses() {
		return IssueSearchTool.STATUSES;
	}

	/** The log stream as {@code search_logs} binds it, through the Tool's own factory. */
	public static Built searchLogs(List<Long> project, List<String> environment, List<String> level, String traceId,
			String release, String query, List<String> attr, Instant from, Instant to, String cursor) {
		return Built.of(LogSearchTool.buildLogSearchQuery(project, environment, level, traceId, release, query, attr,
				from, to, cursor));
	}

	/**
	 * The one statement {@code get_event_raw} issues. It is the event detail page's
	 * own row lookup <em>without</em> the two neighbour probes that page also runs —
	 * a different shape from {@link #eventDetail}, and the only one this Tool pays
	 * for.
	 */
	public static Built eventRaw(UUID id) {
		return new Built(IssueController.EVENT_BY_ID, List.of(id));
	}

	/** The leaderboard as {@code performance_overview} binds it, through the Tool's own factory. */
	public static Built performanceOverview(List<Long> project, List<String> environment, String release, String query,
			String sort, Instant from, Instant to) {
		return Built.of(PerformanceOverviewTool.buildPerformanceOverviewQuery(project, environment, release, query,
				sort, from, to));
	}

	/** The cardinality count it issues alongside, likewise through the Tool's own factory. */
	public static Built performanceCardinality(List<Long> project, List<String> environment, String release,
			String query, Instant from, Instant to) {
		return Built.of(PerformanceOverviewTool.buildPerformanceCardinalityQuery(project, environment, release, query,
				from, to));
	}

	/**
	 * Every ranking {@code performance_overview} accepts, paired with the controller
	 * key it resolves to — read from the Tool's own whitelist so a ranking added
	 * there is one the guards cover on the same commit.
	 */
	public static List<String> performanceSorts() {
		return PerformanceOverviewTool.sortKeys().stream().map(PerformanceOverviewTool::controllerSort).toList();
	}

	/** The window the leaderboard is bound by once the Tool's default has been through the 30-day cap. */
	public static TransactionGroupController.Window performanceWindow(Instant from, Instant to) {
		return TransactionGroupController.window(from, to);
	}

	/**
	 * The four statements {@code uptime_status} issues, as the Tool binds them —
	 * scoped to the Projects asked for and to the days of history asked for, which
	 * on this path are predicates rather than post-filters. Returned as a list
	 * because one Tool call is their sum.
	 *
	 * <p>{@code uptime_check} is a plain table rather than one of the partitioned
	 * telemetry tables, so {@link QueryGuard#assertCeilingCanFail} has no full-scan
	 * cost to validate a ceiling against — see the guard for what is asserted
	 * instead.
	 */
	public static List<Built> uptimeStatus(List<Long> project, int days) {
		return Stream
			.of(UptimeStatusService.buildMonitorQuery(project, null),
					UptimeStatusService.buildOpenIncidentQuery(project),
					UptimeStatusService.buildDailyRollupQuery(project, days),
					UptimeStatusService.buildLastCheckQuery(project))
			.map(query -> new Built(query.sql(), query.params()))
			.toList();
	}

	/** The history {@code uptime_status} reads when the caller names none, from the Tool that owns it. */
	public static int uptimeDefaultDays() {
		return UptimeStatusTool.DEFAULT_DAYS;
	}

	/** The widest history it will read, which is the status page's own fixed span. */
	public static int uptimeMaxDays() {
		return UptimeStatusService.WINDOW_DAYS;
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
