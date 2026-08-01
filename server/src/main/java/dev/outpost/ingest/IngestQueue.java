package dev.outpost.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The entire "message queue": a bounded in-memory buffer of fixed-size spool
 * references between the envelope endpoint and the digest workers. Full queue
 * → caller responds 429 and the SDKs back off.
 *
 * <p>An accepted entry owns exactly one spool file, so the queue owns releasing
 * it: {@link #release} drops the file and the outstanding count together rather
 * than leaving each caller to remember both.
 */
@Component
public class IngestQueue {

	private final BlockingQueue<QueuedEnvelope> queue;

	private final int capacity;

	private final EnvelopeSpool spool;

	private final AtomicInteger outstanding = new AtomicInteger();

	public IngestQueue(@Value("${outpost.ingest.queue-capacity:50000}") int capacity, IngestMetrics metrics,
			EnvelopeSpool spool) {
		this.queue = new ArrayBlockingQueue<>(capacity);
		this.capacity = capacity;
		this.spool = spool;
		// Depth against capacity is the leading indicator of a 429: by the time
		// the endpoint starts rejecting, depth has been pinned at capacity for a
		// while. Gauged rather than counted so a scrape sees the current buffer.
		metrics.gauge("outpost.ingest.queue.depth", "Items currently buffered", this, IngestQueue::size);
		metrics.gauge("outpost.ingest.queue.capacity", "Maximum items the buffer holds", this,
				IngestQueue::capacity);
	}

	/** Non-blocking; false when the buffer is full (backpressure path). */
	public boolean offer(QueuedEnvelope item) {
		outstanding.incrementAndGet();
		if (queue.offer(item)) {
			return true;
		}
		outstanding.decrementAndGet();
		return false;
	}

	/**
	 * Blocks up to {@code lingerMillis} for a first item, then drains whatever
	 * else is immediately available up to {@code maxBatch}.
	 */
	public List<QueuedEnvelope> nextBatch(int maxBatch, long lingerMillis) throws InterruptedException {
		List<QueuedEnvelope> batch = new ArrayList<>();
		QueuedEnvelope first = queue.poll(lingerMillis, TimeUnit.MILLISECONDS);
		if (first == null) {
			return batch;
		}
		batch.add(first);
		queue.drainTo(batch, maxBatch - 1);
		return batch;
	}

	public int size() {
		return queue.size();
	}

	public int capacity() {
		return capacity;
	}

	/**
	 * Finishes with accepted entries: their spool files go and outstanding work
	 * comes down together. Normal digestion, a processing failure, a store failure
	 * and shutdown all end here, so neither half can be done without the other.
	 */
	void release(List<QueuedEnvelope> entries) {
		for (QueuedEnvelope entry : entries) {
			spool.delete(entry.spoolFile());
		}
		outstanding.addAndGet(-entries.size());
	}

	/** Releases accepted references that no worker can finish during shutdown. */
	void releaseRemaining() {
		List<QueuedEnvelope> remaining = new ArrayList<>();
		queue.drainTo(remaining);
		release(remaining);
	}

	/** Accepted items that are still queued or being processed by a worker. */
	public int outstanding() {
		return outstanding.get();
	}
}
