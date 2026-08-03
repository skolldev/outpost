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
 * Builds a production-<em>shaped</em> telemetry dataset directly in Postgres, for
 * the retrieval guards (thousands of rows) and the retrieval benchmark (millions).
 *
 * <p>Seeding goes through {@code INSERT … SELECT … FROM generate_series(…)} rather
 * than the ingest path on purpose: ingest tops out around 700 events/s, so
 * seeding two million events through it would take the better part of an hour
 * and measure the write path instead of preparing the read one.
 *
 * <p>Everything below is a decision about whether the numbers a benchmark built on
 * this produces mean anything. They are easy to get wrong in a direction that
 * makes the read path look fast:
 *
 * <ul>
 * <li><b>Partitions come from the real {@link PartitionManager}.</b> Hand-rolled
 * {@code CREATE TABLE … PARTITION OF} (as {@code scripts/seed.sql} does) would let
 * the seeder and production drift apart silently, and a pruning assertion against
 * a partition layout production never uses proves nothing.
 * <li><b>Events per issue are skewed, not uniform.</b> Uniform events-per-issue
 * makes every issue equally cheap and hides the aggregates that hurt in
 * production. Issues are drawn as {@code floor(issues · random()³)}, so a handful
 * own most of the events.
 * <li><b>{@code user_ident} has real cardinality.</b> It feeds {@code
 * count(DISTINCT user_ident)} directly: one distinct user makes the aggregate
 * free, one per row makes it degenerate. ~10 000 is the shape that costs what
 * production costs.
 * <li><b>{@code release} cardinality stays small</b> (~20), because the issue-list
 * release filter and {@code ReleaseController}'s correlated count are both
 * suspects and both get cheaper as versions multiply.
 * <li><b>Timestamps span the full window, weighted recent</b> ({@code random()²}),
 * so the partition count is production-shaped rather than one week deep.
 * <li><b>{@code data} is ~1 KB of synthetic jsonb</b>, so heap fetches and TOAST
 * behave like they do against real payloads. It is not a real Sentry event — read
 * it as a size, not as a fixture. Spans get a smaller (~250 B) payload of their
 * own: a database span carries a statement and a few attributes, not a stack
 * trace, and there are three spans per transaction — giving them the event
 * payload would add gigabytes to the seed to model something no span has.
 * Empty is the one thing they must not be, or every span row is free.
 * <li><b>{@code trace_id}s are drawn from one pool shared by all four tables</b>,
 * so the trace-detail fan-out has real work to do in each.
 * <li><b>Issue counters are derived from the events</b>, not invented, because
 * {@code last_seen} and {@code event_count} are the issue list's sort keys — a
 * seeder that made them up would page over an order the data does not have.
 * <li><b>{@code VACUUM ANALYZE} runs at the end.</b> Without the {@code ANALYZE}
 * the planner is blind and every number the run produces is about a plan nobody
 * will ever get. The {@code VACUUM} is there for a subtler reason: it sets the
 * hint bits on the freshly bulk-loaded heap, which the first reader would
 * otherwise set as a side effect of reading. That made the first measurement of a
 * query cost about twice its steady state, so a guard's number depended on
 * whether it happened to run first — a difference of ~185 blocks against ~90 on
 * the issue list. Paying it once here makes the guards order-independent.
 * </ul>
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
		 * Scales the <b>row counts</b> by {@code factor} and nothing else.
		 *
		 * <p>Cardinalities and the time window are what make the data
		 * production-shaped, so they are deliberately left alone. Scaling
		 * {@code users} down would make {@code count(DISTINCT user_ident)} — the single
		 * most suspect query in the read path — cheaper on a smaller run, so a smoke
		 * run would understate exactly the thing it exists to look at. Scaling
		 * {@code issues} down would take the issue list from eighty pages to eight and
		 * quietly turn the deep-pagination scenario into a shallow one.
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
	 * The substring a body search looks for. Deliberately <em>selective</em> (~1 in
	 * {@link #BODY_ORDERS} rows): a needle matching every body makes the planner
	 * choose a sequential scan on its own, which would test the wrong thing while
	 * looking like a trigram-index guard.
	 */
	public static final String BODY_NEEDLE = "for order 137";

	/** Distinct order numbers in a log body — the denominator of {@link #BODY_NEEDLE}'s selectivity. */
	private static final int BODY_ORDERS = 1_000;

	/** The trace guaranteed to fan out across all four tables. */
	private static final String KNOWN_TRACE_ID = "0".repeat(24) + "cafebabe";

	/** Rows per {@code INSERT … SELECT}: big enough to amortize, small enough to bound WAL and memory. */
	private static final long CHUNK = 250_000;

	private static final int UPTIME_MONITORS = 8;

	/** Sparser than any real monitor: the overview buckets by day, so per-minute rows only cost time. */
	private static final int UPTIME_CHECK_INTERVAL_MINUTES = 15;

	/** Postgres settings that decide whether a run is measuring cache or disk; recorded, never assumed. */
	private static final List<String> REPORTED_SETTINGS = List.of("shared_buffers", "work_mem", "maintenance_work_mem",
			"effective_cache_size", "synchronous_commit", "max_parallel_workers_per_gather", "server_version");

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

		// The event and its timestamp together, because the detail endpoint's neighbour
		// lookups key on (issue_id, timestamp, id) — an id alone cannot reproduce them.
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
	 * Empties every table this seeder writes, and reclaims their pages.
	 *
	 * <p>{@code TRUNCATE}, not {@code DELETE}, and the difference is load-bearing:
	 * deleted rows leave their pages allocated until a {@code VACUUM}, so a
	 * sequential scan of a table that has been emptied still reads every block the
	 * data used to occupy. A later test measuring buffer counts against what it
	 * believes is a small table then fails for a reason that has nothing to do with
	 * its own query — and the whole point of these fixtures is that a failure means
	 * what it says.
	 */
	public void clear() {
		jdbc.sql("""
				TRUNCATE event, log_record, txn, span, issue_env_stats, issue_release_stats, issue,
				         uptime_check, uptime_incident, uptime_monitor,
				         release, environment, project_key, project CASCADE
				""").update();
	}

	// -------------------------------------------------------------- partitions

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

	// ------------------------------------------------------------------ tables

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
	 * issue is {@code base + k} and its project is {@code projectIds[k % n]} — an
	 * arithmetic the event insert reuses instead of joining back to {@code issue}.
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
			// Issues the skew leaves event-less keep these placeholders, so they sort
			// to the bottom of the default list instead of crowding out page 1.
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

	/**
	 * Spans are inserted from the transactions' own {@code RETURNING} in the same
	 * statement, so every span really belongs to the transaction it names and shares
	 * its trace and week. Generating them independently would have been simpler and
	 * would have quietly broken the trace-detail fan-out.
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
				           'GET /api/' || (ARRAY['orders','checkout','cart','profile'])[1 + (g %% 4)::int] || '/{id}',
				           'http.server',
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
				       'GET /api/checkout/{id}', 'http.server',
				       now() - make_interval(hours => g), now() - make_interval(hours => g) + interval '120 ms',
				       120, 'ok', ?::jsonb
				FROM generate_series(1, 4) g
				""").param(project).param(KNOWN_TRACE_ID).param(syntheticPayload()).update();
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
	 * two passes. Environment stats share the issue-counter aggregate; release stats
	 * need a different grouping.
	 *
	 * <p>{@code last_seen} and {@code event_count} are the Issue list's sort keys,
	 * so inventing them would page over an order the data does not have.
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
				WHERE release IS NOT NULL
				GROUP BY issue_id, project_id, release
				""").update();
	}

	private void analyze() {
		jdbc.sql("VACUUM ANALYZE " + String.join(", ", PartitionManager.TABLES)
				+ ", issue, issue_env_stats, issue_release_stats, release, uptime_check").update();
	}

	// ----------------------------------------------------------------- helpers

	private interface Chunk {

		void insert(long lo, long hi);

	}

	/**
	 * The per-row random draws, as a derived table over the row numbers rather than
	 * a {@code CROSS JOIN LATERAL}. A lateral subquery that references nothing from
	 * the outer query is not lateral at all: Postgres evaluates it <b>once</b> and
	 * the join replicates that single row, so every seeded row would share one
	 * timestamp and one issue — a dataset that looks seeded and measures nothing.
	 *
	 * <p>{@code OFFSET 0} is the standard optimization fence. Without it the planner
	 * may pull the subquery up and inline a volatile expression into each of its
	 * reference sites, drawing a fresh value per site — which would decouple an
	 * event's project from its issue.
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
	 * ~1 KB of jsonb per row, so heap fetches and TOAST behave as they do against
	 * real payloads. Synthetic: the shape borrows from a Sentry event, the contents
	 * are filler.
	 */
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
