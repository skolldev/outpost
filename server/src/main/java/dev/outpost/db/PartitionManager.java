package dev.outpost.db;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
	private final TransactionTemplate transaction;
	private final Set<String> knownPartitions = ConcurrentHashMap.newKeySet();

	public PartitionManager(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
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
