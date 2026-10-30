package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.outpost.pipeline.ProcessedEvent;
import dev.outpost.pipeline.ProcessedLog;
import dev.outpost.pipeline.ProcessedSpan;
import dev.outpost.pipeline.ProcessedTransaction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class StoreBatchTest {

	private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

	private final List<StoreBatch.Flush> flushes = new ArrayList<>();

	@Test
	void flushesAsSoonAsTheBudgetIsFull() {
		StoreBatch batch = new StoreBatch(3, flushes::add);

		for (int i = 0; i < 7; i++) {
			batch.add(log(i));
		}

		assertThat(flushes).extracting(StoreBatch.Flush::rows).containsExactly(3, 3);
		assertThat(batch.rows()).isEqualTo(1);
		batch.flush();
		assertThat(flushes).extracting(StoreBatch.Flush::rows).containsExactly(3, 3, 1);
		assertThat(flushes.stream().flatMap(flush -> flush.logs().stream()).map(ProcessedLog::body))
			.containsExactly("0", "1", "2", "3", "4", "5", "6");
	}

	@Test
	void countsRowsAcrossSignalsAgainstOneBudget() {
		StoreBatch batch = new StoreBatch(4, flushes::add);

		batch.add(event(1));
		batch.add(log(0));
		batch.add(span(1));
		batch.add(event(2));

		assertThat(flushes).hasSize(1);
		StoreBatch.Flush flush = flushes.getFirst();
		assertThat(flush.events()).hasSize(2);
		assertThat(flush.logs()).hasSize(1);
		assertThat(flush.spans()).hasSize(1);
		assertThat(flush.rows()).isEqualTo(4);
	}

	@Test
	void aTransactionCostsItsOwnRowPlusOnePerChildSpan() {
		StoreBatch batch = new StoreBatch(10, flushes::add);

		batch.add(transaction(4));

		assertThat(batch.rows()).isEqualTo(5);
		assertThat(flushes).isEmpty();
	}

	@Test
	void aTransactionThatDoesNotFitWhatIsLeftFlushesFirstRatherThanSplitting() {
		StoreBatch batch = new StoreBatch(10, flushes::add);
		batch.add(transaction(4));

		batch.add(transaction(6));

		assertThat(flushes).hasSize(1);
		assertThat(flushes.getFirst().rows()).isEqualTo(5);
		assertThat(batch.rows()).isEqualTo(7);
	}

	@Test
	void aTransactionLargerThanTheBudgetIsStoredWithItsFirstSpansAndTheRestAsSpans() {
		StoreBatch batch = new StoreBatch(4, flushes::add);
		ProcessedTransaction transaction = transaction(10);

		batch.add(transaction);
		batch.flush();

		assertThat(flushes).allSatisfy(flush -> assertThat(flush.rows()).isLessThanOrEqualTo(4));
		List<ProcessedTransaction> stored = flushes.stream().flatMap(flush -> flush.transactions().stream()).toList();
		assertThat(stored).singleElement().satisfies(head -> {
			assertThat(head.id()).isEqualTo(transaction.id());
			assertThat(head.spans()).hasSize(3);
		});
		List<ProcessedSpan> allSpans = new ArrayList<>(stored.getFirst().spans());
		flushes.forEach(flush -> allSpans.addAll(flush.spans()));
		assertThat(allSpans).containsExactlyElementsOf(transaction.spans());
	}

	@Test
	void aBudgetOfOneStillStoresATransactionAndEverySpan() {
		StoreBatch batch = new StoreBatch(1, flushes::add);

		batch.add(transaction(2));
		batch.flush();

		assertThat(flushes).extracting(StoreBatch.Flush::rows).containsExactly(1, 1, 1);
		assertThat(flushes.getFirst().transactions().getFirst().spans()).isEmpty();
	}

	@Test
	void flushingNothingDoesNotCallTheSink() {
		StoreBatch batch = new StoreBatch(5, flushes::add);

		batch.flush();

		assertThat(flushes).isEmpty();
	}

	@Test
	void eachFlushHandsOverListsTheBatchNoLongerTouches() {
		StoreBatch batch = new StoreBatch(1, flushes::add);

		batch.add(log(0));
		batch.add(log(1));

		assertThat(flushes.getFirst().logs()).extracting(ProcessedLog::body).containsExactly("0");
	}

	@Test
	void rejectsABudgetBelowOneRow() {
		assertThatThrownBy(() -> new StoreBatch(0, flushes::add)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("outpost.ingest.max-batch-rows");
	}

	private static ProcessedLog log(int i) {
		return new ProcessedLog(UUID.randomUUID(), 1, "production", null, NOW, null, null, "info", null,
				String.valueOf(i), null);
	}

	private static ProcessedEvent event(long projectId) {
		return new ProcessedEvent(UUID.randomUUID(), projectId, "production", null, NOW, null, "error", "m", null, "t",
				null, "fp", null, null, null, null);
	}

	private static ProcessedSpan span(int i) {
		return new ProcessedSpan(UUID.randomUUID(), UUID.randomUUID(), 1, "trace", "span" + i, null, null, null, NOW,
				NOW, 0, null, null);
	}

	private static ProcessedTransaction transaction(int spans) {
		return new ProcessedTransaction(UUID.randomUUID(), 1, "production", null, "trace", "root", null, "txn", null,
				NOW, NOW, 0, null, null, IntStream.range(0, spans).mapToObj(StoreBatchTest::span).toList());
	}

}
