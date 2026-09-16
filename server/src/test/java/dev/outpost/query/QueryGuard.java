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
 * Assertion vocabulary shared by the retrieval guards, machine-independent
 * (logical I/O and plan shape, never wall clock) so a loaded CI box cannot flake
 * them. Calibrate a ceiling at ten times healthy measured I/O, confirmed by
 * {@link #assertCeilingCanFail} to sit below the cost of a full scan.
 */
final class QueryGuard {

	/** The partitioned tables; a sequential scan of a populated one is the failure these guards catch. */
	static final List<String> TELEMETRY_TABLES = PartitionManager.TABLES;

	/**
	 * Per table, a column no index covers, so aggregating it forces the heap read
	 * that makes {@link #fullScanCost} an honest upper bound. If a future index ever
	 * covers one of these columns, pick a different one rather than keeping the
	 * smaller number.
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
	 * far larger than the page — the signature of an aggregate the pagination
	 * cannot push down.
	 */
	static void assertNoTempFiles(PlanFacts facts, String what) {
		assertThat(facts.tempBlocks()).as("temp-file blocks for %s — a normal page should sort in memory%n%s", what,
				facts.plan()).isZero();
	}

	/**
	 * No sequential scan of a partition holding a material share of its table.
	 * Partitions at the edge of the retention window are excluded, since reading a
	 * near-empty one end to end is the cheapest plan available for it.
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
	 * Every index this plan read on a populated partition of {@code table} belongs
	 * to the family of {@code indexes} (the parent plus its {@code pg_inherits}
	 * children), and no {@code Sort} ran — {@code Incremental Sort} is allowed since
	 * it is bounded by the group size rather than the table. The assertion is
	 * containment, not intersection: every index touched must be in the family, not
	 * just one of them, or a plan walking the wrong index on some partitions would
	 * pass.
	 */
	static void assertWalksIndex(JdbcClient jdbc, PlanFacts facts, String table, List<String> indexes, String what) {
		assertReadsOnlyIndex(jdbc, facts, table, indexes, what);
		assertThat(facts.ran("Sort")).as("%s sorts rather than walking %s in order%n%s", what, indexes, facts.plan())
			.isFalse();
	}

	/**
	 * Every index this plan read on a populated partition of {@code table} belongs
	 * to one of {@code indexes} — {@link #assertWalksIndex} without the ban on a
	 * {@code Sort}. Use this for a selective lookup, which is expected to fetch its
	 * few matches through a bitmap and sort them rather than walk in order.
	 */
	static void assertReadsOnlyIndex(JdbcClient jdbc, PlanFacts facts, String table, List<String> indexes,
			String what) {
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
		assertThat(used).as("indexes of populated %s partitions read by %s — it must read %s and nothing else%n%s",
				table, what, indexes, facts.plan()).isNotEmpty().isSubsetOf(family);
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
