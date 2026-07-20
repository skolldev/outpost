package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.query.TraceController.SearchQuery;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Performance regression guard for trace search (§9.4). The original query
 * computed {@code span_count} / {@code error_count} as correlated subqueries
 * inside the {@code DISTINCT ON} scan, so both ran once per candidate
 * transaction (thousands) and were then discarded by dedup + LIMIT — ~2.4s and
 * 634k shared-buffer hits on a production-sized dataset, versus ~20ms / ~1.8k
 * once the counts were moved after pagination.
 *
 * <p>The guard seeds a production-shaped dataset, then {@code EXPLAIN (ANALYZE,
 * BUFFERS)}es the <b>controller's own SQL</b> (via {@link
 * TraceController#buildSearchQuery}, not a copy — a copy would keep passing if
 * the real query regressed) and asserts the shared-buffer hit count stays under
 * a ceiling. Buffer count is chosen over wall-clock deliberately: it is
 * machine-independent, so a slow or loaded CI box cannot flake it, and it is the
 * quantity that actually blew up in the regression (350x). Both {@code
 * has_errors} branches are guarded: the default path and the one that adds the
 * {@code EXISTS} filter (which Postgres de-correlates into a hash semi-join, so
 * it stays cheap — this locks that in).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class TraceSearchPerformanceTest {

	/**
	 * Healthy is ~1.8k shared hits; the regression was ~634k. 50k sits ~12x above
	 * healthy (absorbing dataset/plan drift) and ~12x below the bug, so it fails
	 * loudly on an O(rows) reintroduction without flaking on a well-behaved query.
	 */
	private static final long MAX_SHARED_BUFFER_HITS = 50_000;

	/** Enough candidate transactions that an O(rows) subquery is unmistakable. */
	private static final int TRANSACTIONS = 5_000;

	/** Many transactions per trace, so dedup discards most rows (as in production). */
	private static final int TXNS_PER_TRACE = 4;

	private static final Pattern SHARED_HIT = Pattern.compile("shared hit=(\\d+)");

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void seed() {
		jdbc.sql("DELETE FROM span").update();
		jdbc.sql("DELETE FROM txn").update();
		jdbc.sql("DELETE FROM event").update();
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM project").update();

		long project = jdbc.sql("INSERT INTO project (slug, name) VALUES ('perf', 'Perf') RETURNING id")
			.query(Long.class)
			.single();
		long issue = jdbc.sql("""
				INSERT INTO issue (project_id, fingerprint, title, first_seen, last_seen)
				VALUES (?, 'fp', 'boom', now(), now()) RETURNING id
				""").param(project).query(Long.class).single();

		Instant base = Instant.now().minus(2, ChronoUnit.DAYS);
		int traces = TRANSACTIONS / TXNS_PER_TRACE;
		for (int tr = 0; tr < traces; tr++) {
			String traceId = String.format("%032x", tr);
			for (int j = 0; j < TXNS_PER_TRACE; j++) {
				Instant start = base.plusSeconds((long) tr * TXNS_PER_TRACE + j);
				// j==0 is the root (parent_span_id NULL); the rest are continuations.
				jdbc.sql("""
						INSERT INTO txn (id, project_id, environment, release, trace_id, span_id, parent_span_id,
						                 name, op, start_ts, end_ts, duration_ms, status)
						VALUES (?, ?, 'prod', 'r@1', ?, ?, ?, ?, 'http.server', ?, ?, 120, 'ok')
						""")
					.param(UUID.randomUUID())
					.param(project)
					.param(traceId)
					.param(String.format("%016x", tr * 10L + j))
					.param(j == 0 ? null : String.format("%016x", tr * 10L))
					.param("GET /trace/" + tr)
					.param(java.sql.Timestamp.from(start))
					.param(java.sql.Timestamp.from(start.plusMillis(120)))
					.update();
				// One span per transaction — span_count fans out across the trace.
				jdbc.sql("""
						INSERT INTO span (id, txn_id, project_id, trace_id, span_id, parent_span_id, op, description,
						                  start_ts, end_ts, duration_ms, status)
						VALUES (?, ?, ?, ?, ?, ?, 'db.sql.query', 'SELECT 1', ?, ?, 40, 'ok')
						""")
					.param(UUID.randomUUID())
					.param(UUID.randomUUID())
					.param(project)
					.param(traceId)
					.param(String.format("%016x", tr * 100L + j))
					.param(String.format("%016x", tr * 10L + j))
					.param(java.sql.Timestamp.from(start.plusMillis(10)))
					.param(java.sql.Timestamp.from(start.plusMillis(50)))
					.update();
			}
			// An error on every fourth trace, so has_errors matches a realistic subset.
			if (tr % 4 == 0) {
				jdbc.sql("""
						INSERT INTO event (id, project_id, issue_id, environment, "timestamp", trace_id, level,
						                   message, exception_type, data)
						VALUES (?, ?, ?, 'prod', ?, ?, 'error', 'boom', 'Boom', '{}'::jsonb)
						""")
					.param(UUID.randomUUID())
					.param(project)
					.param(issue)
					.param(java.sql.Timestamp.from(base.plusSeconds(tr)))
					.param(traceId)
					.update();
			}
		}
		jdbc.sql("ANALYZE txn, span, event").update();
	}

	@Test
	void defaultSearchStaysUnderBufferCeiling() {
		assertBufferHitsUnderCeiling(searchSql(null));
	}

	@Test
	void hasErrorsSearchStaysUnderBufferCeiling() {
		assertBufferHitsUnderCeiling(searchSql(true));
	}

	private void assertBufferHitsUnderCeiling(SearchQuery search) {
		List<String> plan = jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + search.sql())
			.params(search.params())
			.query(String.class)
			.list();

		long totalSharedHits = 0;
		for (String line : plan) {
			Matcher m = SHARED_HIT.matcher(line);
			while (m.find()) {
				totalSharedHits += Long.parseLong(m.group(1));
			}
		}
		assertThat(totalSharedHits)
			.as("shared-buffer hits for trace search (regression was ~634k, healthy ~1.8k)\n%s",
					String.join("\n", plan))
			.isLessThan(MAX_SHARED_BUFFER_HITS);
	}

	private SearchQuery searchSql(Boolean hasErrors) {
		return TraceController.buildSearchQuery(null, null, null, null, null, null, hasErrors, null, null, null);
	}

}
