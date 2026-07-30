package dev.outpost.ingest;

import dev.outpost.pipeline.ErrorPipeline;
import dev.outpost.pipeline.EventStore;
import dev.outpost.pipeline.LogPipeline;
import dev.outpost.pipeline.LogStore;
import dev.outpost.pipeline.ProcessedEvent;
import dev.outpost.pipeline.ProcessedLog;
import dev.outpost.pipeline.ProcessedTransaction;
import dev.outpost.pipeline.TransactionPipeline;
import dev.outpost.pipeline.TransactionStore;
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
 * The ingest worker pool: drains the queue in batches (≤ 500 items or
 * 1 s linger), runs the per-signal pipeline, and hands batches to the stores.
 * A failing item is logged and dropped — ingest must never wedge on bad input.
 */
@Component
public class IngestWorkers implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(IngestWorkers.class);
	private static final int LIFECYCLE_PHASE = 2_147_481_598;

	private final IngestQueue queue;
	private final ErrorPipeline pipeline;
	private final EventStore store;
	private final LogPipeline logPipeline;
	private final LogStore logStore;
	private final TransactionPipeline transactionPipeline;
	private final TransactionStore transactionStore;
	private final IngestMetrics metrics;
	private final int workerCount;
	private final int maxBatch;
	private final long lingerMillis;
	private final Duration shutdownTimeout;
	private final List<Thread> workers = new ArrayList<>();
	private volatile boolean running;

	public IngestWorkers(IngestQueue queue, ErrorPipeline pipeline, EventStore store, LogPipeline logPipeline,
			LogStore logStore, TransactionPipeline transactionPipeline, TransactionStore transactionStore,
			IngestMetrics metrics, @Value("${outpost.ingest.workers:2}") int workerCount,
			@Value("${outpost.ingest.max-batch:500}") int maxBatch,
			@Value("${outpost.ingest.linger-millis:1000}") long lingerMillis,
			@Value("${outpost.ingest.shutdown-timeout:25s}") Duration shutdownTimeout) {
		this.queue = queue;
		this.pipeline = pipeline;
		this.store = store;
		this.logPipeline = logPipeline;
		this.logStore = logStore;
		this.transactionPipeline = transactionPipeline;
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
				List<IngestItem> batch = queue.nextBatch(maxBatch, lingerMillis);
				if (batch.isEmpty()) {
					continue;
				}
				try {
					metrics.batchDrained(batch.size());
					List<ProcessedEvent> events = new ArrayList<>(batch.size());
					List<ProcessedLog> logs = new ArrayList<>();
					List<ProcessedTransaction> transactions = new ArrayList<>();
					Instant dequeuedAt = Instant.now();
					for (IngestItem item : batch) {
						IngestMetrics.Signal signal = IngestMetrics.Signal.of(item);
						// Measured before processing: this is buffer dwell time, the delay
						// the SDK cannot see and the one that precedes a 429.
						metrics.queueWait(signal, item.receivedAt(), dequeuedAt);
						long startedAt = System.nanoTime();
						try {
							switch (item) {
								case IngestItem.ErrorEvent event -> events.add(pipeline.process(event));
								case IngestItem.LogBatch logBatch -> logs.addAll(logPipeline.process(logBatch));
								case IngestItem.TransactionEvent txn ->
									transactions.add(transactionPipeline.process(txn));
							}
							metrics.processed(signal, System.nanoTime() - startedAt);
						}
						catch (RuntimeException e) {
							metrics.dropped(IngestMetrics.DropStage.PIPELINE);
							log.warn("dropping unprocessable item for project {}: {}", item.projectId(), e.toString());
						}
					}
					storeTimed(IngestMetrics.Signal.ERROR, events, store::store);
					storeTimed(IngestMetrics.Signal.LOG, logs, logStore::store);
					storeTimed(IngestMetrics.Signal.TRANSACTION, transactions, transactionStore::store);
				}
				finally {
					queue.completed(batch.size());
				}
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			catch (RuntimeException e) {
				// The stores already degrade to per-row storage; anything reaching
				// here is unexpected — log and keep the worker alive.
				log.error("ingest worker iteration failed", e);
			}
		}
	}

	/**
	 * Times a store call, skipping empty lists so a mixed batch doesn't record a
	 * near-zero sample for the signals it didn't carry.
	 */
	private <T> void storeTimed(IngestMetrics.Signal signal, List<T> batch, Consumer<List<T>> store) {
		if (batch.isEmpty()) {
			return;
		}
		long startedAt = System.nanoTime();
		store.accept(batch);
		metrics.stored(signal, System.nanoTime() - startedAt);
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
