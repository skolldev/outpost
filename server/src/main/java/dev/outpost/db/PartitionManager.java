package dev.outpost.db;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates weekly range partitions of the partitioned telemetry tables on
 * demand. Partitions are named {@code <table>_pYYYYMMDD} after their Monday
 * (UTC) start. Creation runs inside a transaction holding a Postgres advisory
 * lock so concurrent ingest workers (or a second replica) never race on DDL.
 */
@Component
public class PartitionManager {

	public static final String EVENT = "event";
	public static final String LOG_RECORD = "log_record";
	public static final String TXN = "txn";
	public static final String SPAN = "span";

	private static final List<String> TABLES = List.of(EVENT, LOG_RECORD, TXN, SPAN);
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
	 * Drops every weekly partition of {@code table} whose range lies entirely
	 * before {@code cutoff}, reclaiming disk immediately instead of row-deleting.
	 * Runs under the same advisory lock as creation, so it never races
	 * {@link #ensureWeek}. The boundary partition straddling the cutoff still
	 * holds live rows and is left for the caller to prune.
	 * <p>
	 * Each drop runs in a transaction bounded by {@code lockTimeoutSeconds}: a
	 * {@code DROP TABLE} needs an ACCESS EXCLUSIVE lock, which a concurrent
	 * time-unfiltered scan of an old partition (e.g. a trace fan-out by
	 * {@code trace_id}) can hold, so without the bound the daily job could block
	 * indefinitely and pin a connection. On timeout the drop throws (a query
	 * cancellation) so the caller can defer it to the next run.
	 * <p>
	 * With {@code onlyIfEmpty}, a partition is dropped only when it holds no
	 * rows, checked after taking its ACCESS EXCLUSIVE lock so a concurrent
	 * insert either commits before the check (partition is kept) or blocks until
	 * the decision is made — there is no window where a committed row can be
	 * dropped. Used for {@code event}, where rows may only be removed under the
	 * per-project event lock: a stale-timestamped event can land in an expired
	 * week after that pass, and its partition must then survive until the next
	 * run row-deletes it and rebuilds the issue aggregates.
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
		List<String> partitions = jdbc.sql("""
				SELECT c.relname
				FROM pg_inherits i
				JOIN pg_class c ON c.oid = i.inhrelid
				JOIN pg_class p ON p.oid = i.inhparent
				WHERE p.relname = ?
				""").param(table).query(String.class).list();
		int dropped = 0;
		for (String partition : partitions) {
			LocalDate weekStart = weekStartOf(partition, prefix);
			if (weekStart == null) {
				continue;
			}
			// The partition covers [weekStart, weekStart + 1 week); it is fully
			// expired only when that upper bound is at or before the cutoff, so
			// the current and future partitions are never eligible.
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
}
