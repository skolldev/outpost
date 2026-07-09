package dev.outpost.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The entire "message queue" (§6.1): a bounded in-memory buffer between the
 * envelope endpoint and the batch-insert workers. Full queue → caller responds
 * 429 and the SDKs back off.
 */
@Component
public class IngestQueue {

	private final BlockingQueue<IngestItem> queue;

	public IngestQueue(@Value("${outpost.ingest.queue-capacity:10000}") int capacity) {
		this.queue = new ArrayBlockingQueue<>(capacity);
	}

	/** Non-blocking; false when the buffer is full (backpressure path). */
	public boolean offer(IngestItem item) {
		return queue.offer(item);
	}

	/**
	 * Blocks up to {@code lingerMillis} for a first item, then drains whatever
	 * else is immediately available up to {@code maxBatch}.
	 */
	public List<IngestItem> nextBatch(int maxBatch, long lingerMillis) throws InterruptedException {
		List<IngestItem> batch = new ArrayList<>();
		IngestItem first = queue.poll(lingerMillis, TimeUnit.MILLISECONDS);
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
}
