package dev.outpost.ingest;

import dev.outpost.pipeline.ErrorPipeline;
import dev.outpost.pipeline.EventStore;
import dev.outpost.pipeline.LogPipeline;
import dev.outpost.pipeline.LogStore;
import dev.outpost.pipeline.ProcessedEvent;
import dev.outpost.pipeline.ProcessedLog;
import dev.outpost.pipeline.ProcessedSpan;
import dev.outpost.pipeline.ProcessedTransaction;
import dev.outpost.pipeline.StreamedSpanPipeline;
import dev.outpost.pipeline.StreamedSpans;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Drains spool references in batches, parses one envelope at a time, and hands
 * the resulting signal batches to their stores.
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

	/** Processed telemetry gathered across one drained batch, stored per signal at the end. */
	private record Collected(List<ProcessedEvent> events, List<ProcessedLog> logs,
			List<ProcessedTransaction> transactions, List<ProcessedSpan> spans) {
	}

	void digest(List<QueuedEnvelope> batch) {
		try {
			metrics.batchDrained(batch.size());
			Collected collected = new Collected(new ArrayList<>(batch.size()), new ArrayList<>(), new ArrayList<>(),
					new ArrayList<>());
			Instant dequeuedAt = Instant.now();
			for (QueuedEnvelope envelope : batch) {
				try {
					processEnvelope(envelope, dequeuedAt, collected);
				}
				catch (IOException | RuntimeException e) {
					metrics.dropped(IngestMetrics.DropStage.PIPELINE);
					log.warn("dropping unprocessable envelope for project {} from {}: {}", envelope.projectId(),
							envelope.spoolFile().path(), e.toString());
				}
			}
			storeTimed(IngestMetrics.Signal.ERROR, collected.events(), store::store);
			storeTimed(IngestMetrics.Signal.LOG, collected.logs(), logStore::store);
			// Streamed segments are transactions, so they are stored (and timed) as such.
			storeTimed(IngestMetrics.Signal.TRANSACTION, collected.transactions(), transactionStore::store);
			storeTimed(IngestMetrics.Signal.SPAN, collected.spans(), transactionStore::storeSpans);
		}
		finally {
			queue.release(batch);
		}
	}

	private void processEnvelope(QueuedEnvelope queued, Instant dequeuedAt, Collected collected) throws IOException {
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
				process(signalItem.signal(), ingestItem, dequeuedAt, collected);
			});
		}
	}

	private void process(IngestMetrics.Signal signal, IngestItem item, Instant dequeuedAt, Collected collected) {
		metrics.queueWait(signal, item.receivedAt(), dequeuedAt);
		long startedAt = System.nanoTime();
		try {
			switch (item) {
				case IngestItem.ErrorEvent event -> collected.events().add(pipeline.process(event));
				case IngestItem.LogBatch logBatch -> collected.logs().addAll(logPipeline.process(logBatch));
				case IngestItem.TransactionEvent txn ->
					collected.transactions().add(transactionPipeline.process(txn));
				case IngestItem.SpanBatch spanBatch -> {
					StreamedSpans streamed = spanPipeline.process(spanBatch);
					collected.transactions().addAll(streamed.segments());
					collected.spans().addAll(streamed.spans());
				}
			}
			metrics.processed(signal, System.nanoTime() - startedAt);
		}
		catch (RuntimeException e) {
			metrics.dropped(IngestMetrics.DropStage.PIPELINE);
			log.warn("dropping unprocessable item for project {}: {}", item.projectId(), e.toString());
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
