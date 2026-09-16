package dev.outpost.db;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates weekly range partitions of the partitioned telemetry tables on demand,
 * named {@code <table>_pYYYYMMDD} after their Monday (UTC) start. Creation runs
 * inside a transaction holding a Postgres advisory lock so concurrent ingest
 * workers (or a second replica) never race on DDL.
 */
@Component
public class PartitionManager {

	public static final String EVENT = "event";
	public static final String LOG_RECORD = "log_record";
	public static final String TXN = "txn";
	public static final String SPAN = "span";

	/** Every partitioned table, in one place — a new one is registered by adding it here. */
	public static final List<String> TABLES = List.of(EVENT, LOG_RECORD, TXN, SPAN);
	private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
	private static final long ADVISORY_LOCK_KEY = 727_572_057L; // unique to partition DDL

	private final JdbcClient jdbc;
	private final PlatformTransactionManager transactionManager;
	private final TransactionTemplate transaction;
	private final Set<String> knownPartitions = ConcurrentHashMap.newKeySet();

	public PartitionManager(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transactionManager = transactionManager;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@EventListener(ContextRefreshedEvent.class)
	public void createUpcomingPartitions() {
		LocalDate thisWeek = weekStart(Instant.now());
		for (String table : TABLES) {
			for (int i = -1; i <= 2; i++) {
				ensureWeek(table, thisWeek.plusWeeks(i));
			}
		}
	}

	/** Cheap when the partition is already known; creates it otherwise. */
	public void ensurePartition(String table, Instant timestamp) {
		ensureWeek(table, weekStart(timestamp));
	}

	/**
	 * Ensures the partition of every week {@code timestamps} falls in. Partitions
	 * are weekly, so a batch of 50,000 log records — whose timestamps are all
	 * distinct — still names only one or two weeks; deduping on the raw instant
	 * would collapse nothing (#107).
	 */
	public void ensurePartitions(String table, Collection<Instant> timestamps) {
		for (LocalDate week : weeksOf(timestamps)) {
			ensureWeek(table, week);
		}
	}

	/**
	 * Drops every weekly partition of {@code table} lying entirely before {@code cutoff},
	 * reclaiming disk immediately instead of row-deleting; the boundary partition straddling
	 * {@code cutoff} is left for the caller to prune. {@code lockTimeoutSeconds} bounds the
	 * DROP TABLE's ACCESS EXCLUSIVE wait so a concurrent scan can't block it forever, and
	 * {@code onlyIfEmpty} checks for rows under that same lock so a concurrent insert can
	 * never lose a committed row to the drop.
	 *
	 * @return the number of partitions dropped
	 */
	public int dropExpiredPartitions(String table, Instant cutoff, int lockTimeoutSeconds) {
		return dropExpiredPartitions(table, cutoff, lockTimeoutSeconds, false);
	}

	public int dropExpiredPartitions(String table, Instant cutoff, int lockTimeoutSeconds, boolean onlyIfEmpty) {
		TransactionTemplate dropTransaction = new TransactionTemplate(transactionManager);
		if (lockTimeoutSeconds > 0) {
			dropTransaction.setTimeout(lockTimeoutSeconds);
		}
		String prefix = table + "_p";
		List<String> partitions = childPartitions(table);
		int dropped = 0;
		for (String partition : partitions) {
			LocalDate weekStart = weekStartOf(partition, prefix);
			if (weekStart == null) {
				continue;
			}
			// Compare the range's upper bound, not its start, so current and future partitions stay ineligible.
			Instant upperBound = weekStart.plusWeeks(1).atStartOfDay(ZoneOffset.UTC).toInstant();
			if (upperBound.isAfter(cutoff)) {
				continue;
			}
			boolean droppedPartition = Boolean.TRUE.equals(dropTransaction.execute(status -> {
				jdbc.sql("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")").query(rs -> {});
				if (onlyIfEmpty) {
					jdbc.sql("LOCK TABLE " + partition + " IN ACCESS EXCLUSIVE MODE").update();
					if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + partition + ")").query(Boolean.class).single()) {
						return false;
					}
				}
				jdbc.sql("DROP TABLE IF EXISTS " + partition).update();
				return true;
			}));
			if (droppedPartition) {
				knownPartitions.remove(partition);
				dropped++;
			}
		}
		return dropped;
	}

	/**
	 * The lower bound of the oldest surviving partition of {@code table} — a cheap
	 * catalog read, unlike {@code min("timestamp")} which would scan every partition
	 * — or empty when {@code table} has none. It is filter-blind (reflects when
	 * retention for the whole table started, not per-project) and can precede the
	 * earliest surviving row by up to a week, since it's the partition's bound rather than the row's timestamp.
	 */
	public Optional<Instant> earliestPartitionStart(String table) {
		String prefix = table + "_p";
		return childPartitions(table).stream()
			.map(partition -> weekStartOf(partition, prefix))
			.filter(Objects::nonNull)
			.min(LocalDate::compareTo)
			.map(week -> week.atStartOfDay(ZoneOffset.UTC).toInstant());
	}

	/** Every partition currently attached to {@code table}, by relation name. */
	private List<String> childPartitions(String table) {
		return jdbc.sql("""
				SELECT c.relname
				FROM pg_inherits i
				JOIN pg_class c ON c.oid = i.inhrelid
				JOIN pg_class p ON p.oid = i.inhparent
				WHERE p.relname = ?
				""").param(table).query(String.class).list();
	}

	private static LocalDate weekStartOf(String partition, String prefix) {
		if (!partition.startsWith(prefix)) {
			return null;
		}
		try {
			return LocalDate.parse(partition.substring(prefix.length()), NAME_FORMAT);
		}
		catch (DateTimeParseException e) {
			return null;
		}
	}

	private void ensureWeek(String table, LocalDate weekStart) {
		String partition = table + "_p" + NAME_FORMAT.format(weekStart);
		if (knownPartitions.contains(partition)) {
			return;
		}
		transaction.executeWithoutResult(status -> {
			jdbc.sql("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")").query(rs -> {});
			jdbc.sql("CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')"
					.formatted(partition, table, weekStart, weekStart.plusWeeks(1)))
				.update();
		});
		knownPartitions.add(partition);
	}

	static LocalDate weekStart(Instant timestamp) {
		return timestamp.atZone(ZoneOffset.UTC).toLocalDate().with(DayOfWeek.MONDAY);
	}

	static Set<LocalDate> weeksOf(Collection<Instant> timestamps) {
		Set<LocalDate> weeks = new LinkedHashSet<>();
		for (Instant timestamp : timestamps) {
			weeks.add(weekStart(timestamp));
		}
		return weeks;
	}
}
