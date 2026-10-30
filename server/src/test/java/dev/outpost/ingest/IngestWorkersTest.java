package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.outpost.pipeline.ErrorPipeline;
import dev.outpost.pipeline.EventStore;
import dev.outpost.pipeline.LogPipeline;
import dev.outpost.pipeline.LogStore;
import dev.outpost.pipeline.ProcessedEvent;
import dev.outpost.pipeline.ProcessedLog;
import dev.outpost.pipeline.ProcessedSpan;
import dev.outpost.pipeline.ProcessedTransaction;
import dev.outpost.pipeline.StreamedSpanPipeline;
import dev.outpost.pipeline.TransactionPipeline;
import dev.outpost.pipeline.TransactionStore;
import dev.outpost.support.EnvelopeFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class IngestWorkersTest {

	private static final byte[] EVENT_ENVELOPE = """
			{}
			{"type":"event"}
			{"event_id":"0123456789abcdef0123456789abcdef"}
			""".getBytes(StandardCharsets.UTF_8);

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final IngestMetrics metrics = new IngestMetrics(registry);
	// Field order matters: spool must be initialized before queue, which releases through it.
	private final EnvelopeSpool spool = mock(EnvelopeSpool.class);
	private final IngestQueue queue = new IngestQueue(10, metrics, spool);
	private final ErrorPipeline pipeline = mock(ErrorPipeline.class);
	private final EventStore store = mock(EventStore.class);
	private final SpoolFile spoolFile = new SpoolFile(Path.of("envelope.spool"), false);
	private final LogStore logStore = mock(LogStore.class);
	private final TransactionStore transactionStore = mock(TransactionStore.class);
	private final LogPipeline logPipeline = spy(new LogPipeline());
	private final EnvelopeFactory envelopes = new EnvelopeFactory();
	/** Every store call, in order, as the rows it was handed. */
	private final List<StoreCall> storeCalls = new ArrayList<>();

	private record StoreCall(String signal, int rows, List<Long> projects) {
	}

	@Test
	void parseFailureStillRemovesSpoolFile() throws IOException, InterruptedException {
		EnvelopeParser parser = mock(EnvelopeParser.class);
		when(spool.open(spoolFile)).thenThrow(new IOException("read failed"));
		QueuedEnvelope envelope = enqueue();

		workers(parser).digest(queue.nextBatch(10, 0));

		verify(spool).delete(envelope.spoolFile());
		assertThat(queue.outstanding()).isZero();
	}

	@Test
	void storeFailureStillRemovesSpoolFileAndRecordsTheDrop() throws IOException, InterruptedException {
		EnvelopeParser parser = new EnvelopeParser(new ObjectMapper());
		when(spool.open(spoolFile)).thenAnswer(invocation -> new ByteArrayInputStream(EVENT_ENVELOPE));
		when(pipeline.process(any(IngestItem.ErrorEvent.class))).thenReturn(mock(ProcessedEvent.class));
		doThrow(new IllegalStateException("database unavailable"))
			.when(store)
			.store(any());
		QueuedEnvelope envelope = enqueue();

		workers(parser).digest(queue.nextBatch(10, 0));

		verify(spool).delete(envelope.spoolFile());
		assertThat(queue.outstanding()).isZero();
		assertThat(registry.get("outpost.ingest.dropped").tag("stage", "store").counter().count()).isEqualTo(1);
	}

	@Test
	void aLogItemLargerThanTheRowBudgetIsProcessedAndStoredInBudgetSizedSlices() throws Exception {
		recordStoreCalls();
		enqueue(42, "logs.spool", envelopes.log("prod", 25));

		budgeted(10).digest(queue.nextBatch(10, 0));

		assertThat(storeCalls).extracting(StoreCall::rows).containsExactly(10, 10, 5);
		// The bound holds before storage too: the pipeline never sees more records than one batch may hold.
		verify(logPipeline, times(3)).process(argThat(batch -> batch.payload().path("items").size() <= 10));
	}

	@Test
	void slicesAreSizedToWhatTheBatchCanStillTakeSoHeldRowsNeverExceedTheBudget() throws Exception {
		recordStoreCalls();
		when(pipeline.process(any(IngestItem.ErrorEvent.class))).thenAnswer(invocation -> event(42));
		enqueue(42, "error.spool", envelopes.error("prod"));
		enqueue(42, "logs.spool", envelopes.log("prod", 25));
		List<Integer> sliceSizes = new ArrayList<>();
		doAnswer(invocation -> {
			sliceSizes.add(invocation.<IngestItem.LogBatch>getArgument(0).itemCount());
			return invocation.callRealMethod();
		}).when(logPipeline).process(any());

		budgeted(10).digest(queue.nextBatch(10, 0));

		// One event already pending leaves room for 9; after that flush each slice gets the full budget.
		assertThat(sliceSizes).containsExactly(9, 10, 6);
		assertThat(storeCalls).extracting(StoreCall::signal, StoreCall::rows)
			.containsExactly(tuple("error", 1), tuple("log", 9), tuple("log", 10), tuple("log", 6));
	}

	@Test
	void noStoreCallExceedsTheRowBudgetAcrossMixedSignalsAndProjects() throws Exception {
		recordStoreCalls();
		when(pipeline.process(any(IngestItem.ErrorEvent.class)))
			.thenAnswer(invocation -> event(invocation.<IngestItem.ErrorEvent>getArgument(0).projectId()));
		enqueue(1, "error-1.spool", envelopes.error("prod"));
		enqueue(2, "logs-2.spool", envelopes.log("prod", 30));
		enqueue(1, "txn-1.spool", envelopes.transaction("prod", 40));
		enqueue(2, "spans-2.spool", envelopes.streamedSpans("prod", 12));
		enqueue(1, "logs-1.spool", envelopes.log("prod", 7));

		budgeted(16).digest(queue.nextBatch(10, 0));

		assertThat(storeCalls).allSatisfy(call -> assertThat(call.rows()).isLessThanOrEqualTo(16));
		Map<String, Integer> rows = storeCalls.stream()
			.collect(Collectors.groupingBy(StoreCall::signal, Collectors.summingInt(StoreCall::rows)));
		// 1 event; 37 log records; 1 txn + 40 spans and 1 streamed segment + 12 streamed spans.
		assertThat(rows).containsEntry("error", 1).containsEntry("log", 37);
		assertThat(rows.get("transaction") + rows.get("span")).isEqualTo(54);
		assertThat(storeCalls.stream().flatMap(call -> call.projects().stream()).distinct()).containsOnly(1L, 2L);
		assertThat(queue.outstanding()).isZero();
	}

	@Test
	void envelopesAreReleasedAsTheirRowsAreStoredNotAtTheEndOfTheDrain() throws Exception {
		SpoolFile first = enqueue(42, "first.spool", envelopes.log("prod", 10)).spoolFile();
		enqueue(42, "second.spool", envelopes.log("prod", 10));
		SpoolFile third = enqueue(42, "third.spool", envelopes.log("prod", 10)).spoolFile();

		budgeted(10).digest(queue.nextBatch(10, 0));

		InOrder order = inOrder(spool, logStore);
		order.verify(logStore).store(any());
		order.verify(spool).delete(first);
		// Each envelope is read twice (attachments first), so any read of the third will do.
		order.verify(spool, atLeastOnce()).open(third);
		assertThat(queue.outstanding()).isZero();
	}

	@Test
	void anEnvelopeStillBeingDigestedIsNotReleasedByAFlushInsideIt() throws Exception {
		SpoolFile large = enqueue(42, "large.spool", envelopes.log("prod", 25)).spoolFile();

		budgeted(10).digest(queue.nextBatch(10, 0));

		InOrder order = inOrder(spool, logStore);
		order.verify(logStore, times(3)).store(any());
		order.verify(spool).delete(large);
	}

	private void recordStoreCalls() {
		doAnswer(invocation -> record("error", invocation.<List<ProcessedEvent>>getArgument(0),
				events -> events.stream().map(ProcessedEvent::projectId).toList(), List::size))
			.when(store)
			.store(any());
		doAnswer(invocation -> record("log", invocation.<List<ProcessedLog>>getArgument(0),
				logs -> logs.stream().map(ProcessedLog::projectId).toList(), List::size))
			.when(logStore)
			.store(any());
		doAnswer(invocation -> record("transaction", invocation.<List<ProcessedTransaction>>getArgument(0),
				txns -> txns.stream().map(ProcessedTransaction::projectId).toList(),
				txns -> txns.stream().mapToInt(txn -> 1 + txn.spans().size()).sum()))
			.when(transactionStore)
			.store(any());
		doAnswer(invocation -> record("span", invocation.<List<ProcessedSpan>>getArgument(0),
				spans -> spans.stream().map(ProcessedSpan::projectId).toList(), List::size))
			.when(transactionStore)
			.storeSpans(any());
	}

	private <T> Object record(String signal, List<T> batch, Function<List<T>, List<Long>> projects,
			ToIntFunction<List<T>> rows) {
		storeCalls.add(new StoreCall(signal, rows.applyAsInt(batch), projects.apply(batch)));
		return null;
	}

	private static ProcessedEvent event(long projectId) {
		return new ProcessedEvent(UUID.randomUUID(), projectId, "production", null, Instant.now(), null,
				"error", "m", null, "t", null, "fp", null, null, null, null);
	}

	private QueuedEnvelope enqueue() {
		QueuedEnvelope envelope = new QueuedEnvelope(42, Instant.now(), spoolFile);
		assertThat(queue.offer(envelope)).isTrue();
		return envelope;
	}

	private QueuedEnvelope enqueue(long projectId, String name, String body) throws IOException {
		SpoolFile file = new SpoolFile(Path.of(name), false);
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		when(spool.open(file)).thenAnswer(invocation -> new ByteArrayInputStream(bytes));
		QueuedEnvelope envelope = new QueuedEnvelope(projectId, Instant.now(), file);
		assertThat(queue.offer(envelope)).isTrue();
		return envelope;
	}

	private IngestWorkers workers(EnvelopeParser parser) {
		return new IngestWorkers(queue, spool, parser, mock(ClientReportCounters.class), pipeline, store,
				mock(LogPipeline.class), mock(LogStore.class), mock(TransactionPipeline.class),
				mock(StreamedSpanPipeline.class), mock(TransactionStore.class), metrics, 0, 10, 5000, 0,
				Duration.ZERO);
	}

	/** Real log, transaction and span pipelines, so the rows they produce are the ones budgeted. */
	private IngestWorkers budgeted(int maxBatchRows) {
		return new IngestWorkers(queue, spool, new EnvelopeParser(new ObjectMapper()), mock(ClientReportCounters.class),
				pipeline, store, logPipeline, logStore, new TransactionPipeline(), new StreamedSpanPipeline(),
				transactionStore, metrics, 0, 10, maxBatchRows, 0, Duration.ZERO);
	}
}
