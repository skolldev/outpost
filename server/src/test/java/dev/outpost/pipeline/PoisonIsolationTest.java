package dev.outpost.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The three stores share this fallback, so the semantics they all depend on —
 * one round trip when nothing is wrong, and one bad item costing only itself —
 * are pinned once here rather than three times over.
 */
class PoisonIsolationTest {

	private static final Logger LOG = LoggerFactory.getLogger(PoisonIsolationTest.class);

	private final List<List<String>> attempts = new ArrayList<>();

	private final List<String> poisoned = new ArrayList<>();

	@Test
	void aCleanBatchIsAttemptedOnceAndNeverSplit() {
		run(List.of("a", "b", "c"), item -> false);

		assertThat(attempts).containsExactly(List.of("a", "b", "c"));
		assertThat(poisoned).isEmpty();
	}

	@Test
	void onePoisonItemCostsOnlyItself() {
		run(List.of("a", "bad", "c"), "bad"::equals);

		// The whole batch, then each item alone.
		assertThat(attempts).containsExactly(List.of("a", "bad", "c"), List.of("a"), List.of("bad"), List.of("c"));
		assertThat(poisoned).containsExactly("bad");
	}

	@Test
	void everyItemCanBePoison() {
		run(List.of("x", "y"), item -> true);

		assertThat(poisoned).containsExactly("x", "y");
	}

	@Test
	void theFailureReachesTheHandlerThatReportsIt() {
		List<String> reported = new ArrayList<>();
		PoisonIsolation.run(LOG, List.of("only"), batch -> {
			throw new IllegalStateException("constraint violated");
		}, (item, failure) -> reported.add(item + ": " + failure.getMessage()));

		assertThat(reported).containsExactly("only: constraint violated");
	}

	/** Attempts the batch, failing whenever it still contains a poison item. */
	private void run(List<String> batch, Predicate<String> poison) {
		PoisonIsolation.run(LOG, batch, items -> {
			attempts.add(List.copyOf(items));
			if (items.stream().anyMatch(poison)) {
				throw new IllegalStateException("poison item in batch");
			}
		}, (item, failure) -> poisoned.add(item));
	}
}
