package dev.outpost.pipeline;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Best-effort batch storage: one item the database will never accept must not
 * cost the rest of the batch. The batch is attempted whole, and only on failure
 * degrades to one attempt per item, so the happy path stays a single round trip.
 *
 * <p>The retry target lives here rather than in each store because that is
 * exactly where the three drifted before (#124): a store whose fallback recursed
 * into its own public entry point re-ran the partition preparation above it,
 * once per item, on the one path already degraded.
 */
final class PoisonIsolation {

	private PoisonIsolation() {
	}

	/**
	 * Runs {@code attempt} over the whole batch; if that throws, runs it once per
	 * item and hands each item that still fails to {@code onPoison}.
	 *
	 * <p>{@code attempt} must be safe to re-run on a subset — a batch that failed
	 * partway is retried from the start, so anything it does outside its
	 * transaction has to tolerate repetition.
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
			// The logger belongs to the calling store, so its name says which
			// signal degraded without this needing a noun for each one.
			log.warn("batch of {} failed ({}), retrying individually", batch.size(), e.toString());
			for (T item : batch) {
				run(log, List.of(item), attempt, onPoison);
			}
		}
	}
}
