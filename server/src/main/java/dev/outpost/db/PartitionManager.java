package dev.outpost.db;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates weekly range partitions of the {@code event} table on demand.
 * Partitions are named {@code event_pYYYYMMDD} after their Monday (UTC) start.
 * Creation runs inside a transaction holding a Postgres advisory lock so
 * concurrent ingest workers (or a second replica) never race on DDL.
 */
@Component
public class PartitionManager {

	private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
	private static final long ADVISORY_LOCK_KEY = 727_572_057L; // unique to partition DDL

	private final JdbcClient jdbc;
	private final TransactionTemplate transaction;
	private final Set<LocalDate> knownWeeks = ConcurrentHashMap.newKeySet();

	public PartitionManager(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@EventListener(ContextRefreshedEvent.class)
	public void createUpcomingPartitions() {
		LocalDate thisWeek = weekStart(Instant.now());
		for (int i = -1; i <= 2; i++) {
			ensureWeek(thisWeek.plusWeeks(i));
		}
	}

	/** Ensures the partition covering {@code timestamp} exists. Cheap when already known. */
	public void ensurePartition(Instant timestamp) {
		ensureWeek(weekStart(timestamp));
	}

	private void ensureWeek(LocalDate weekStart) {
		if (knownWeeks.contains(weekStart)) {
			return;
		}
		String partition = "event_p" + NAME_FORMAT.format(weekStart);
		transaction.executeWithoutResult(status -> {
			jdbc.sql("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")").query(rs -> {});
			jdbc.sql("CREATE TABLE IF NOT EXISTS %s PARTITION OF event FOR VALUES FROM ('%s') TO ('%s')"
					.formatted(partition, weekStart, weekStart.plusWeeks(1)))
				.update();
		});
		knownWeeks.add(weekStart);
	}

	static LocalDate weekStart(Instant timestamp) {
		return timestamp.atZone(ZoneOffset.UTC).toLocalDate().with(DayOfWeek.MONDAY);
	}
}
