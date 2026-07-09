package dev.outpost.pipeline;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A single span within a transaction after pipeline processing, ready for
 * storage. One row per entry in the transaction's {@code spans[]} array.
 */
public record ProcessedSpan(UUID id, UUID txnId, long projectId, String traceId, String spanId, String parentSpanId,
		String op, String description, Instant startTs, Instant endTs, double durationMs, String status, JsonNode data) {
}
