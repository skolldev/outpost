package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.db.PartitionManager;
import dev.outpost.support.PlanFacts;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * ({@link #assertPrunesFrom}, {@link #assertNoSequentialScanOfTelemetry},
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

	/**
	 * The partitioned tables, from the one place production registers them. A
	 * sequential scan of a populated one is the failure these guards exist to catch.
	 */
	static final List<String> TELEMETRY_TABLES = PartitionManager.TABLES;

	/**
	 * Per table, a column no index covers — aggregating it forces the heap read that
	 * makes {@link #fullScanCost} an honest upper bound. {@code count(*)} may be
	 * answered from an index and would understate it.
	 *
	 * <p>{@code txn} uses {@code status} rather than the {@code name} it used to,
	 * because {@code V15} put {@code name} in a covering index: {@code count(name)}
	 * then became an index-only scan measuring 447 blocks where the heap costs 2 691,
	 * which would have quietly turned every {@code txn} ceiling into one that cannot
	 * fail. Any future index over one of these columns has the same effect — pick a
	 * different one rather than accepting the smaller number.
	 */
	private static final Map<String, String> FULL_SCAN_COLUMNS = Map.of(PartitionManager.EVENT, "message",
			PartitionManager.LOG_RECORD, "body", PartitionManager.TXN, "status", PartitionManager.SPAN, "description");

	private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.BASIC_ISO_DATE;

	/** Below this a partition is small enough that reading it end to end is simply the right plan. */
	private static final long MIN_MATERIAL_PARTITION_ROWS = 100;

	private QueryGuard() {
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
	 * Every index this plan read on {@code table} belongs to {@code index}, and no
	 * {@code Sort} ran.
	 *
	 * <p>Both halves are load-bearing, for the reasons #126 established. "Some index
	 * was used" cannot fail when a redundant index is added, so the index is named;
	 * and a bitmap scan of the right index returns rows in heap order and sorts them
	 * anyway, so the {@code Sort} has to be excluded separately. An
	 * {@code Incremental Sort} is deliberately allowed — it is bounded by the group
	 * size rather than the table, which is the property being guarded.
	 *
	 * <p>Naming an index on a <em>partitioned</em> table means naming a family: the
	 * parent index in the migration is a catalogue entry with no storage, and the
	 * plan reads the per-partition children Postgres named itself. The family is
	 * read from {@code pg_inherits} rather than pattern-matched, so a rename in the
	 * migration cannot silently widen what this accepts.
	 *
	 * <p>The assertion is containment, not intersection: it is satisfied only if
	 * <em>every</em> index touched on a populated partition is in the family.
	 * Intersection would pass a plan that walked the right index on one partition
	 * and the wrong one on the other nine.
	 *
	 * <p>Several indexes may be named where several are genuinely equivalent — a
	 * request filtering on project <em>and</em> environment can walk either the
	 * project-leading or the project+environment index in order, and they measured
	 * four blocks apart. Pinning one of two near-equal plans produces a guard that
	 * fails when the planner picks the other, which is a fact about cost estimates
	 * rather than about health, and a guard that fails for a healthy plan is one the
	 * next person disables. Naming both still excludes the plan that matters — the
	 * global ordering index walked with both filters applied as predicates.
	 *
	 * <p>Indexes on <em>empty</em> partitions are excluded, for the same reason
	 * {@link #assertNoSequentialScanOfTelemetry} discounts small ones: the partition
	 * manager keeps a week of partitions ahead of the newest row, and which index
	 * the planner picks on a relation holding nothing is not a fact about the
	 * query — both cost zero and it chooses arbitrarily between them.
	 */
	static void assertWalksIndex(JdbcClient jdbc, PlanFacts facts, String table, List<String> indexes, String what) {
		Set<String> family = new LinkedHashSet<>();
		for (String index : indexes) {
			assertThat(exists(jdbc, index))
				.as("index %s does not exist — the guard names an index the migrations do not create", index)
				.isTrue();
			family.addAll(indexFamily(jdbc, index));
		}

		Set<String> used = new LinkedHashSet<>();
		indexesOf(jdbc, table).forEach((name, relation) -> {
			if (facts.indexesUsed().contains(name) && rowCount(jdbc, relation) > 0) {
				used.add(name);
			}
		});
		assertThat(used).as("indexes of populated %s partitions read by %s — it must walk %s and nothing else%n%s",
				table, what, indexes, facts.plan()).isNotEmpty().isSubsetOf(family);
		assertThat(facts.ran("Sort")).as("%s sorts rather than walking %s in order%n%s", what, indexes, facts.plan())
			.isFalse();
	}

	/**
	 * A ceiling above the cost of simply reading the table cannot fail, whatever the
	 * plan does. Every enabled ceiling has to clear this or it is decoration.
	 */
	static void assertCeilingCanFail(JdbcClient jdbc, long ceiling, String table) {
		long fullScan = fullScanCost(jdbc, table);
		assertThat(ceiling).as("ceiling %d vs the %d blocks a full scan of %s costs on this dataset", ceiling, fullScan,
				table).isLessThan(fullScan);
	}

	/**
	 * What reading {@code table} end to end costs on this dataset. An unregistered
	 * table is rejected rather than scanned: {@code count(null)} is answerable, and
	 * a zero here would make {@link #assertCeilingCanFail} blame the ceiling for a
	 * missing map entry.
	 */
	static long fullScanCost(JdbcClient jdbc, String table) {
		String column = FULL_SCAN_COLUMNS.get(table);
		if (column == null) {
			throw new IllegalArgumentException(
					"no full-scan column registered for " + table + "; known tables are " + FULL_SCAN_COLUMNS.keySet());
		}
		return PlanFacts.explain(jdbc, "SELECT count(" + column + ") FROM " + table, List.of()).logicalIo();
	}

	private static boolean exists(JdbcClient jdbc, String index) {
		return jdbc.sql("""
				SELECT count(*) FROM pg_class c JOIN pg_index x ON x.indexrelid = c.oid WHERE c.relname = ?
				""").param(index).query(Long.class).single() > 0;
	}

	/**
	 * A partitioned index and every per-partition child Postgres created under it.
	 * The parent itself is included so the same helper works for an unpartitioned
	 * table, where there are no children.
	 */
	private static Set<String> indexFamily(JdbcClient jdbc, String index) {
		Set<String> family = new LinkedHashSet<>(List.of(index));
		family.addAll(jdbc.sql("""
				SELECT c.relname FROM pg_inherits i
				JOIN pg_class c ON c.oid = i.inhrelid
				JOIN pg_class p ON p.oid = i.inhparent
				WHERE p.relname = ?
				""").param(index).query(String.class).list());
		return family;
	}

	/** Every index on {@code table} or any of its partitions, mapped to the relation it indexes. */
	private static Map<String, String> indexesOf(JdbcClient jdbc, String table) {
		Map<String, String> indexes = new LinkedHashMap<>();
		jdbc.sql("""
				SELECT c.relname AS index_name, t.relname AS table_name FROM pg_index x
				JOIN pg_class c ON c.oid = x.indexrelid
				JOIN pg_class t ON t.oid = x.indrelid
				WHERE t.relname = ? OR t.relname LIKE ?
				""")
			.param(table)
			.param(table + "\\_p%")
			.query((rs, i) -> Map.entry(rs.getString("index_name"), rs.getString("table_name")))
			.list()
			.forEach(entry -> indexes.put(entry.getKey(), entry.getValue()));
		return indexes;
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
