package dev.outpost.ingest;

import dev.outpost.pipeline.ErrorPipeline;
import dev.outpost.pipeline.EventStore;
import dev.outpost.pipeline.ProcessedEvent;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The ingest worker pool (§6.1): drains the queue in batches (≤ 500 items or
 * 1 s linger), runs the error pipeline, and hands batches to the store. A
 * failing item is logged and dropped — ingest must never wedge on bad input.
 */
@Component
public class IngestWorkers implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(IngestWorkers.class);

	private final IngestQueue queue;
	private final ErrorPipeline pipeline;
	private final EventStore store;
	private final int workerCount;
	private final int maxBatch;
	private final long lingerMillis;
	private final List<Thread> workers = new ArrayList<>();
	private volatile boolean running;

	public IngestWorkers(IngestQueue queue, ErrorPipeline pipeline, EventStore store,
			@Value("${outpost.ingest.workers:2}") int workerCount,
			@Value("${outpost.ingest.max-batch:500}") int maxBatch,
			@Value("${outpost.ingest.linger-millis:1000}") long lingerMillis) {
		this.queue = queue;
		this.pipeline = pipeline;
		this.store = store;
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
				List<ProcessedEvent> processed = new ArrayList<>(batch.size());
				for (IngestItem item : batch) {
					try {
						processed.add(pipeline.process(item));
					}
					catch (RuntimeException e) {
						log.warn("dropping unprocessable event for project {}: {}", item.projectId(), e.toString());
					}
				}
				store.store(processed);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			catch (RuntimeException e) {
				// EventStore already degrades to per-event storage; anything reaching
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
