package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.support.PlanFacts;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The assertion vocabulary the retrieval guards share. Everything here is
 * machine-independent — logical I/O and plan shape, never wall clock — so a slow
 * or loaded CI box cannot flake it.
 *
 * <p>A buffer ceiling on its own is a blunt instrument: it cannot tell a query
 * that pruned correctly from one that got lucky on a small dataset, and it
 * changes meaning the moment the dataset does. The plan-shape assertions
 * ({@link #assertPrunesTo}, {@link #assertNoSequentialScanOfTelemetry},
 * {@link #assertNoTempFiles}) are the ones that survive a change of scale; the
 * ceiling sits behind them as a backstop.
 *
 * <h2>Calibrating a ceiling</h2>
 *
 * For a path whose current plan is healthy: ten times its measured logical I/O,
 * confirmed by {@link #assertCeilingCanFail} to sit below the cost of the
 * corresponding full scan — a ceiling above the scan cost cannot fail and is
 * decoration. For a path with a known bug the rule inverts: the ceiling goes at
 * the <em>healthy target</em> and the guard is {@code @Disabled} naming its
 * follow-up issue. Calibrating off a bug's plan would certify the bug as the
 * baseline and call it a guard.
 */
final class QueryGuard {

	/** The partitioned tables. A sequential scan of any of them is the failure these guards exist to catch. */
	static final List<String> TELEMETRY_TABLES = List.of("event", "log_record", "txn", "span");

	/** A column of each telemetry table that no index covers, so a full scan really reads the heap. */
	private static final List<String> FULL_SCAN_COLUMNS = List.of("message", "body", "name", "description");

	private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.BASIC_ISO_DATE;

	/** Below this a partition is small enough that reading it end to end is simply the right plan. */
	private static final long MIN_MATERIAL_PARTITION_ROWS = 100;

	private QueryGuard() {
	}

	static PlanFacts explain(JdbcClient jdbc, QueryPlans.Built built) {
		return built.explain(jdbc);
	}

	static void assertUnderCeiling(PlanFacts facts, long ceiling, String what) {
		assertThat(facts.logicalIo()).as("shared blocks touched by %s%n%s", what, facts.plan()).isLessThan(ceiling);
	}

	/**
	 * A page-sized result that spills to a temp file is sorting or hashing something
	 * far larger than the page — the signature of an aggregate or ordering the
	 * pagination cannot push down.
	 */
	static void assertNoTempFiles(PlanFacts facts, String what) {
		assertThat(facts.tempBlocks()).as("temp-file blocks for %s — a normal page should sort in memory%n%s", what,
				facts.plan()).isZero();
	}

	/**
	 * No sequential scan of a partition holding a material share of its table.
	 *
	 * <p>The share matters. A weekly partition at the edge of the retention window
	 * holds a handful of rows, and reading those end to end is the cheapest plan
	 * available — Postgres picks it for healthy queries, and failing on it would
	 * make this assertion useless noise. What it catches is the real thing: a
	 * selective lookup falling back to reading a partition that holds real data.
	 */
	static void assertNoSequentialScanOfTelemetry(JdbcClient jdbc, PlanFacts facts, String what) {
		Set<String> scans = new LinkedHashSet<>();
		for (String table : TELEMETRY_TABLES) {
			long threshold = Math.max(MIN_MATERIAL_PARTITION_ROWS, rowCount(jdbc, table) / 100);
			for (String partition : facts.sequentialScansOf(table)) {
				if (rowCount(jdbc, partition) > threshold) {
					scans.add(partition);
				}
			}
		}
		assertThat(scans)
			.as("sequential scans of populated telemetry partitions by %s — a selective lookup must use an index%n%s",
					what, facts.plan())
			.isEmpty();
	}

	/**
	 * A query bounded from {@code from} onwards must read only the weekly partitions
	 * at or after that week. Also asserts the table has more partitions than that,
	 * or the check is vacuous: on a dataset that fits in one week, everything prunes
	 * perfectly forever.
	 */
	static void assertPrunesFrom(JdbcClient jdbc, PlanFacts facts, String table, Instant from, String what) {
		Set<String> allowed = partitionsFrom(jdbc, table, from);
		assertThat(partitionCount(jdbc, table))
			.as("weekly partitions of %s — pruning cannot be tested against a single-partition dataset", table)
			.isGreaterThan(allowed.size());
		assertThat(facts.partitionsScanned(table))
			.as("partitions of %s read by %s; a bound at %s allows only %s%n%s", table, what, from, allowed,
					facts.plan())
			.isSubsetOf(allowed);
	}

	/**
	 * A ceiling above the cost of simply reading the table cannot fail, whatever the
	 * plan does. Every enabled ceiling has to clear this or it is decoration.
	 */
	static void assertCeilingCanFail(JdbcClient jdbc, long ceiling, String... tables) {
		for (String table : tables) {
			long fullScan = fullScanCost(jdbc, table);
			assertThat(ceiling).as("ceiling %d vs the %d blocks a full scan of %s costs on this dataset", ceiling,
					fullScan, table).isLessThan(fullScan);
		}
	}

	/**
	 * What reading {@code table} end to end costs here. Aggregating an unindexed
	 * text column forces the heap read: {@code count(*)} may be answered from an
	 * index and would understate it.
	 */
	static long fullScanCost(JdbcClient jdbc, String table) {
		String column = FULL_SCAN_COLUMNS.get(TELEMETRY_TABLES.indexOf(table));
		return PlanFacts.explain(jdbc, "SELECT count(" + column + ") FROM " + table, List.of()).logicalIo();
	}

	static long partitionCount(JdbcClient jdbc, String table) {
		return jdbc.sql("""
				SELECT count(*) FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = ?
				""").param(table).query(Long.class).single();
	}

	/**
	 * Every existing partition of {@code table} at or after {@code from}'s week.
	 * Read from the catalogue rather than generated, because these predicates have
	 * no upper bound — future partitions are legitimately in range.
	 */
	private static Set<String> partitionsFrom(JdbcClient jdbc, String table, Instant from) {
		String earliest = table + "_p" + PARTITION_SUFFIX.format(weekStart(from));
		return new LinkedHashSet<>(jdbc.sql("""
				SELECT c.relname FROM pg_inherits i
				JOIN pg_class c ON c.oid = i.inhrelid
				JOIN pg_class p ON p.oid = i.inhparent
				WHERE p.relname = ? AND c.relname >= ?
				""").param(table).param(earliest).query(String.class).list());
	}

	private static long rowCount(JdbcClient jdbc, String relation) {
		return jdbc.sql("SELECT count(*) FROM " + relation).query(Long.class).single();
	}

	private static LocalDate weekStart(Instant timestamp) {
		return timestamp.atZone(ZoneOffset.UTC).toLocalDate().with(DayOfWeek.MONDAY);
	}

}
