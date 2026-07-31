package dev.outpost.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.outpost.db.PartitionManager;
import dev.outpost.ingest.IngestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * The environment/release upsert and the partition check must cost one round
 * trip per distinct value, not one per record — a drain is up to 50,000 records
 * sharing a handful of environments and one week (#107).
 */
class LogStoreTest {

	private static final Instant TIMESTAMP = Instant.parse("2026-07-27T12:00:00Z");

	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

	private final PartitionManager partitions = mock(PartitionManager.class);

	private final LogTail tail = mock(LogTail.class);

	private final ObjectMapper mapper = new ObjectMapper();

	private final LogStore store = new LogStore(jdbc, transactionManager(), partitions, tail, mapper,
			new IngestMetrics(new SimpleMeterRegistry()), new TelemetryOrigins(jdbc));

	@Test
	void upsertsTheEnvironmentAndReleaseOncePerBatch() {
		store.store(batch(500));

		verify(jdbc, times(1)).update(contains("INTO environment"), eq(1L), eq("prod"));
		verify(jdbc, times(1)).update(contains("INTO release"), eq(1L), eq("shop@1.0.0"));
		verify(jdbc).batchUpdate(contains("INTO log_record"), any(List.class));
	}

	@Test
	void ensuresThePartitionForEveryRecordsWeekInOneCall() {
		store.store(batch(500));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<Instant>> timestamps = ArgumentCaptor.forClass(Collection.class);
		verify(partitions, times(1)).ensurePartitions(eq(PartitionManager.LOG_RECORD), timestamps.capture());
		assertThat(timestamps.getValue()).hasSize(500);
	}

	private List<ProcessedLog> batch(int size) {
		return IntStream.range(0, size)
			.mapToObj(i -> new ProcessedLog(UUID.randomUUID(), 1L, "prod", "shop@1.0.0", TIMESTAMP.plusSeconds(i),
					null, null, "info", null, "hello", mapper.createObjectNode()))
			.toList();
	}

	private static PlatformTransactionManager transactionManager() {
		PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
		when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		return manager;
	}
}
