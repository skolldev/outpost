package dev.outpost.pipeline;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Best-effort batch storage: attempts the whole batch, and only on failure
 * retries one item at a time, so the happy path stays a single round trip.
 * Keep the retry here rather than recursing into a store's public entry
 * point — that would re-run per-batch work like partition prep on every item.
 */
final class PoisonIsolation {

	private PoisonIsolation() {
	}

	/**
	 * Runs {@code attempt} over the whole batch; if that throws, retries one item
	 * at a time and hands each item that still fails to {@code onPoison}.
	 * {@code attempt} must tolerate re-running on a subset, since a batch that
	 * fails partway is retried from the start.
	 */
	static <T> void run(Logger log, List<T> batch, Consumer<List<T>> attempt,
			BiConsumer<T, RuntimeException> onPoison) {
		try {
			attempt.accept(batch);
		}
		catch (RuntimeException e) {
			if (batch.size() == 1) {
				onPoison.accept(batch.getFirst(), e);
				return;
			}
			// log is the caller's logger, so warnings are attributed to the store
			// that degraded.
			log.warn("batch of {} failed ({}), retrying individually", batch.size(), e.toString());
			for (T item : batch) {
				run(log, List.of(item), attempt, onPoison);
			}
		}
	}
}
