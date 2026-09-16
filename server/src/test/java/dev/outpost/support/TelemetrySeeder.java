package dev.outpost.support;

import dev.outpost.db.PartitionManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Builds a production-shaped telemetry dataset directly in Postgres for the
 * retrieval guards and benchmark. Seeds via bulk {@code INSERT ... SELECT}
 * rather than the ingest path, which tops out around 700 events/s and would
 * measure the write path instead of preparing the read one.
 */
public final class TelemetrySeeder {

	/**
	 * How much to seed. {@link #DEFAULT} is the benchmark's target; {@link #GUARD}
	 * is the CI guards', which is far smaller but still spans several weeks — a
	 * pruning assertion against a dataset that fits in one partition would pass for
	 * the wrong reason forever.
	 *
	 * @param windowDays how far back timestamps reach, i.e. how many weekly
	 * partitions each telemetry table ends up with
	 */
	public record Scale(int projects, int issues, long events, long logs, long txns, int spansPerTxn, int users,
			int releases, int windowDays) {

		public static final Scale DEFAULT = new Scale(4, 4_000, 2_000_000, 5_000_000, 1_000_000, 3, 10_000, 20, 60);

		public static final Scale GUARD = new Scale(2, 200, 40_000, 40_000, 8_000, 3, 500, 8, 42);

		/**
		 * Scales the row counts by {@code factor} and nothing else — cardinalities and
		 * the time window stay fixed, since those are what make the data production-shaped.
		 */
		public Scale times(double factor) {
			return new Scale(projects, issues, Math.max(1, (long) (events * factor)),
					Math.max(1, (long) (logs * factor)), Math.max(1, (long) (txns * factor)), spansPerTxn, users,
					releases, windowDays);
		}

		public long spans() {
			return txns * spansPerTxn;
		}
	}

	/**
	 * What was seeded, so scenarios do not have to re-query for their own inputs —
	 * a benchmark that spends a round trip finding an id before every timed request
	 * is measuring that round trip too.
	 */
	public record Seeded(List<Long> projectIds, int issues, long issueIdBase, long events, long logs, long txns,
			long spans, String traceId, long issueId, UUID eventId, Instant eventTimestamp, String release,
			String environment, String bodyNeedle, String attributeKey, String attributeValue, Instant windowStart,
			Map<String, String> settings) {

		public long projectId() {
			return projectIds.get(0);
		}
	}

	/** The environments every seeded signal is spread across. */
	public static final List<String> ENVIRONMENTS = List.of("production", "staging", "development");

	/**
	 * The attribute an equality filter searches on. High-cardinality on purpose: a
	 * filter matching half the table tells you nothing about whether the predicate
	 * can use an index, because no plan would bother.
	 */
	public static final String ATTRIBUTE_KEY = "order.id";

	/** Distinct values of {@link #ATTRIBUTE_KEY} — the denominator of its selectivity. */
	private static final int ATTRIBUTE_VALUES = 1_000;

	/**
	 * The substring a body search looks for. Deliberately selective (~1 in
	 * {@link #BODY_ORDERS} rows): a needle matching every body makes the planner
	 * choose a sequential scan on its own instead of exercising the trigram index.
	 */
	public static final String BODY_NEEDLE = "for order 137";

	/** Distinct order numbers in a log body — the denominator of {@link #BODY_NEEDLE}'s selectivity. */
	private static final int BODY_ORDERS = 1_000;

	/** The trace guaranteed to fan out across all four tables. */
	private static final String KNOWN_TRACE_ID = "0".repeat(24) + "cafebabe";

	/** The Transaction Group the known trace's Transactions belong to, for scenarios that drill into one group. */
	public static final String KNOWN_TRANSACTION_NAME = "GET /api/checkout/{id}";

	public static final String KNOWN_TRANSACTION_OP = "http.server";

	/** Rows per {@code INSERT … SELECT}: big enough to amortize, small enough to bound WAL and memory. */
	private static final long CHUNK = 250_000;

	private static final int UPTIME_MONITORS = 8;

	/** Sparser than any real monitor: the overview buckets by day, so per-minute rows only cost time. */
	private static final int UPTIME_CHECK_INTERVAL_MINUTES = 15;

	/**
	 * Postgres settings that decide whether a run is measuring cache or disk;
	 * recorded, never assumed. {@code random_page_cost} matters most (#185): it can
	 * decide which plan is chosen, not just how fast it runs.
	 */
	private static final List<String> REPORTED_SETTINGS = List.of("shared_buffers", "work_mem", "maintenance_work_mem",
			"effective_cache_size", "random_page_cost", "seq_page_cost", "synchronous_commit",
			"max_parallel_workers_per_gather", "server_version");

	private final JdbcClient jdbc;

	private final PartitionManager partitions;

	public TelemetrySeeder(JdbcClient jdbc, PartitionManager partitions) {
		this.jdbc = jdbc;
		this.partitions = partitions;
	}

	/** Wipes telemetry and seeds {@code scale} from scratch. */
	public Seeded seed(Scale scale) {
		clear();
		Instant now = Instant.now();
		Instant windowStart = now.minus(scale.windowDays(), ChronoUnit.DAYS);
		ensurePartitions(windowStart, now);

		List<Long> projectIds = seedProjects(scale);
		seedEnvironmentsAndReleases(scale, projectIds);
		long issueIdBase = seedIssues(scale, projectIds);

		long traces = Math.max(1, scale.txns() / 2);
		seedEvents(scale, projectIds, issueIdBase, traces);
		seedLogs(scale, projectIds, traces);
		seedTransactionsAndSpans(scale, projectIds, traces);
		seedKnownTrace(projectIds.get(0), issueIdBase);
		seedUptime(projectIds.get(0), scale);

		rollUpIssueCounters();
		analyze();

		// Paired with its timestamp: detail-endpoint neighbour lookups key on (issue_id, timestamp, id), not id alone.
		Map.Entry<UUID, Instant> event = jdbc.sql("SELECT id, \"timestamp\" FROM event WHERE issue_id = ? LIMIT 1")
			.param(issueIdBase)
			.query((rs, i) -> Map.entry(rs.getObject("id", UUID.class), rs.getTimestamp("timestamp").toInstant()))
			.single();
		return new Seeded(projectIds, scale.issues(), issueIdBase, count("event"), count("log_record"), count("txn"),
				count("span"), KNOWN_TRACE_ID, issueIdBase, event.getKey(), event.getValue(), release(0),
				ENVIRONMENTS.get(0), BODY_NEEDLE, ATTRIBUTE_KEY, "137", windowStart, settings());
	}

	/** The Postgres settings a report has to state, read back rather than assumed. */
	public Map<String, String> settings() {
		Map<String, String> settings = new LinkedHashMap<>();
		for (String name : REPORTED_SETTINGS) {
			settings.put(name, jdbc.sql("SHOW " + name).query(String.class).single());
		}
		return settings;
	}

	/**
	 * Empties every table this seeder writes and reclaims their pages. {@code
	 * TRUNCATE}, not {@code DELETE}: deleted rows leave their pages allocated until
	 * a {@code VACUUM}, so a sequential scan of an "emptied" table would still read
	 * every block it used to occupy.
	 */
	public void clear() {
		jdbc.sql("""
				TRUNCATE event, log_record, txn, span, issue_env_stats, issue_release_stats, issue,
				         uptime_check, uptime_incident, uptime_monitor,
				         release, environment, project_key, project CASCADE
				""").update();
	}

	/**
	 * One timestamp per week of the window, handed to the production partition
	 * manager. A week either side absorbs the drift between this JVM's clock and
	 * the database's, which decides whether the newest rows have a partition to
	 * land in.
	 */
	private void ensurePartitions(Instant from, Instant to) {
		List<Instant> weeks = new ArrayList<>();
		for (Instant week = from.minus(7, ChronoUnit.DAYS); !week.isAfter(to.plus(7, ChronoUnit.DAYS)); week = week
			.plus(7, ChronoUnit.DAYS)) {
			weeks.add(week);
		}
		for (String table : PartitionManager.TABLES) {
			partitions.ensurePartitions(table, weeks);
		}
	}

	private List<Long> seedProjects(Scale scale) {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < scale.projects(); i++) {
			long id = jdbc.sql("INSERT INTO project (slug, name, platform) VALUES (?, ?, 'java-spring-boot') RETURNING id")
				.param("seed-" + i)
				.param("Seed " + i)
				.query(Long.class)
				.single();
			jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, ?)")
				.param(id)
				.param(String.format("%032x", i + 1))
				.update();
			ids.add(id);
		}
		return ids;
	}

	private void seedEnvironmentsAndReleases(Scale scale, List<Long> projectIds) {
		for (long project : projectIds) {
			for (String environment : ENVIRONMENTS) {
				jdbc.sql("INSERT INTO environment (project_id, name) VALUES (?, ?)")
					.param(project)
					.param(environment)
					.update();
			}
			for (int i = 0; i < scale.releases(); i++) {
				jdbc.sql("INSERT INTO release (project_id, version, created_at) VALUES (?, ?, now() - make_interval(days => ?))")
					.param(project)
					.param(release(i))
					.param(scale.releases() - i)
					.update();
			}
		}
	}

	/**
	 * Issues are round-robined over the projects, so the id of the {@code k}-th
	 * issue is {@code base + k} and its project is {@code projectIds[k % n]}.
	 */
	private long seedIssues(Scale scale, List<Long> projectIds) {
		return jdbc.sql("""
				INSERT INTO issue (project_id, fingerprint, title, culprit, level, status, first_seen, last_seen)
				SELECT (%s)[1 + ((g - 1) %% %d)::int],
				       'fp-' || g,
				       'IllegalStateException: order ' || g || ' has no customer',
				       'dev.outpost.OrderService.loadCustomer' || g,
				       'error',
				       CASE WHEN g %% 20 = 0 THEN 'resolved' ELSE 'unresolved' END,
				       now() - make_interval(days => ?), now() - make_interval(days => ?)
				FROM generate_series(1, ?) g
				RETURNING id
				""".formatted(projectArray(projectIds), projectIds.size()))
			// Issues the skew leaves event-less keep these placeholders, sorting to the bottom instead of crowding page 1.
			.param(scale.windowDays())
			.param(scale.windowDays())
			.param(scale.issues())
			.query(Long.class)
			.list()
			.stream()
			.mapToLong(Long::longValue)
			.min()
			.orElseThrow();
	}

	private void seedEvents(Scale scale, List<Long> projectIds, long issueIdBase, long traces) {
		inChunks(scale.events(), (lo, hi) -> jdbc.sql("""
				INSERT INTO event (id, project_id, issue_id, environment, release, "timestamp", trace_id, level,
				                   message, exception_type, user_ident, data)
				SELECT gen_random_uuid(),
				       (%s)[1 + (r.k %% %d)::int],
				       ?::bigint + r.k,
				       (%s)[1 + floor(random() * %d)::int],
				       'app@1.0.' || floor(random() * ?)::int,
				       r.ts,
				       lpad(to_hex(floor(random() * ?)::bigint), 32, '0'),
				       'error',
				       'Unhandled exception in request ' || g,
				       'IllegalStateException',
				       'user-' || floor(random() * ?)::int,
				       ?::jsonb || jsonb_build_object('seq', g)
				FROM (%s) r
				""".formatted(projectArray(projectIds), projectIds.size(), environmentArray(), ENVIRONMENTS.size(),
				rowVariables("""
						floor(? * power(random(), 3))::bigint AS k,
						now() - (power(random(), 2) * ?::double precision) * interval '1 day' AS ts""")))
			.param(issueIdBase)
			.param(scale.releases())
			.param(traces)
			.param(scale.users())
			.param(syntheticPayload())
			.param(scale.issues())
			.param(scale.windowDays())
			.param(lo)
			.param(hi)
			.update());
	}

	private void seedLogs(Scale scale, List<Long> projectIds, long traces) {
		inChunks(scale.logs(), (lo, hi) -> jdbc.sql("""
				INSERT INTO log_record (id, project_id, environment, "timestamp", trace_id, span_id, level,
				                        severity_number, body, attributes, release)
				SELECT gen_random_uuid(),
				       (%s)[1 + (g %% %d)::int],
				       (%s)[1 + floor(random() * %d)::int],
				       r.ts,
				       lpad(to_hex(floor(random() * ?)::bigint), 32, '0'),
				       lpad(to_hex(g), 16, '0'),
				       (ARRAY['info','warn','error','debug'])[1 + floor(random() * 4)::int],
				       9,
				       'handling checkout step ' || (g %% 12) || ' for order ' || (g %% %d),
				       jsonb_build_object(
				           '%s', (g %% %d)::text,
				           'logger.name', (ARRAY['dev.outpost.OrderService','dev.outpost.CartService'])[1 + floor(random() * 2)::int],
				           'thread.name', 'http-nio-8080-exec-' || (g %% 16),
				           'http.status_code', (200 + (g %% 5))::int),
				       'app@1.0.' || floor(random() * ?)::int
				FROM (%s) r
				""".formatted(projectArray(projectIds), projectIds.size(), environmentArray(), ENVIRONMENTS.size(),
				BODY_ORDERS, ATTRIBUTE_KEY, ATTRIBUTE_VALUES,
				rowVariables("now() - (power(random(), 2) * ?::double precision) * interval '1 day' AS ts")))
			.param(traces)
			.param(scale.releases())
			.param(scale.windowDays())
			.param(lo)
			.param(hi)
			.update());
	}

	/** Distinct transaction names: 3 methods x 8 resources x 3 API versions. */
	private static final int TRANSACTION_NAMES = 3 * 8 * 3;

	/** Ops a Transaction is given, one of them {@code NULL}. */
	private static final int TRANSACTION_OPS = 3;

	/** Distinct Transaction Groups per Project: every name under every op. */
	public static final int TRANSACTION_GROUPS_PER_PROJECT = TRANSACTION_NAMES * TRANSACTION_OPS;

	/**
	 * Names, cycling with a period of {@link #TRANSACTION_NAMES}, modular rather
	 * than random so the group count is a fixture property, not a seed's. Every
	 * divisor must stay a multiple of {@code projects} — rows are round-robined by
	 * {@code g % projects}, so a divisor that doesn't share that factor would show
	 * each Project only part of the cycle.
	 */
	private static String transactionName(int projects) {
		return """
				(ARRAY['GET','POST','PUT'])[1 + ((g / %1$d) %% 3)::int] || ' /api/v'
				           || (1 + ((g / %3$d) %% 3))::text || '/'
				           || (ARRAY['orders','checkout','cart','profile','search','account','pricing','inventory'])[1 + ((g / %2$d) %% 8)::int]
				           || '/{id}'""".formatted(projects, projects * 3, projects * 24);
	}

	/**
	 * Ops, cycling one rung slower than the names so every name appears under every
	 * op — the leaderboard keys on (project, name, op). {@code NULL} is one of the
	 * three deliberately: {@code txn.op} is nullable, and "no op" is a legitimate
	 * Transaction Group.
	 */
	private static String transactionOp(int projects) {
		return "(ARRAY['http.server','navigation',NULL])[1 + ((g / %d) %% 3)::int]"
			.formatted(projects * TRANSACTION_NAMES);
	}

	/**
	 * Spans are inserted from the transactions' own {@code RETURNING} in the same
	 * statement, so each span shares its transaction's trace and week. Generating
	 * them independently would quietly break the trace-detail fan-out.
	 */
	private void seedTransactionsAndSpans(Scale scale, List<Long> projectIds, long traces) {
		inChunks(scale.txns(), (lo, hi) -> jdbc.sql("""
				WITH inserted AS (
				    INSERT INTO txn (id, project_id, environment, release, trace_id, span_id, parent_span_id,
				                     name, op, start_ts, end_ts, duration_ms, status, data)
				    SELECT gen_random_uuid(),
				           (%s)[1 + (g %% %d)::int],
				           (%s)[1 + floor(random() * %d)::int],
				           'app@1.0.' || floor(random() * ?)::int,
				           lpad(to_hex(floor(random() * ?)::bigint), 32, '0'),
				           lpad(to_hex(g), 16, '0'),
				           CASE WHEN g %% 3 = 0 THEN lpad(to_hex(g + 1), 16, '0') END,
				           %s,
				           %s,
				           r.ts, r.ts + make_interval(secs => r.duration / 1000.0), r.duration, 'ok',
				           ?::jsonb
				    FROM (%s) r
				    RETURNING id, project_id, trace_id, span_id, start_ts
				)
				INSERT INTO span (id, txn_id, project_id, trace_id, span_id, parent_span_id, op, description,
				                  start_ts, end_ts, duration_ms, status, data)
				SELECT gen_random_uuid(), i.id, i.project_id, i.trace_id,
				       lpad(to_hex(floor(random() * 9007199254740991)::bigint), 16, '0'),
				       i.span_id, 'db.sql.query', 'SELECT * FROM orders WHERE id = $1',
				       i.start_ts + make_interval(secs => s * 0.005),
				       i.start_ts + make_interval(secs => s * 0.005 + 0.04), 40, 'ok', ?::jsonb
				FROM inserted i CROSS JOIN generate_series(1, ?) s
				""".formatted(projectArray(projectIds), projectIds.size(), environmentArray(), ENVIRONMENTS.size(),
				transactionName(projectIds.size()), transactionOp(projectIds.size()),
				rowVariables("""
						now() - (power(random(), 2) * ?::double precision) * interval '1 day' AS ts,
						       20 + random() * 480 AS duration""")))
			.param(scale.releases())
			.param(traces)
			.param(syntheticPayload())
			.param(scale.windowDays())
			.param(lo)
			.param(hi)
			.param(syntheticSpanPayload())
			.param(scale.spansPerTxn())
			.update());
	}

	/**
	 * One trace with guaranteed fan-out across all four tables. The random pool
	 * already shares trace ids, but nothing in it is guaranteed to appear
	 * everywhere, and a trace-detail scenario that happens to hit a trace with no
	 * logs measures three queries returning nothing.
	 */
	private void seedKnownTrace(long project, long issueIdBase) {
		jdbc.sql("""
				INSERT INTO txn (id, project_id, environment, release, trace_id, span_id, parent_span_id,
				                 name, op, start_ts, end_ts, duration_ms, status, data)
				SELECT gen_random_uuid(), ?, 'production', 'app@1.0.0', ?, lpad(to_hex(g), 16, '0'),
				       CASE WHEN g > 1 THEN lpad(to_hex(1), 16, '0') END,
				       ?, ?,
				       now() - make_interval(hours => g), now() - make_interval(hours => g) + interval '120 ms',
				       120, 'ok', ?::jsonb
				FROM generate_series(1, 4) g
				""").param(project)
			.param(KNOWN_TRACE_ID)
			.param(KNOWN_TRANSACTION_NAME)
			.param(KNOWN_TRANSACTION_OP)
			.param(syntheticPayload())
			.update();
		jdbc.sql("""
				INSERT INTO span (id, txn_id, project_id, trace_id, span_id, parent_span_id, op, description,
				                  start_ts, end_ts, duration_ms, status, data)
				SELECT gen_random_uuid(), t.id, ?, ?, lpad(to_hex(1000 + s), 16, '0'), t.span_id,
				       'db.sql.query', 'SELECT * FROM orders WHERE id = $1',
				       t.start_ts + interval '5 ms', t.start_ts + interval '45 ms', 40, 'ok', ?::jsonb
				FROM txn t CROSS JOIN generate_series(1, 3) s
				WHERE t.trace_id = ?
				""").param(project).param(KNOWN_TRACE_ID).param(syntheticSpanPayload()).param(KNOWN_TRACE_ID).update();
		jdbc.sql("""
				INSERT INTO event (id, project_id, issue_id, environment, release, "timestamp", trace_id, level,
				                   message, exception_type, user_ident, data)
				SELECT gen_random_uuid(), ?, ?, 'production', 'app@1.0.0', now() - make_interval(hours => g), ?,
				       'error', 'Unhandled exception in request', 'IllegalStateException', 'user-1', ?::jsonb
				FROM generate_series(1, 3) g
				""").param(project).param(issueIdBase).param(KNOWN_TRACE_ID).param(syntheticPayload()).update();
		jdbc.sql("""
				INSERT INTO log_record (id, project_id, environment, "timestamp", trace_id, span_id, level,
				                        severity_number, body, attributes, release)
				SELECT gen_random_uuid(), ?, 'production', now() - make_interval(hours => g), ?,
				       lpad(to_hex(g), 16, '0'), 'info', 9, 'handling checkout step ' || g || ' %s',
				       jsonb_build_object('%s', '137'), 'app@1.0.0'
				FROM generate_series(1, 10) g
				""".formatted(BODY_NEEDLE, ATTRIBUTE_KEY)).param(project).param(KNOWN_TRACE_ID).update();
	}

	/**
	 * {@code next_check_at} is pushed into the future because {@code UptimeScheduler}
	 * runs during tests: a monitor that reads as due would be probed for real.
	 */
	private void seedUptime(long project, Scale scale) {
		List<Long> monitors = jdbc.sql("""
				INSERT INTO uptime_monitor (project_id, environment, url, interval_seconds, next_check_at)
				SELECT ?, 'production', 'https://example.invalid/health/' || g, 60, now() + interval '1 day'
				FROM generate_series(1, ?) g
				RETURNING id
				""").param(project).param(UPTIME_MONITORS).query(Long.class).list();
		for (long monitor : monitors) {
			jdbc.sql("""
					INSERT INTO uptime_check (monitor_id, checked_at, success, status_code, latency_ms, error)
					SELECT ?, now() - make_interval(mins => g * %d), g %% 97 <> 0, 200,
					       40 + floor(random() * 60)::int, NULL
					FROM generate_series(1, ?) g
					""".formatted(UPTIME_CHECK_INTERVAL_MINUTES))
				.param(monitor)
				.param(scale.windowDays() * (24 * 60 / UPTIME_CHECK_INTERVAL_MINUTES))
				.update();
		}
	}

	/**
	 * Issue counters and per-environment/release stats, derived from the Events in
	 * two passes. {@code last_seen} and {@code event_count} are the Issue list's sort
	 * keys, so inventing them would page over an order the data doesn't have.
	 */
	private void rollUpIssueCounters() {
		jdbc.sql("""
				WITH agg AS (
				    SELECT issue_id, environment, count(*) AS n, min("timestamp") AS first_ts, max("timestamp") AS last_ts
				    FROM event GROUP BY issue_id, environment
				), stats AS (
				    INSERT INTO issue_env_stats (issue_id, environment, event_count, last_seen)
				    SELECT issue_id, environment, n, last_ts FROM agg
				    RETURNING issue_id
				)
				UPDATE issue i
				SET event_count = t.n, first_seen = t.first_ts, last_seen = t.last_ts
				FROM (SELECT issue_id, sum(n) AS n, min(first_ts) AS first_ts, max(last_ts) AS last_ts
				      FROM agg GROUP BY issue_id) t
				WHERE i.id = t.issue_id
				""").update();
		jdbc.sql("""
				INSERT INTO issue_release_stats (issue_id, project_id, release, event_count, last_seen)
				SELECT issue_id, project_id, release, count(*), max("timestamp")
				FROM event
				WHERE release IS NOT NULL AND btrim(release) <> ''
				GROUP BY issue_id, project_id, release
				""").update();
	}

	private void analyze() {
		jdbc.sql("VACUUM ANALYZE " + String.join(", ", PartitionManager.TABLES)
				+ ", issue, issue_env_stats, issue_release_stats, release, uptime_check").update();
	}

	private interface Chunk {

		void insert(long lo, long hi);

	}

	/**
	 * The per-row random draws, as a derived table rather than a {@code CROSS JOIN
	 * LATERAL} — a lateral referencing nothing from the outer query is evaluated
	 * once, and every row would share one timestamp. {@code OFFSET 0} is a real
	 * optimization fence: without it the planner may inline the volatile
	 * expressions and draw a fresh value per reference, decoupling an event's
	 * project from its issue.
	 */
	private static String rowVariables(String columns) {
		return """
				SELECT g, %s
				FROM generate_series(?, ?) g
				OFFSET 0""".formatted(columns);
	}

	private static void inChunks(long total, Chunk chunk) {
		for (long lo = 1; lo <= total; lo += CHUNK) {
			chunk.insert(lo, Math.min(total, lo + CHUNK - 1));
		}
	}

	private long count(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
	}

	private static String release(int index) {
		return "app@1.0." + index;
	}

	/** Ids are longs read straight out of {@code project}, so there is nothing to bind. */
	private static String projectArray(List<Long> projectIds) {
		return "ARRAY[" + String.join(",", projectIds.stream().map(String::valueOf).toList()) + "]::bigint[]";
	}

	private static String environmentArray() {
		return "ARRAY['" + String.join("','", ENVIRONMENTS) + "']";
	}

	/**
	 * ~250 B of jsonb per span. Deliberately smaller than {@link #syntheticPayload}:
	 * the shape borrows from an OpenTelemetry database span, which carries a
	 * statement and a handful of attributes rather than a stack trace.
	 */
	private static String syntheticSpanPayload() {
		return """
				{"db.system":"postgresql","db.name":"outpost","db.user":"app",\
				"db.statement":"SELECT o.id, o.total, o.status FROM orders o WHERE o.customer_id = $1 ORDER BY o.created_at DESC LIMIT 50",\
				"db.operation":"SELECT","net.peer.name":"db.internal","net.peer.port":5432,\
				"thread.name":"http-nio-8080-exec-7"}""";
	}

	/**
	 * ~1 KB of jsonb per row, so heap fetches and TOAST behave as they do against
	 * real payloads; the shape borrows from a Sentry event but the contents are filler.
	 */
	private static String syntheticPayload() {
		StringBuilder frames = new StringBuilder();
		for (int i = 0; i < 6; i++) {
			if (i > 0) {
				frames.append(',');
			}
			frames.append("""
					{"module":"dev.outpost.OrderService","function":"loadCustomer","in_app":true,"lineno":%d,\
					"filename":"OrderService.java","abs_path":"/srv/app/src/main/java/dev/outpost/OrderService.java"}"""
				.formatted(80 + i));
		}
		return """
				{"platform":"java","logentry":{"formatted":"Unhandled exception in request"},\
				"contexts":{"runtime":{"name":"OpenJDK","version":"25"},\
				"os":{"name":"Linux","version":"6.8.0"},"device":{"arch":"aarch64","memory_size":17179869184}},\
				"tags":{"handled":"no","mechanism":"servlet","transaction":"GET /api/checkout/{id}"},\
				"exception":{"values":[{"type":"IllegalStateException","value":"order has no customer",\
				"module":"java.lang","stacktrace":{"frames":[%s]}}]}}""".formatted(frames);
	}

}
