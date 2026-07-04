package dev.outpost.pipeline;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** An error event after pipeline processing, ready for storage. */
public record ProcessedEvent(UUID id, long projectId, String environment, String release, Instant timestamp,
		String traceId, String level, String message, String exceptionType, String title, String culprit,
		String fingerprint, String userIdent, JsonNode data, byte[] rawGzip, String symbolicationStatus) {
}
