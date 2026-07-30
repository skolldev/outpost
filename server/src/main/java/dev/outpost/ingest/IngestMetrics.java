package dev.outpost.ingest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

/**
 * Every meter on the ingest path, named in one place. The hot-path classes call
 * intention-named methods here rather than carrying {@code registry.counter("…")}
 * string literals, so the naming contract lives in one file and — because every
 * meter is resolved once in the constructor — recording costs a field read and an
 * increment, with no tag list or meter id allocated per event.
 *
 * <p>Read together the meters answer "how much can we ingest": accepted vs
 * rejected envelopes is the accept-side ceiling, {@code queue.depth} shows the
 * buffer filling, and {@code queue.wait} — dequeue time minus the item's
 * {@code receivedAt} — is the honest measure of how far behind the workers are.
 * A climbing queue wait under a flat accept rate means the drain side is the
 * limit, which is the distinction a 429 alone cannot make.
 */
@Component
public class IngestMetrics {

	/** The signal a queued item carries. Tag value on the per-signal meters. */
	public enum Signal {

		ERROR("error"), LOG("log"), TRANSACTION("transaction");

		private final String tag;

		Signal(String tag) {
			this.tag = tag;
		}

		public static Signal of(IngestItem item) {
			return switch (item) {
				case IngestItem.ErrorEvent ignored -> ERROR;
				case IngestItem.LogBatch ignored -> LOG;
				case IngestItem.TransactionEvent ignored -> TRANSACTION;
			};
		}

	}

	/** Terminal outcome of an envelope, mirroring the controller's response codes. */
	public enum Outcome {

		/** Queued for the workers — 200. */
		ACCEPTED("accepted"),
		/** Buffer full — 429, and the SDK backs off. */
		REJECTED("rejected"),
		/** Unknown or inactive DSN key — 403. */
		FORBIDDEN("forbidden"),
		/** Unparseable envelope — 400. */
		MALFORMED("malformed"),
		/** Over the size cap — 413. */
		OVERSIZE("oversize");

		private final String tag;

		Outcome(String tag) {
			this.tag = tag;
		}

	}

	/** Where an item was lost. */
	public enum DropStage {

		PIPELINE("pipeline"), STORE("store");

		private final String tag;

		DropStage(String tag) {
			this.tag = tag;
		}

	}

	private final MeterRegistry registry;

	private final Map<Outcome, Counter> envelopes = new EnumMap<>(Outcome.class);

	private final Map<Signal, Counter> itemsQueued = new EnumMap<>(Signal.class);

	private final Map<Signal, Counter> itemsRejected = new EnumMap<>(Signal.class);

	private final Map<Signal, Timer> queueWait = new EnumMap<>(Signal.class);

	private final Map<Signal, Timer> process = new EnumMap<>(Signal.class);

	private final Map<Signal, Timer> store = new EnumMap<>(Signal.class);

	private final Map<DropStage, Counter> dropped = new EnumMap<>(DropStage.class);

	private final Counter duplicates;

	private final DistributionSummary batchSize;

	public IngestMetrics(MeterRegistry registry) {
		this.registry = registry;
		for (Outcome outcome : Outcome.values()) {
			envelopes.put(outcome, Counter.builder("outpost.ingest.envelopes")
				.description("Envelopes by ingest outcome")
				.tag("outcome", outcome.tag)
				.register(registry));
		}
		for (Signal signal : Signal.values()) {
			itemsQueued.put(signal, items(signal, Outcome.ACCEPTED));
			itemsRejected.put(signal, items(signal, Outcome.REJECTED));
			queueWait.put(signal, timer("outpost.ingest.queue.wait", "Time an item waited in the ingest buffer", signal));
			process.put(signal, timer("outpost.ingest.process", "Pipeline processing time per item", signal));
			store.put(signal, timer("outpost.ingest.store", "Batch persistence time", signal));
		}
		for (DropStage stage : DropStage.values()) {
			dropped.put(stage, Counter.builder("outpost.ingest.dropped")
				.description("Items lost rather than stored")
				.tag("stage", stage.tag)
				.register(registry));
		}
		// Deliberately not a `dropped` stage: a redelivery is a healthy SDK retry,
		// not a loss, and folding it into the drop counter would poison any alert
		// on drop rate.
		this.duplicates = Counter.builder("outpost.ingest.duplicates")
			.description("Events already stored under their event id, so not stored again")
			.register(registry);
		this.batchSize = DistributionSummary.builder("outpost.ingest.batch.size")
			.description("Envelope references drained per worker batch")
			.publishPercentiles(0.5, 0.95)
			.register(registry);
	}

	private Counter items(Signal signal, Outcome outcome) {
		return Counter.builder("outpost.ingest.items")
			.description("Queued items by signal and outcome")
			.tag("signal", signal.tag)
			.tag("outcome", outcome.tag)
			.register(registry);
	}

	private Timer timer(String name, String description, Signal signal) {
		return Timer.builder(name)
			.description(description)
			.tag("signal", signal.tag)
			// Percentiles are computed in-process. Single-instance by ADR 0001, so
			// there is nothing to aggregate across and this stays accurate.
			.publishPercentiles(0.5, 0.95, 0.99)
			.register(registry);
	}

	public void envelope(Outcome outcome) {
		envelopes.get(outcome).increment();
	}

	public void itemQueued(Signal signal) {
		itemsQueued.get(signal).increment();
	}

	public void itemRejected(Signal signal) {
		itemsRejected.get(signal).increment();
	}

	/**
	 * Registers a gauge over a live value. Micrometer holds {@code source} weakly,
	 * so the caller must keep it referenced; the components gauged here are
	 * singletons, so that holds for the application's lifetime.
	 */
	public <T> void gauge(String name, String description, T source, ToDoubleFunction<T> value) {
		Gauge.builder(name, source, value).description(description).register(registry);
	}

	public void batchDrained(int size) {
		batchSize.record(size);
	}

	public void queueWait(Signal signal, Instant receivedAt, Instant dequeuedAt) {
		queueWait.get(signal).record(Duration.between(receivedAt, dequeuedAt));
	}

	public void processed(Signal signal, long nanos) {
		process.get(signal).record(nanos, TimeUnit.NANOSECONDS);
	}

	public void stored(Signal signal, long nanos) {
		store.get(signal).record(nanos, TimeUnit.NANOSECONDS);
	}

	public void dropped(DropStage stage) {
		dropped.get(stage).increment();
	}

	public void duplicates(int count) {
		duplicates.increment(count);
	}

}
