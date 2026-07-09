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
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The ingest worker pool (§6.1): drains the queue in batches (≤ 500 items or
 * 1 s linger), runs the per-signal pipeline, and hands batches to the stores.
 * A failing item is logged and dropped — ingest must never wedge on bad input.
 */
@Component
public class IngestWorkers implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(IngestWorkers.class);

	private final IngestQueue queue;
	private final ErrorPipeline pipeline;
	private final EventStore store;
	private final LogPipeline logPipeline;
	private final LogStore logStore;
	private final TransactionPipeline transactionPipeline;
	private final TransactionStore transactionStore;
	private final int workerCount;
	private final int maxBatch;
	private final long lingerMillis;
	private final List<Thread> workers = new ArrayList<>();
	private volatile boolean running;

	public IngestWorkers(IngestQueue queue, ErrorPipeline pipeline, EventStore store, LogPipeline logPipeline,
			LogStore logStore, TransactionPipeline transactionPipeline, TransactionStore transactionStore,
			@Value("${outpost.ingest.workers:2}") int workerCount,
			@Value("${outpost.ingest.max-batch:500}") int maxBatch,
			@Value("${outpost.ingest.linger-millis:1000}") long lingerMillis) {
		this.queue = queue;
		this.pipeline = pipeline;
		this.store = store;
		this.logPipeline = logPipeline;
		this.logStore = logStore;
		this.transactionPipeline = transactionPipeline;
		this.transactionStore = transactionStore;
		this.workerCount = workerCount;
		this.maxBatch = maxBatch;
		this.lingerMillis = lingerMillis;
	}

	@Override
	public void start() {
		running = true;
		for (int i = 0; i < workerCount; i++) {
			Thread worker = new Thread(this::drainLoop, "ingest-worker-" + i);
			worker.setDaemon(true);
			worker.start();
			workers.add(worker);
		}
	}

	private void drainLoop() {
		while (running) {
			try {
				List<IngestItem> batch = queue.nextBatch(maxBatch, lingerMillis);
				if (batch.isEmpty()) {
					continue;
				}
				List<ProcessedEvent> events = new ArrayList<>(batch.size());
				List<ProcessedLog> logs = new ArrayList<>();
				List<ProcessedTransaction> transactions = new ArrayList<>();
				for (IngestItem item : batch) {
					try {
						switch (item) {
							case IngestItem.ErrorEvent event -> events.add(pipeline.process(event));
							case IngestItem.LogBatch logBatch -> logs.addAll(logPipeline.process(logBatch));
							case IngestItem.TransactionEvent txn -> transactions.add(transactionPipeline.process(txn));
						}
					}
					catch (RuntimeException e) {
						log.warn("dropping unprocessable item for project {}: {}", item.projectId(), e.toString());
					}
				}
				store.store(events);
				logStore.store(logs);
				transactionStore.store(transactions);
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

	@Override
	public void stop() {
		running = false;
		for (Thread worker : workers) {
			worker.interrupt();
		}
		for (Thread worker : workers) {
			try {
				worker.join(2000);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		workers.clear();
	}

	@Override
	public boolean isRunning() {
		return running;
	}
}
