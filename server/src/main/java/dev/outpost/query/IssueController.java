package dev.outpost.query;

import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issue + event query API: filterable issue list with 14-day sparklines,
 * issue detail with per-environment stats, event navigation within an issue.
 * Cursor pagination is keyset-based on {@code (sort value, id)}.
 */
@RestController
@RequestMapping("/api/internal")
public class IssueController {

	/** The list page size, and therefore the ceiling on any per-call limit built over this keyset. */
	static final int PAGE_SIZE = 50;
	private static final int SPARKLINE_DAYS = 14;

	/** Issue list keyset: default by {@code (last_seen, id)}, or by {@code (event_count, id)} when sort=count. */
	private static final KeysetPage BY_LAST_SEEN = KeysetPage.of(KeysetPage.KeyColumn.instant("last_seen"),
			KeysetPage.KeyColumn.longs("id"), PAGE_SIZE);
	private static final KeysetPage BY_EVENT_COUNT = KeysetPage.of(KeysetPage.KeyColumn.longs("event_count"),
			KeysetPage.KeyColumn.longs("id"), PAGE_SIZE);

	/** Event-within-issue keyset by {@code (timestamp, id)}; the SQL column is quoted, the row key is not. */
	private static final KeysetPage EVENTS = KeysetPage.of(KeysetPage.KeyColumn.instant("\"timestamp\"", "timestamp"),
			KeysetPage.KeyColumn.uuid("id"), PAGE_SIZE);

	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;

	public IssueController(JdbcTemplate jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	// ------------------------------------------------------------------ issues

	@GetMapping("/issues")
	public Map<String, Object> issues(@RequestParam(required = false) List<Long> project,
			@RequestParam(required = false) List<String> environment,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String release,
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			@RequestParam(required = false) String query,
			@RequestParam(defaultValue = "last_seen") String sort,
			@RequestParam(required = false) String cursor) {

		SearchQuery search = buildIssueQuery(project, environment, status, release, from, to, query, sort, cursor);
		KeysetPage page = issuePage(sort);

		List<Map<String, Object>> rows = jdbc.query(search.sql(), this::mapIssue, search.params().toArray());
		KeysetPage.Page result = page.paginate(rows);
		attachAggregates(result.rows());

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("issues", result.rows());
		body.put("next_cursor", result.nextCursor());
		return body;
	}

	/**
	 * The keyset the list is paged by. Package-visible so a guard can walk real
	 * cursors to a deep page instead of synthesizing one — see {@link KeysetPage}.
	 */
	static KeysetPage issuePage(String sort) {
		return "count".equals(sort) ? BY_EVENT_COUNT : BY_LAST_SEEN;
	}

	static int pageSize() {
		return PAGE_SIZE;
	}

	/**
	 * The lower bound the sparkline is built from. Package-visible for the same
	 * reason {@link #pageSize()} is: it is the bind parameter that decides which
	 * partitions the aggregate reads, so a guard that computed its own would
	 * {@code EXPLAIN} a different plan than the controller runs — and would keep
	 * passing after this window changed.
	 */
	static Instant sparklineSince() {
		return sparklineSince(LocalDate.now(ZoneOffset.UTC));
	}

	private static Instant sparklineSince(LocalDate today) {
		return today.minusDays(SPARKLINE_DAYS - 1L).atStartOfDay().toInstant(ZoneOffset.UTC);
	}

	/** The issue-list query the controller runs, extracted per {@link SearchQuery}. */
	static SearchQuery buildIssueQuery(List<Long> project, List<String> environment, String status, String release,
			Instant from, Instant to, String query, String sort, String cursor) {

		StringBuilder sql = new StringBuilder("""
				SELECT id, project_id, fingerprint, title, culprit, level, status, first_seen, last_seen, event_count
				FROM issue WHERE 1=1
				""");
		List<Object> params = new ArrayList<>();

		QuerySupport.appendInClause(sql, "project_id", project, params);
		if (status != null && !status.isBlank()) {
			sql.append(" AND status = ?");
			params.add(status);
		}
		if (environment != null && !environment.isEmpty()) {
			sql.append(" AND EXISTS (SELECT 1 FROM issue_env_stats s WHERE s.issue_id = issue.id AND s.environment IN ("
					+ QuerySupport.placeholders(environment.size()) + "))");
			params.addAll(environment);
		}
		if (release != null && !release.isBlank()) {
			sql.append(" AND EXISTS (SELECT 1 FROM issue_release_stats s WHERE s.issue_id = issue.id AND s.release = ?)");
			params.add(release);
		}
		if (from != null) {
			sql.append(" AND last_seen >= ?");
			params.add(java.sql.Timestamp.from(from));
		}
		if (to != null) {
			sql.append(" AND first_seen <= ?");
			params.add(java.sql.Timestamp.from(to));
		}
		if (query != null && !query.isBlank()) {
			sql.append(" AND (title ILIKE ? OR culprit ILIKE ?)");
			params.add("%" + query + "%");
			params.add("%" + query + "%");
		}

		KeysetPage.Tail tail = issuePage(sort).build(cursor);
		sql.append(tail.sql());
		params.addAll(tail.params());
		return new SearchQuery(sql.toString(), params);
	}

	/**
	 * Daily event counts for the page's issues over the sparkline window. Split out
	 * from {@link #attachAggregates} so a guard can {@code EXPLAIN} it alone: it is
	 * one of the two per-page-load aggregates over {@code event}, and the two have
	 * very different plans — this one is time-bounded, {@link
	 * #buildUsersAffectedQuery} is not.
	 */
	static SearchQuery buildSparklineQuery(List<Long> issueIds, Instant since) {
		List<Object> params = new ArrayList<>(issueIds);
		params.add(java.sql.Timestamp.from(since));
		return new SearchQuery("""
				SELECT issue_id, date_trunc('day', "timestamp" AT TIME ZONE 'UTC')::date AS day, count(*) AS n
				FROM event WHERE issue_id IN (%s) AND "timestamp" >= ?
				GROUP BY issue_id, day
				""".formatted(QuerySupport.placeholders(issueIds.size())), params);
	}

	/**
	 * Distinct users per issue for the page. Deliberately EXPLAIN-able alone: it
	 * carries no time bound, so it reads every partition ever created for every
	 * issue on the page, on the most-visited screen in the product.
	 */
	static SearchQuery buildUsersAffectedQuery(List<Long> issueIds) {
		return new SearchQuery(
				"SELECT issue_id, count(DISTINCT user_ident) AS n FROM event WHERE issue_id IN (%s) GROUP BY issue_id"
					.formatted(QuerySupport.placeholders(issueIds.size())),
				new ArrayList<>(issueIds));
	}

	/**
	 * Per-environment event counts for the page's issues. Extracted alongside the
	 * other two so a guard or benchmark can {@code EXPLAIN} the whole page load: an
	 * issue-list request is four statements, and summing three of them would report
	 * a cost nobody waits for.
	 */
	static SearchQuery buildEnvironmentRollupQuery(List<Long> issueIds) {
		return new SearchQuery(
				"SELECT issue_id, environment FROM issue_env_stats WHERE issue_id IN (%s) ORDER BY environment"
					.formatted(QuerySupport.placeholders(issueIds.size())),
				new ArrayList<>(issueIds));
	}

	/** Sparkline (daily counts, last 14 days) + users affected + environments, per page of issues. */
	private void attachAggregates(List<Map<String, Object>> rows) {
		if (rows.isEmpty()) {
			return;
		}
		List<Long> ids = rows.stream().map(r -> (Long) r.get("id")).toList();

		Map<Long, long[]> sparklines = new HashMap<>();
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		SearchQuery sparkline = buildSparklineQuery(ids, sparklineSince(today));
		jdbc.query(sparkline.sql(), rs -> {
			long[] days = sparklines.computeIfAbsent(rs.getLong("issue_id"), k -> new long[SPARKLINE_DAYS]);
			int offset = (int) (today.toEpochDay() - rs.getDate("day").toLocalDate().toEpochDay());
			if (offset >= 0 && offset < SPARKLINE_DAYS) {
				days[SPARKLINE_DAYS - 1 - offset] += rs.getLong("n");
			}
		}, sparkline.params().toArray());

		Map<Long, Long> usersAffected = new HashMap<>();
		SearchQuery users = buildUsersAffectedQuery(ids);
		jdbc.query(users.sql(), rs -> {
			usersAffected.put(rs.getLong("issue_id"), rs.getLong("n"));
		}, users.params().toArray());

		Map<Long, List<String>> environments = new HashMap<>();
		SearchQuery rollup = buildEnvironmentRollupQuery(ids);
		jdbc.query(rollup.sql(), rs -> {
			environments.computeIfAbsent(rs.getLong("issue_id"), k -> new ArrayList<>()).add(rs.getString("environment"));
		}, rollup.params().toArray());

		for (Map<String, Object> row : rows) {
			Long id = (Long) row.get("id");
			row.put("sparkline", sparklines.getOrDefault(id, new long[SPARKLINE_DAYS]));
			row.put("users_affected", usersAffected.getOrDefault(id, 0L));
			row.put("environments", environments.getOrDefault(id, List.of()));
		}
	}

	@GetMapping("/issues/{id}")
	public ResponseEntity<Map<String, Object>> issue(@PathVariable long id) {
		List<Map<String, Object>> rows = jdbc.query("""
				SELECT id, project_id, fingerprint, title, culprit, level, status, first_seen, last_seen, event_count
				FROM issue WHERE id = ?
				""", this::mapIssue, id);
		if (rows.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		Map<String, Object> issue = rows.get(0);
		issue.put("env_stats", jdbc.query("""
				SELECT environment, event_count, last_seen FROM issue_env_stats WHERE issue_id = ? ORDER BY environment
				""", (rs, i) -> Map.of("environment", rs.getString("environment"), "event_count",
				rs.getLong("event_count"), "last_seen", rs.getTimestamp("last_seen").toInstant()), id));
		return ResponseEntity.ok(issue);
	}

	@PatchMapping("/issues/{id}")
	public ResponseEntity<Map<String, Object>> updateIssue(@PathVariable long id,
			@RequestBody Map<String, String> body) {
		String status = body.get("status");
		if (!"resolved".equals(status) && !"unresolved".equals(status)) {
			return ResponseEntity.badRequest().body(Map.of("detail", "status must be resolved or unresolved"));
		}
		int updated = jdbc.update("UPDATE issue SET status = ? WHERE id = ?", status, id);
		return updated > 0 ? issue(id) : ResponseEntity.notFound().build();
	}

	// ------------------------------------------------------------------ events

	@GetMapping("/issues/{id}/events")
	public Map<String, Object> issueEvents(@PathVariable long id,
			@RequestParam(required = false) List<String> environment,
			@RequestParam(required = false) String cursor) {
		StringBuilder sql = new StringBuilder("""
				SELECT id, "timestamp", environment, release, level, message, user_ident, trace_id
				FROM event WHERE issue_id = ?
				""");
		List<Object> params = new ArrayList<>();
		params.add(id);
		if (environment != null && !environment.isEmpty()) {
			sql.append(" AND environment IN (").append(QuerySupport.placeholders(environment.size())).append(")");
			params.addAll(environment);
		}
		KeysetPage.Tail tail = EVENTS.build(cursor);
		sql.append(tail.sql());
		params.addAll(tail.params());

		List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, i) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", rs.getObject("id", UUID.class));
			row.put("timestamp", rs.getTimestamp("timestamp").toInstant());
			row.put("environment", rs.getString("environment"));
			row.put("release", rs.getString("release"));
			row.put("level", rs.getString("level"));
			row.put("message", rs.getString("message"));
			row.put("user_ident", rs.getString("user_ident"));
			row.put("trace_id", rs.getString("trace_id"));
			return row;
		}, params.toArray());

		KeysetPage.Page page = EVENTS.paginate(rows);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("events", page.rows());
		body.put("next_cursor", page.nextCursor());
		return body;
	}

	/**
	 * Event detail's three statements, named rather than inlined so a guard can
	 * {@code EXPLAIN} what the controller runs — see {@link SearchQuery}. The
	 * neighbour lookups are what make this page cost more than one row: each is a
	 * separate probe across every partition of {@code event}.
	 */
	static final String EVENT_BY_ID = """
			SELECT id, project_id, issue_id, environment, release, "timestamp", trace_id, level, message,
			       exception_type, user_ident, data, symbolication_status
			FROM event WHERE id = ?
			""";

	static final String NEWER_EVENT_IN_ISSUE = """
			SELECT id FROM event WHERE issue_id = ? AND ("timestamp", id) > (?, ?)
			ORDER BY "timestamp" ASC, id ASC LIMIT 1
			""";

	static final String OLDER_EVENT_IN_ISSUE = """
			SELECT id FROM event WHERE issue_id = ? AND ("timestamp", id) < (?, ?)
			ORDER BY "timestamp" DESC, id DESC LIMIT 1
			""";

	@GetMapping("/events/{id}")
	public ResponseEntity<Map<String, Object>> event(@PathVariable UUID id) {
		List<Map<String, Object>> rows = jdbc.query(EVENT_BY_ID, (rs, i) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", rs.getObject("id", UUID.class));
			row.put("project_id", rs.getLong("project_id"));
			row.put("issue_id", rs.getLong("issue_id"));
			row.put("environment", rs.getString("environment"));
			row.put("release", rs.getString("release"));
			row.put("timestamp", rs.getTimestamp("timestamp").toInstant());
			row.put("trace_id", rs.getString("trace_id"));
			row.put("level", rs.getString("level"));
			row.put("message", rs.getString("message"));
			row.put("exception_type", rs.getString("exception_type"));
			row.put("user_ident", rs.getString("user_ident"));
			row.put("data", QuerySupport.parseJson(mapper, rs.getString("data")));
			row.put("symbolication_status", rs.getString("symbolication_status"));
			return row;
		}, id);
		if (rows.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		Map<String, Object> event = rows.get(0);
		long issueId = (Long) event.get("issue_id");
		Instant ts = (Instant) event.get("timestamp");
		event.put("prev_event_id", neighbor(issueId, ts, (UUID) event.get("id"), false));
		event.put("next_event_id", neighbor(issueId, ts, (UUID) event.get("id"), true));
		return ResponseEntity.ok(event);
	}

	/** Adjacent event id within the same issue, by (timestamp, id) order. */
	private UUID neighbor(long issueId, Instant ts, UUID id, boolean newer) {
		String sql = newer ? NEWER_EVENT_IN_ISSUE : OLDER_EVENT_IN_ISSUE;
		List<UUID> found = jdbc.query(sql, (rs, i) -> rs.getObject("id", UUID.class), issueId,
				java.sql.Timestamp.from(ts), id);
		return found.isEmpty() ? null : found.get(0);
	}

	// ------------------------------------------------------------------ shared

	private Map<String, Object> mapIssue(ResultSet rs, int rowNum) throws SQLException {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", rs.getLong("id"));
		row.put("project_id", rs.getLong("project_id"));
		row.put("title", rs.getString("title"));
		row.put("culprit", rs.getString("culprit"));
		row.put("level", rs.getString("level"));
		row.put("status", rs.getString("status"));
		row.put("first_seen", rs.getTimestamp("first_seen").toInstant());
		row.put("last_seen", rs.getTimestamp("last_seen").toInstant());
		row.put("event_count", rs.getLong("event_count"));
		return row;
	}
}
