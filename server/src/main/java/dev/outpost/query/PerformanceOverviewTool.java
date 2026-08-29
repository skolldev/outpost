package dev.outpost.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.Array;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The MCP Surface's {@code performance_overview} Tool: the Transaction Group
 * leaderboard with the duration statistics the Performance view ranks by.
 *
 * <p>Both statements are {@link TransactionGroupController}'s own — the ranking
 * and the cardinality count beside it — so the 30-day window cap and
 * {@code TransactionGroupPerformanceTest}'s guards come with them (ADR-0016).
 * The window is resolved through the controller's own {@code Window} for the same
 * reason: a clamp computed here would be a second definition of the cap that
 * could drift from the one the statement is actually bound by.
 *
 * <p><b>This is the Tool whose caveats do the most work, and they are the reason
 * it is worth shipping at all.</b> The Performance view carries them as a banner
 * next to the numbers; a Tool result carries no banner, and every one of them is
 * a fact about what the numbers mean rather than decoration:
 *
 * <ul>
 * <li><b>Sampling.</b> Sentry SDKs sample traces and Outpost stores no sample
 * rate, so these are the Transactions received, not the requests served. Uniform
 * sampling scales every group equally and leaves a ranking intact; per-transaction
 * sampling does not, and nothing in the data says which case an installation is
 * in. The field name carries half of this — {@code transactions_received}, never
 * {@code count} — and the caveat carries the half a name cannot.
 * <li><b>Cardinality.</b> A Transaction Group's name is stored exactly as the SDK
 * sent it and is never normalized (ADR-0014), so a Project that does not
 * parameterize its URLs gets one group per URL.
 * {@code distinct_transaction_groups} is the honest disclosure, counted before
 * the sample floor and before the limit.
 * <li><b>The sample floor.</b> Groups below it are excluded from the ranking
 * because a percentile over one sample is that one duration wearing three labels,
 * but they are still counted in the cardinality — so the two numbers do not add
 * up, and the caveat says why rather than leaving it to look like a bug.
 * </ul>
 *
 * <p>Nothing derived from {@code txn.status} appears here, for the reason it does
 * not appear on the Performance view: "is it broken" is answered by Issues, from
 * error Events, and a second weaker answer would disagree with it. An agent asking
 * that question should call {@code find_issues}.
 */
@Component
public class PerformanceOverviewTool {

	public record PerformanceOverviewResult(String from, String to, boolean range_clamped, String sorted_by,
			long distinct_transaction_groups, boolean more_groups_matched,
			List<TransactionGroupPayload> transaction_groups, List<String> caveats) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TransactionGroupPayload(@Nullable String project_slug, String name, @Nullable String op,
			long transactions_received, double total_ms, double avg_ms, double max_ms, double p50_ms, double p95_ms,
			double p99_ms) {
	}

	/**
	 * The rankings this Tool offers, each mapped to the key
	 * {@link TransactionGroupController#buildLeaderboardQuery} whitelists.
	 *
	 * <p>The names on the left are the payload's own: a caller ranking by a number it
	 * can read off a row should not have to learn a second name for that number, and
	 * {@code p95} without its unit is precisely the shape ADR-0014 rules out. The map
	 * is also the whitelist — an unrecognised key is rejected here and never reaches
	 * the statement.
	 */
	private static final Map<String, String> SORTS = sorts();

	private static Map<String, String> sorts() {
		Map<String, String> sorts = new LinkedHashMap<>();
		sorts.put("total_ms", "total_ms");
		sorts.put("p95_ms", "p95");
		sorts.put("p50_ms", "p50");
		sorts.put("transactions_received", "count");
		return Map.copyOf(sorts);
	}

	/** "Where fixing something pays off most", which is what the Performance view defaults to. */
	private static final String DEFAULT_SORT = "total_ms";

	/**
	 * Transaction Groups returned when the caller names no limit. Far below the
	 * hundred the Performance view renders: a screen scrolls and a context window
	 * does not, and the statement costs the same either way — the limit here trims
	 * the payload, not the work.
	 */
	static final int DEFAULT_LIMIT = 20;

	private final JdbcTemplate jdbc;

	private final ToolSupport support;

	public PerformanceOverviewTool(ToolSupport support) {
		this.jdbc = support.jdbc();
		this.support = support;
	}

	@McpTool(name = "performance_overview", title = "Transaction Group performance", generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(title = "Transaction Group performance", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = """
					Transaction Groups ranked by duration, worst first. A Transaction Group is the recurring \
					activity that Transactions sharing a Project, name and op are instances of — typically a route \
					or a job. Answers "what is slow"; it does not answer "what is broken", which is find_issues. To \
					see the Transactions behind one group — including Trace IDs for get_trace — call \
					find_transactions with the group's name and op. You must read the `caveats` array before drawing \
					a conclusion from these numbers: they describe the Transactions Outpost received, which is a \
					sample whenever a Project's SDK samples traces.""")
	public PerformanceOverviewResult performanceOverview(
			@McpToolParam(required = false,
					description = "Project slugs from list_projects. Omit for every Project.") List<String> project_slugs,
			@McpToolParam(required = false, description = "Environment Names from list_projects. Matched exactly. "
					+ "Omit for every Environment.") List<String> environments,
			@McpToolParam(required = false, description = "Exact release version, e.g. shop@1.4.2 — list_projects "
					+ "shows each Project's recent versions. Narrows which Transactions are measured; it does not "
					+ "split the groups.") String release,
			@McpToolParam(required = false,
					description = "Case-insensitive substring of the Transaction Group name.") String query,
			@McpToolParam(required = false, description = "total_ms (the default), p95_ms, p50_ms or "
					+ "transactions_received. All rank worst first.") String sort,
			@McpToolParam(required = false, description = "Start of the window: an ISO-8601 instant, or an ISO-8601 "
					+ "duration such as PT1H or P2D meaning that far back from `to`. Defaults to "
					+ ToolSupport.DEFAULT_WINDOW_DAYS + " days before `to`; anything earlier than 30 days before "
					+ "`to` is clamped.") String from,
			@McpToolParam(required = false,
					description = "End of the window as an ISO-8601 instant. Defaults to now.") String to,
			@McpToolParam(required = false, description = "Transaction Groups to return. Defaults to " + DEFAULT_LIMIT
					+ ".") Integer limit) {

		List<String> caveats = new ArrayList<>();
		ToolSupport.Projects projects = support.projects();
		List<Long> projectIds = projects.resolve(project_slugs);
		// Refused rather than bound, like an unknown slug: an exact-match filter for a
		// value nothing carries returns an empty result that reads as "nothing matched".
		support.requireKnownEnvironments(environments);
		support.requireKnownRelease(release);
		ToolSupport.Window requested = ToolSupport.window(from, to, caveats);
		String sortedBy = sort(sort);
		int size = limit(limit);

		// The controller's own resolution, so the 30-day cap this Tool discloses is the
		// one the statement below is actually bound by.
		TransactionGroupController.Window window = TransactionGroupController.window(requested.fromInstant(),
				requested.toInstant());
		if (window.clamped()) {
			caveats.add("The window was clamped to the 30 days before " + window.to()
					+ ", which is the widest span these statistics are computed over. Percentiles are computed "
					+ "live from every Transaction in the window and are never pre-aggregated, so the cap is "
					+ "what keeps that affordable.");
		}

		SearchQuery search = buildPerformanceOverviewQuery(projectIds, environments, release, query,
				SORTS.get(sortedBy), window.from(), window.to());
		List<TransactionGroupPayload> groups = jdbc.query(search.sql(), (rs, row) -> {
			// percentile_cont(ARRAY[…]) returns one array per group, in probe order:
			// p50, p95, p99.
			Array percentiles = rs.getArray("percentiles");
			Double[] p = (Double[]) percentiles.getArray();
			return new TransactionGroupPayload(projects.slug(rs.getLong("project_id")), rs.getString("name"),
					rs.getString("op"), rs.getLong("txn_count"), rs.getDouble("total_ms"), rs.getDouble("avg_ms"),
					rs.getDouble("max_ms"), p[0], p[1], p[2]);
		}, search.params().toArray());

		// The statement asks for one more group than the view returns, so "was there
		// anything past the limit" is answered by the rows in hand. Both cuts are
		// reported as one flag: from the caller's side there is no difference between
		// the statement's limit and this Tool's.
		boolean more = groups.size() > TransactionGroupController.maxGroups() || groups.size() > size;
		groups = groups.subList(0, Math.min(groups.size(), size));

		SearchQuery cardinality = buildPerformanceCardinalityQuery(projectIds, environments, release, query,
				window.from(), window.to());
		long distinct = jdbc.queryForObject(cardinality.sql(), Long.class, cardinality.params().toArray());

		if (more) {
			caveats.add("More Transaction Groups matched than the " + size + " returned. This is the worst "
					+ size + " by " + sortedBy + ", not the whole list; narrow with project_slugs, "
					+ "environments or query rather than reading it as complete.");
		}
		caveats.add("transactions_received counts the Transactions Outpost received, not the requests served. "
				+ "Sentry SDKs sample traces and Outpost stores no sample rate, so these totals are a sample "
				+ "of real activity. Uniform sampling scales every group equally and leaves this ranking "
				+ "intact; per-transaction sampling does not, and nothing in the data reveals which applies.");
		caveats.add("Transaction Group names are reported exactly as the SDK sent them and are never normalized. "
				+ "distinct_transaction_groups is " + distinct + " for this window; a number far larger than the "
				+ "groups returned usually means a Project is not parameterizing its URLs, so each one becomes "
				+ "its own group.");
		caveats.add("Transaction Groups holding fewer than " + TransactionGroupController.minSamples()
				+ " Transactions in the window are excluded from this ranking — a percentile over one sample is "
				+ "that sample wearing three labels — but they are still counted in "
				+ "distinct_transaction_groups, which is why the two numbers do not reconcile.");
		if (groups.isEmpty()) {
			caveats.add("No Transaction Group is in this result. Either no Transactions were received in the "
					+ "window, or every group in it holds fewer than " + TransactionGroupController.minSamples()
					+ " Transactions; distinct_transaction_groups tells the two apart.");
		}

		return new PerformanceOverviewResult(window.from().toString(), window.to().toString(), window.clamped(),
				sortedBy, distinct, more, groups, caveats);
	}

	/**
	 * The leaderboard as this Tool binds it: the controller's own factory, so the
	 * plan and its guards are the Performance view's own. Named here so
	 * {@code QueryPlans} can {@code EXPLAIN} the Tool's shape and
	 * {@code McpToolQueryReuseTest} can assert it is not a copy.
	 */
	static SearchQuery buildPerformanceOverviewQuery(List<Long> project, List<String> environment, String release,
			String query, String sort, Instant from, Instant to) {
		return TransactionGroupController.buildLeaderboardQuery(project, environment, release, query, sort, from, to);
	}

	/** The cardinality count that annotates it, likewise the controller's own. */
	static SearchQuery buildPerformanceCardinalityQuery(List<Long> project, List<String> environment, String release,
			String query, Instant from, Instant to) {
		return TransactionGroupController.buildDistinctGroupQuery(project, environment, release, query, from, to);
	}

	/** Every sort this Tool accepts, in payload names — read by the guards and the reuse test. */
	static List<String> sortKeys() {
		return List.copyOf(SORTS.keySet());
	}

	static String controllerSort(String sort) {
		return SORTS.get(sort);
	}

	private static String sort(String requested) {
		return ToolSupport.choose(requested, SORTS.keySet(), DEFAULT_SORT, "sort");
	}

	private static int limit(Integer requested) {
		if (requested == null) {
			return DEFAULT_LIMIT;
		}
		return Math.max(1, Math.min(requested, TransactionGroupController.maxGroups()));
	}

}
