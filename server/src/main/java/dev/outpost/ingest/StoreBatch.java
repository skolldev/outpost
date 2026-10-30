package dev.outpost.ingest;

import dev.outpost.pipeline.ProcessedEvent;
import dev.outpost.pipeline.ProcessedLog;
import dev.outpost.pipeline.ProcessedSpan;
import dev.outpost.pipeline.ProcessedTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Processed telemetry awaiting storage, bounded by a budget of database rows
 * ({@code outpost.ingest.max-batch-rows}) rather than by envelopes or items.
 * A row is one row written: an event, a log record, a txn, a span. That is the
 * unit that means the same thing for every signal — an envelope can carry one
 * event or a hundred log records — so it is what bounds a store call and its
 * transaction.
 * <p>
 * The batch hands its contents to the sink the moment they fill the budget, so
 * it never holds more than the budget. A transaction is kept whole with its
 * child spans when it fits in a batch of its own; one with more spans than the
 * budget allows is stored with its first spans and the rest go out as span rows,
 * which {@code TransactionStore.storeSpans} already accepts independently of
 * their txn (streamed spans arrive that way). Such a transaction is still
 * processed whole before it reaches the batch: the split bounds what is stored
 * and held, not the one transaction in hand, which the envelope size limit bounds.
 */
final class StoreBatch {

	/** One store call's worth of telemetry, per signal; its lists are not touched again. */
	record Flush(List<ProcessedEvent> events, List<ProcessedLog> logs, List<ProcessedTransaction> transactions,
			List<ProcessedSpan> spans) {

		int rows() {
			int rows = events.size() + logs.size() + spans.size();
			for (ProcessedTransaction transaction : transactions) {
				rows += rowsOf(transaction);
			}
			return rows;
		}
	}

	private final int maxRows;

	private final Consumer<Flush> sink;

	private Flush pending;

	private int rows;

	StoreBatch(int maxRows, Consumer<Flush> sink) {
		this.maxRows = requireValidBudget(maxRows);
		this.sink = sink;
		this.pending = empty();
	}

	/** Returns {@code maxRows}, or throws if no batch could be bounded by it. */
	static int requireValidBudget(int maxRows) {
		if (maxRows < 1) {
			throw new IllegalArgumentException("outpost.ingest.max-batch-rows must be at least 1");
		}
		return maxRows;
	}

	void add(ProcessedEvent event) {
		pending.events().add(event);
		added(1);
	}

	void add(ProcessedLog log) {
		pending.logs().add(log);
		added(1);
	}

	void add(ProcessedSpan span) {
		pending.spans().add(span);
		added(1);
	}

	void add(ProcessedTransaction transaction) {
		int cost = rowsOf(transaction);
		if (cost > maxRows - rows) {
			flush();
		}
		if (cost <= maxRows) {
			pending.transactions().add(transaction);
			added(cost);
			return;
		}
		List<ProcessedSpan> spans = transaction.spans();
		int headSpans = maxRows - 1;
		pending.transactions().add(transaction.withSpans(List.copyOf(spans.subList(0, headSpans))));
		added(maxRows);
		for (ProcessedSpan span : spans.subList(headSpans, spans.size())) {
			add(span);
		}
	}

	/** Hands everything pending to the sink; a no-op when nothing is. */
	void flush() {
		if (rows == 0) {
			return;
		}
		Flush full = pending;
		pending = empty();
		rows = 0;
		sink.accept(full);
	}

	int rows() {
		return rows;
	}

	/** Rows that can still be added before the batch flushes; always at least one. */
	int capacity() {
		return maxRows - rows;
	}

	private void added(int count) {
		rows += count;
		if (rows >= maxRows) {
			flush();
		}
	}

	private static int rowsOf(ProcessedTransaction transaction) {
		return 1 + transaction.spans().size();
	}

	private static Flush empty() {
		return new Flush(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
	}

}
