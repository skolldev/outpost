package dev.outpost.ingest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;

/**
 * One unit of work parsed from a spooled envelope for the processing pipeline.
 * Log batches ship pre-batched by the SDKs, ≤ 100 records per item.
 */
public sealed interface IngestItem {

	long projectId();

	Instant receivedAt();

	/**
	 * An error event. Attachments from the same envelope (≤ 1 MiB each) ride
	 * along and are stored with the event.
	 */
	record ErrorEvent(long projectId, Instant receivedAt, JsonNode event,
			List<Attachment> attachments) implements IngestItem {
	}

	/**
	 * An item whose payload is {@code {"items": [...]}}, each entry yielding at
	 * most one stored row. The array can be as long as the envelope limits allow,
	 * so it can be processed in slices.
	 */
	sealed interface ItemArray<S extends ItemArray<S>> extends IngestItem {

		JsonNode payload();

		/** The {@code items[from, to)} as an item of the same kind; entries are shared, not copied. */
		S slice(int from, int to);

		default int itemCount() {
			JsonNode items = payload().path("items");
			return items.isArray() ? items.size() : 0;
		}
	}

	/** A {@code log} envelope item: {@code {"items": [...]}} with up to 100 log records. */
	record LogBatch(long projectId, Instant receivedAt, JsonNode payload) implements ItemArray<LogBatch> {

		@Override
		public LogBatch slice(int from, int to) {
			return new LogBatch(projectId, receivedAt, sliceItems(payload, from, to));
		}
	}

	/**
	 * A {@code transaction} envelope item: the trace's root span plus a
	 * {@code spans[]} array of children.
	 */
	record TransactionEvent(long projectId, Instant receivedAt, JsonNode payload) implements IngestItem {
	}

	/**
	 * A v2 {@code span} envelope item (span streaming): {@code {"items": [...]}}
	 * with a batch of independently finished spans, segment and child alike.
	 */
	record SpanBatch(long projectId, Instant receivedAt, JsonNode payload) implements ItemArray<SpanBatch> {

		@Override
		public SpanBatch slice(int from, int to) {
			return new SpanBatch(projectId, receivedAt, sliceItems(payload, from, to));
		}
	}

	record Attachment(String filename, String contentType, byte[] content) {
	}

	/** {@code payload} with only {@code items[from, to)}; other fields and the entries themselves are shared. */
	private static JsonNode sliceItems(JsonNode payload, int from, int to) {
		ObjectNode slice = JsonNodeFactory.instance.objectNode();
		for (var property : payload.properties()) {
			if (!property.getKey().equals("items")) {
				slice.set(property.getKey(), property.getValue());
			}
		}
		ArrayNode items = slice.putArray("items");
		JsonNode source = payload.path("items");
		for (int i = from; i < to; i++) {
			items.add(source.get(i));
		}
		return slice;
	}
}
