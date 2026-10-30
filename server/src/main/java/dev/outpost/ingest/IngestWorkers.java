package dev.outpost.ingest;

import dev.outpost.pipeline.ErrorPipeline;
import dev.outpost.pipeline.EventStore;
import dev.outpost.pipeline.LogPipeline;
import dev.outpost.pipeline.LogStore;
import dev.outpost.pipeline.StreamedSpanPipeline;
import dev.outpost.pipeline.TransactionPipeline;
import dev.outpost.pipeline.TransactionStore;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Drains spool references in batches, parses one envelope at a time, and hands
 * the resulting telemetry to the stores in {@link StoreBatch}es.
 * <p>
 * Two bounds apply. {@code max-batch} caps the envelopes taken off the queue per
 * drain; {@code max-batch-rows} caps the rows any one store call — and the
 * telemetry a worker holds processed — may carry. The second is the one that
 * bounds work: an envelope can carry a single event or a hundred log records.
 * Envelopes are released once every row they produced has been handed to a
 * store, so a long drain does not pin its spool files until the end.
 */
@Component
public class IngestWorkers implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(IngestWorkers.class);
	private static final int LIFECYCLE_PHASE = 2_147_481_598;
	/** Shared with {@link SpoolReaper}, which validates its max age against this. */
	static final String DEFAULT_SHUTDOWN_TIMEOUT = "25s";

	private final IngestQueue queue;
	private final EnvelopeSpool spool;
	private final EnvelopeParser parser;
	private final ClientReportCounters clientReports;
	private final ErrorPipeline pipeline;
	private final EventStore store;
	private final LogPipeline logPipeline;
	private final LogStore logStore;
	private final TransactionPipeline transactionPipeline;
	private final StreamedSpanPipeline spanPipeline;
	private final TransactionStore transactionStore;
	private final IngestMetrics metrics;
	private final int workerCount;
	private final int maxBatch;
	private final int maxBatchRows;
	private final long lingerMillis;
	private final Duration shutdownTimeout;
	private final List<Thread> workers = new ArrayList<>();
	private volatile boolean running;

	public IngestWorkers(IngestQueue queue, EnvelopeSpool spool, EnvelopeParser parser,
			ClientReportCounters clientReports, ErrorPipeline pipeline, EventStore store, LogPipeline logPipeline,
			LogStore logStore, TransactionPipeline transactionPipeline, StreamedSpanPipeline spanPipeline,
			TransactionStore transactionStore, IngestMetrics metrics,
			@Value("${outpost.ingest.workers:2}") int workerCount,
			@Value("${outpost.ingest.max-batch:500}") int maxBatch,
			@Value("${outpost.ingest.max-batch-rows:5000}") int maxBatchRows,
			@Value("${outpost.ingest.linger-millis:1000}") long lingerMillis,
			@Value("${outpost.ingest.shutdown-timeout:" + DEFAULT_SHUTDOWN_TIMEOUT + "}") Duration shutdownTimeout) {
		this.queue = queue;
		this.spool = spool;
		this.parser = parser;
		this.clientReports = clientReports;
		this.pipeline = pipeline;
		this.store = store;
		this.logPipeline = logPipeline;
		this.logStore = logStore;
		this.transactionPipeline = transactionPipeline;
		this.spanPipeline = spanPipeline;
		this.transactionStore = transactionStore;
		this.metrics = metrics;
		this.workerCount = workerCount;
		this.maxBatch = maxBatch;
		this.maxBatchRows = StoreBatch.requireValidBudget(maxBatchRows);
		this.lingerMillis = lingerMillis;
		if (shutdownTimeout.isNegative()) {
			throw new IllegalArgumentException("outpost.ingest.shutdown-timeout must not be negative");
		}
		this.shutdownTimeout = shutdownTimeout;
	}

	@Override
	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		for (int i = 0; i < workerCount; i++) {
			Thread worker = new Thread(this::drainLoop, "ingest-worker-" + i);
			worker.setDaemon(true);
			worker.start();
			workers.add(worker);
		}
	}

	private void drainLoop() {
		while (running || queue.size() > 0) {
			try {
				List<QueuedEnvelope> batch = queue.nextBatch(maxBatch, lingerMillis);
				if (batch.isEmpty()) {
					continue;
				}
				digest(batch);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			catch (RuntimeException e) {
				log.error("ingest worker iteration failed", e);
			}
		}
	}

	void digest(List<QueuedEnvelope> batch) {
		Progress progress = new Progress(batch);
		try {
			metrics.batchDrained(batch.size());
			StoreBatch pending = new StoreBatch(maxBatchRows, flush -> {
				store(flush);
				progress.releaseDigested();
			});
			Instant dequeuedAt = Instant.now();
			for (QueuedEnvelope envelope : batch) {
				try {
					processEnvelope(envelope, dequeuedAt, pending);
				}
				catch (IOException | RuntimeException e) {
					metrics.dropped(IngestMetrics.DropStage.PIPELINE);
					log.warn("dropping unprocessable envelope for project {} from {}: {}", envelope.projectId(),
							envelope.spoolFile().path(), e.toString());
				}
				progress.digested++;
			}
			pending.flush();
		}
		finally {
			progress.releaseRest();
		}
	}

	/**
	 * Where a drain stands: {@code batch[0, released)} is released, and
	 * {@code batch[released, digested)} is digested but has rows awaiting a flush.
	 * The envelope being digested is never released by a flush its own rows set
	 * off, since rows it has yet to produce would not be covered.
	 */
	private final class Progress {

		private final List<QueuedEnvelope> batch;

		private int released;

		private int digested;

		Progress(List<QueuedEnvelope> batch) {
			this.batch = batch;
		}

		void releaseDigested() {
			queue.release(batch.subList(released, digested));
			released = digested;
		}

		void releaseRest() {
			queue.release(batch.subList(released, batch.size()));
			released = batch.size();
		}
	}

	private void store(StoreBatch.Flush flush) {
		metrics.flushed(flush.rows());
		storeTimed(IngestMetrics.Signal.ERROR, flush.events(), store::store);
		storeTimed(IngestMetrics.Signal.LOG, flush.logs(), logStore::store);
		// Streamed segments are transactions, so they are stored (and timed) as such.
		storeTimed(IngestMetrics.Signal.TRANSACTION, flush.transactions(), transactionStore::store);
		storeTimed(IngestMetrics.Signal.SPAN, flush.spans(), transactionStore::storeSpans);
	}

	private void processEnvelope(QueuedEnvelope queued, Instant dequeuedAt, StoreBatch pending) throws IOException {
		List<IngestItem.Attachment> attachments = new ArrayList<>();
		try (InputStream in = spool.open(queued.spoolFile())) {
			parser.parse(in, item -> {
				switch (item.kind()) {
					case ATTACHMENT -> attachments.add(new IngestItem.Attachment(
							item.header().path("filename").asText("attachment"),
							item.header().path("content_type").asText("application/octet-stream"), item.payload()));
					case CLIENT_REPORT -> {
						JsonNode report = parser.parseObjectPayload(item);
						if (report != null) {
							clientReports.record(queued.projectId(), report);
						}
					}
					case EVENT, LOG, TRANSACTION, SPAN, OTHER -> {
					}
				}
			});
		}

		try (InputStream in = spool.open(queued.spoolFile())) {
			parser.parse(in, item -> {
				Envelope.SignalItem signalItem = parser.signalItem(item);
				if (signalItem == null) {
					return;
				}
				JsonNode payload = signalItem.payload();
				IngestItem ingestItem = switch (signalItem.signal()) {
					case ERROR -> new IngestItem.ErrorEvent(queued.projectId(), queued.receivedAt(), payload,
							attachments);
					case LOG -> new IngestItem.LogBatch(queued.projectId(), queued.receivedAt(), payload);
					case TRANSACTION ->
						new IngestItem.TransactionEvent(queued.projectId(), queued.receivedAt(), payload);
					case SPAN -> new IngestItem.SpanBatch(queued.projectId(), queued.receivedAt(), payload);
				};
				process(signalItem.signal(), ingestItem, dequeuedAt, pending);
			});
		}
	}

	/**
	 * Processes one item into {@code pending}. Only the pipeline is timed and
	 * guarded; adding the result can fill {@code pending} and set off a store,
	 * which is timed, and whose failures are counted, as such.
	 */
	private void process(IngestMetrics.Signal signal, IngestItem item, Instant dequeuedAt, StoreBatch pending) {
		metrics.queueWait(signal, item.receivedAt(), dequeuedAt);
		Processing processing = new Processing();
		switch (item) {
			case IngestItem.ErrorEvent event -> processing.run(() -> pipeline.process(event), pending::add);
			case IngestItem.LogBatch logBatch -> inSlices(logBatch, pending, processing, logPipeline::process,
					records -> records.forEach(pending::add));
			case IngestItem.TransactionEvent txn -> processing.run(() -> transactionPipeline.process(txn), pending::add);
			case IngestItem.SpanBatch spanBatch -> inSlices(spanBatch, pending, processing, spanPipeline::process,
					streamed -> {
						streamed.segments().forEach(pending::add);
						streamed.spans().forEach(pending::add);
					});
		}
		if (processing.failure != null) {
			metrics.dropped(IngestMetrics.DropStage.PIPELINE);
			log.warn("dropping unprocessable item for project {}: {}", item.projectId(), processing.failure.toString());
		}
		else {
			metrics.processed(signal, processing.nanos);
		}
	}

	/**
	 * Processes an item array in slices sized to what {@code pending} can still
	 * take. Each entry yields at most one row, so a slice's output fits without
	 * flushing mid-slice: the processed rows a worker holds — pending plus the
	 * slice in hand — never exceed the budget. If a slice fails, the rest of the
	 * item is dropped; the slices before it are already pending and are stored.
	 */
	private <S extends IngestItem.ItemArray<S>, T> void inSlices(S item, StoreBatch pending, Processing processing,
			Function<S, T> pipeline, Consumer<T> sink) {
		int count = item.itemCount();
		if (count <= pending.capacity()) {
			processing.run(() -> pipeline.apply(item), sink);
			return;
		}
		for (int from = 0; from < count && processing.failure == null;) {
			int to = Math.min(count, from + pending.capacity());
			S slice = item.slice(from, to);
			processing.run(() -> pipeline.apply(slice), sink);
			from = to;
		}
	}

	/** Pipeline time and the first pipeline failure across one item's slices. */
	private static final class Processing {

		private long nanos;

		private RuntimeException failure;

		<T> void run(Supplier<T> pipeline, Consumer<T> sink) {
			T processed;
			long startedAt = System.nanoTime();
			try {
				processed = pipeline.get();
			}
			catch (RuntimeException e) {
				failure = e;
				return;
			}
			finally {
				nanos += System.nanoTime() - startedAt;
			}
			sink.accept(processed);
		}
	}

	private <T> void storeTimed(IngestMetrics.Signal signal, List<T> batch, Consumer<List<T>> store) {
		if (batch.isEmpty()) {
			return;
		}
		long startedAt = System.nanoTime();
		try {
			store.accept(batch);
			metrics.stored(signal, System.nanoTime() - startedAt);
		}
		catch (RuntimeException e) {
			metrics.dropped(IngestMetrics.DropStage.STORE, batch.size());
			log.warn("dropping {} {} item(s) after store failure: {}", batch.size(), signal, e.toString());
		}
	}

	@Override
	public synchronized void stop() {
		running = false;
		long deadline = System.nanoTime() + shutdownTimeout.toNanos();
		boolean interrupted = false;
		for (Thread worker : workers) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				break;
			}
			try {
				worker.join(Duration.ofNanos(remaining));
			}
			catch (InterruptedException e) {
				interrupted = true;
				break;
			}
		}
		int residual = queue.outstanding();
		if (residual > 0) {
			log.warn("ingest shutdown timed out with {} item{} still queued or in flight", residual,
					residual == 1 ? "" : "s");
		}
		for (Thread worker : workers) {
			if (worker.isAlive()) {
				worker.interrupt();
			}
		}
		queue.releaseRemaining();
		workers.clear();
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public int getPhase() {
		return LIFECYCLE_PHASE;
	}
}
