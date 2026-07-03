package dev.outpost.ingest;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * One error event pulled off an envelope, queued for the processing pipeline.
 * Attachments from the same envelope (≤ 1 MiB each) ride along and are stored
 * with the event.
 */
public record IngestItem(long projectId, Instant receivedAt, JsonNode event, List<Attachment> attachments) {

	public record Attachment(String filename, String contentType, byte[] content) {
	}
}
