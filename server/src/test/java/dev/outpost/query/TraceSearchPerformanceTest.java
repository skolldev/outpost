package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import dev.outpost.support.PlanFacts;
import dev.outpost.support.TelemetrySeeder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Performance regression guard for trace search: {@code EXPLAIN}s the
 * controller's own SQL (via {@link TraceController#buildSearchQuery}, never a
 * copy) and asserts the shared blocks touched stay under a ceiling, chosen over
 * wall-clock since it's machine-independent. Both {@code has_errors} branches
 * are guarded — the default path and the one adding the {@code EXISTS} filter,
 * which Postgres de-correlates into a cheap hash semi-join.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
		"outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class TraceSearchPerformanceTest {

	/**
	 * Healthy is ~1.8k shared hits; 50k leaves headroom while still catching a
	 * regression, and counts blocks read as well as hit so a cold cache can't turn
	 * a runaway plan into a passing guard.
	 */
	private static final long MAX_SHARED_BUFFER_HITS = 50_000;

	/** Enough candidate transactions that an O(rows) subquery is unmistakable. */
	private static final int TRANSACTIONS = 5_000;

	/** Many transactions per trace, so dedup discards most rows (as in production). */
	private static final int TXNS_PER_TRACE = 4;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	@BeforeEach
	void seed() {
		// Truncates via TelemetrySeeder.clear() rather than DELETE, so a neighboring guard's rows don't occupy pages here.
		new TelemetrySeeder(jdbc, partitions).clear();

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

	private void assertBufferHitsUnderCeiling(QueryPlans.Built search) {
		PlanFacts facts = search.explain(jdbc);

		assertThat(facts.logicalIo())
			.as("shared blocks touched by trace search (regression was ~634k, healthy ~1.8k)%n%s", facts.plan())
			.isLessThan(MAX_SHARED_BUFFER_HITS);
	}

	private QueryPlans.Built searchSql(Boolean hasErrors) {
		return QueryPlans.traceSearch(null, null, null, null, null, null, hasErrors, null, null, null);
	}

}
