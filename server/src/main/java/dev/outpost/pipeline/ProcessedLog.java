package dev.outpost.pipeline;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A log record after pipeline processing, ready for storage. Also the SSE
 * live-tail payload — serialized field names (snake_case) match the rows the
 * {@code GET /logs} endpoint returns.
 */
public record ProcessedLog(UUID id, long projectId, String environment, String release, Instant timestamp,
		String traceId, String spanId, String level, Integer severityNumber, String body, JsonNode attributes) {
}
