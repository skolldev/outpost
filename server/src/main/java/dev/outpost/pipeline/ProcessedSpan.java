package dev.outpost.pipeline;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A single span after pipeline processing, ready for storage: an entry of a
 * transaction's {@code spans[]} array, or a streamed (v2) non-segment span.
 */
public record ProcessedSpan(UUID id, UUID txnId, long projectId, String traceId, String spanId, String parentSpanId,
		String op, String description, Instant startTs, Instant endTs, double durationMs, String status, JsonNode data) {
}
