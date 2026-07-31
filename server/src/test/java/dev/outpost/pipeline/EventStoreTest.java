package dev.outpost.pipeline;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.outpost.db.PartitionManager;
import dev.outpost.ingest.IngestMetrics;
import dev.outpost.notifications.NotificationPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * See {@link LogStoreTest} — and here the round trips are spent under the
 * per-project advisory lock, which the benchmark shows is the error path's
 * ceiling (#107).
 */
class EventStoreTest {

	private static final Instant TIMESTAMP = Instant.parse("2026-07-27T12:00:00Z");

	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

	private final PartitionManager partitions = mock(PartitionManager.class);

	private final ObjectMapper mapper = new ObjectMapper();

	private final EventStore store = new EventStore(jdbc, transactionManager(), partitions, mapper,
			mock(EventIssueLock.class), mock(NotificationPublisher.class), new IngestMetrics(new SimpleMeterRegistry()),
			new TelemetryOrigins(jdbc));

	@BeforeEach
	@SuppressWarnings("unchecked")
	void stubIssueUpsert() {
		when(jdbc.queryForObject(contains("INTO issue"), any(RowMapper.class), any(Object[].class)))
			.thenAnswer(invocation -> {
				RowMapper<?> rowMapper = invocation.getArgument(1);
				ResultSet rs = mock(ResultSet.class);
				when(rs.getLong("id")).thenReturn(7L);
				when(rs.getBoolean("inserted")).thenReturn(false);
				return rowMapper.mapRow(rs, 1);
			});
	}

	@Test
	void upsertsTheEnvironmentAndReleaseOncePerProjectBatch() {
		store.store(batch(500));

		verify(jdbc, times(1)).update(contains("INTO environment"), eq(1L), eq("prod"));
		verify(jdbc, times(1)).update(contains("INTO release"), eq(1L), eq("shop@1.0.0"));
		verify(jdbc).batchUpdate(contains("INTO event"), any(List.class));
	}

	@Test
	void ensuresThePartitionForEveryEventsWeekInOneCall() {
		store.store(batch(500));

		verify(partitions, times(1)).ensurePartitions(eq(PartitionManager.EVENT), any(Collection.class));
	}

	private List<ProcessedEvent> batch(int size) {
		return IntStream.range(0, size)
			.mapToObj(i -> new ProcessedEvent(UUID.randomUUID(), 1L, "prod", "shop@1.0.0", TIMESTAMP.plusSeconds(i),
					null, "error", "boom", "RuntimeException", "boom", "app.main", "fingerprint", null,
					mapper.createObjectNode(), new byte[0], "none"))
			.toList();
	}

	private static PlatformTransactionManager transactionManager() {
		PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
		when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		return manager;
	}
}
