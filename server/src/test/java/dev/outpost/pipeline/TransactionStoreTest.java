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

/** See {@link LogStoreTest} — the same round-trip budget applies here (#107). */
class TransactionStoreTest {

	private static final Instant START = Instant.parse("2026-07-27T12:00:00Z");

	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

	private final PartitionManager partitions = mock(PartitionManager.class);

	private final ObjectMapper mapper = new ObjectMapper();

	private final TransactionStore store = new TransactionStore(jdbc, transactionManager(), partitions, mapper,
			new IngestMetrics(new SimpleMeterRegistry()), new TelemetryOrigins(jdbc));

	@Test
	void upsertsTheEnvironmentAndReleaseOncePerBatch() {
		store.store(batch(200));

		verify(jdbc, times(1)).update(contains("INTO environment"), eq(1L), eq("prod"));
		verify(jdbc, times(1)).update(contains("INTO release"), eq(1L), eq("shop@1.0.0"));
		verify(jdbc).batchUpdate(contains("INTO txn"), any(List.class));
	}

	@Test
	void ensuresTxnAndSpanPartitionsInOneCallEach() {
		store.store(batch(200));

		verify(partitions, times(1)).ensurePartitions(eq(PartitionManager.TXN), any(Collection.class));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<Instant>> spanStarts = ArgumentCaptor.forClass(Collection.class);
		verify(partitions, times(1)).ensurePartitions(eq(PartitionManager.SPAN), spanStarts.capture());
		// Spans can straddle the root's week boundary, so their own starts count too.
		assertThat(spanStarts.getValue()).hasSize(400);
	}

	private List<ProcessedTransaction> batch(int size) {
		return IntStream.range(0, size).mapToObj(i -> {
			UUID txnId = UUID.randomUUID();
			Instant start = START.plusSeconds(i);
			ProcessedSpan span = new ProcessedSpan(UUID.randomUUID(), txnId, 1L, "trace", "span", null, "db",
					"SELECT 1", start, start.plusMillis(5), 5.0, "ok", mapper.createObjectNode());
			return new ProcessedTransaction(txnId, 1L, "prod", "shop@1.0.0", "trace", "span", null, "GET /", "http",
					start, start.plusMillis(10), 10.0, "ok", mapper.createObjectNode(), List.of(span));
		}).toList();
	}

	private static PlatformTransactionManager transactionManager() {
		PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
		when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		return manager;
	}
}
