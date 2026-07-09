package dev.outpost.ingest;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * One unit of work pulled off an envelope, queued for the processing pipeline:
 * either a single error event or a batch of log records (the SDKs ship logs
 * pre-batched, ≤ 100 records per item).
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

	/** A {@code log} envelope item: {@code {"items": [...]}} with up to 100 log records. */
	record LogBatch(long projectId, Instant receivedAt, JsonNode payload) implements IngestItem {
	}

	/**
	 * A {@code transaction} envelope item (§6.4): the trace's root span plus a
	 * {@code spans[]} array of children. Stored as one txn row + one span row per
	 * entry — no grouping, no ingest-time trace linking.
	 */
	record TransactionEvent(long projectId, Instant receivedAt, JsonNode payload) implements IngestItem {
	}

	record Attachment(String filename, String contentType, byte[] content) {
	}
}
