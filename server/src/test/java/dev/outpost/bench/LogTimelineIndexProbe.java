package dev.outpost.bench;

import dev.outpost.db.PartitionManager;
import dev.outpost.query.QueryPlans;
import dev.outpost.support.PlanFacts;
import dev.outpost.support.TelemetrySeeder;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What the log timeline's covering index costs and buys at benchmark scale
 * (#141). Opt-in like the other benchmarks:
 * {@snippet lang = shell : ./gradlew retrievalBenchmark --tests '*LogTimelineIndexProbe' -Pbench.scale=0.4 }
 *
 * <p>The guard-tier measurement in {@code LogTimelinePerformanceTest} answers
 * whether the plan is the right <em>shape</em>. It cannot answer what a seventh
 * index on the highest-volume table in the product costs, because 40 000 records
 * make every storage and write number too small to extrapolate from — which is
 * exactly the mistake {@code V11}'s notes warn against when they decline to turn
 * one build time into a rate. So this asks the three questions a migration has to
 * answer, at the ~2 000 000 records {@code V11} quoted its own numbers against:
 * how long the build blocks ingest, what the index costs on disk, and what it
 * costs every insert thereafter.
 *
 * <p>It reports and asserts nothing. The numbers decide whether the migration is
 * worth writing, and a threshold here would be a guess standing in for that
 * judgement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(BenchContainerConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("benchmark")
@Tag("retrieval")
class LogTimelineIndexProbe {

	/** The candidate: every column the timeline touches, so it can be scanned index-only. */
	private static final String COVERING_INDEX = """
			CREATE INDEX idx_log_timeline ON log_record (project_id, "timestamp", level, environment)
			""";

	/** Rows per timed bulk insert — enough that per-row index maintenance dominates the fixed cost. */
	private static final int WRITE_PROBE_ROWS = 200_000;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	TelemetrySeeder.Seeded seeded;

	@BeforeAll
	void seed() {
		double scale = Double.parseDouble(System.getProperty("outpost.bench.scale", "1.0"));
		seeded = new TelemetrySeeder(jdbc, partitions).seed(TelemetrySeeder.Scale.DEFAULT.times(scale));
	}

	@Test
	void coveringIndexCostAndBenefit() {
		Instant now = Instant.now();
		Instant allTimeFrom = partitions.earliestPartitionStart(PartitionManager.LOG_RECORD).orElseThrow();
		long records = records();

		System.out.printf("%n=== log_record: %d records, %d partitions, heap %s, indexes %s%n", records,
				partitionCount(), heapSize(), indexSize());
		System.out.println("=== read path WITHOUT the covering index");
		readShapes(now, allTimeFrom);
		long writeWithout = timedBulkInsert();

		long indexBytesBefore = indexBytes();
		long buildMillis = System.currentTimeMillis();
		jdbc.sql(COVERING_INDEX).update();
		buildMillis = System.currentTimeMillis() - buildMillis;
		jdbc.sql("VACUUM ANALYZE log_record").update();
		long added = indexBytes() - indexBytesBefore;

		System.out.printf("%n=== build blocked ingest for %d ms (ShareLock on log_record for the whole build)%n",
				buildMillis);
		System.out.printf("=== index adds %s — %d bytes/record, taking index storage from %s to %s (+%d %%)%n",
				pretty(added), added / Math.max(1, records), pretty(indexBytesBefore), indexSize(),
				100 * added / Math.max(1, indexBytesBefore));
		System.out.println("=== read path WITH the covering index");
		readShapes(now, allTimeFrom);
		long writeWith = timedBulkInsert();

		System.out.printf("%n=== bulk insert of %d rows: %d ms without the index, %d ms with (+%d %%)%n",
				WRITE_PROBE_ROWS, writeWithout, writeWith, 100 * (writeWith - writeWithout) / Math.max(1, writeWithout));
	}

	/** Every timeline shape the logs page can produce, plus the list page the index must not regress. */
	private void readShapes(Instant now, Instant allTimeFrom) {
		Instant since = now.minus(14, ChronoUnit.DAYS);
		timeline("1h", null, null, now.minus(1, ChronoUnit.HOURS), now);
		timeline("24h", null, null, now.minus(24, ChronoUnit.HOURS), now);
		timeline("14d (the default)", null, null, since, now);
		timeline("30d", null, null, now.minus(30, ChronoUnit.DAYS), now);
		timeline("All time", null, null, allTimeFrom, now);
		timeline("14d, one project", List.of(seeded.projectId()), null, since, now);
		timeline("14d, one environment", null, List.of(seeded.environment()), since, now);
		timeline("All time, one project", List.of(seeded.projectId()), null, allTimeFrom, now);

		PlanFacts list = QueryPlans.logs(null, null, null, null, null, null, null, since, null, null).explain(jdbc);
		System.out.printf("    %-34s %8d blocks%n", "log list page 1, 14d", list.logicalIo());
	}

	private void timeline(String what, List<Long> project, List<String> environment, Instant from, Instant to) {
		Duration bucket = QueryPlans.timelineBucket(from, to);
		PlanFacts facts = QueryPlans.logTimeline(project, environment, null, null, null, null, null, from, to)
			.explain(jdbc);
		System.out.printf("    %-34s %8d blocks  bucket=%-6s bars=%-4d temp=%d%n", what, facts.logicalIo(), bucket,
				Duration.between(from, to).dividedBy(bucket), facts.tempBlocks());
	}

	/**
	 * Appends {@value #WRITE_PROBE_ROWS} rows to the current week and returns the
	 * milliseconds it took. A proxy for ingest cost, not a throughput measurement:
	 * it isolates per-row index maintenance from everything else {@code LogStore}
	 * does, which is the only part a new index changes.
	 */
	private long timedBulkInsert() {
		long started = System.currentTimeMillis();
		jdbc.sql("""
				INSERT INTO log_record (id, project_id, environment, "timestamp", trace_id, span_id, level,
				                        severity_number, body, attributes, release)
				SELECT gen_random_uuid(), ?, 'production', now() - (g % 3600) * interval '1 second',
				       md5(random()::text), substr(md5(random()::text), 1, 16), 'info', 9,
				       'write probe row ' || g, '{"probe": true}'::jsonb, 'probe'
				FROM generate_series(1, ?) g
				""").param(seeded.projectId()).param(WRITE_PROBE_ROWS).update();
		return System.currentTimeMillis() - started;
	}

	private long records() {
		return jdbc.sql("SELECT count(*) FROM log_record").query(Long.class).single();
	}

	private long partitionCount() {
		return jdbc.sql("""
				SELECT count(*) FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'log_record'
				""").query(Long.class).single();
	}

	private long indexBytes() {
		return jdbc.sql("""
				SELECT coalesce(sum(pg_relation_size(c.oid)), 0)
				FROM pg_class c JOIN pg_index x ON x.indexrelid = c.oid
				JOIN pg_class t ON t.oid = x.indrelid
				WHERE t.relname LIKE 'log\\_record\\_p%'
				""").query(Long.class).single();
	}

	private String indexSize() {
		return pretty(indexBytes());
	}

	private String heapSize() {
		return pretty(jdbc.sql("""
				SELECT coalesce(sum(pg_relation_size(c.oid)), 0) FROM pg_class c WHERE c.relname LIKE 'log\\_record\\_p%'
				""").query(Long.class).single());
	}

	private static String pretty(long bytes) {
		return bytes / (1024 * 1024) + " MB";
	}

}
