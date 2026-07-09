package dev.outpost.pipeline;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A transaction after pipeline processing: the trace's root span plus its child
 * {@link ProcessedSpan}s. Stored as one txn row + one span row per child.
 */
public record ProcessedTransaction(UUID id, long projectId, String environment, String release, String traceId,
		String spanId, String parentSpanId, String name, String op, Instant startTs, Instant endTs, double durationMs,
		String status, JsonNode data, List<ProcessedSpan> spans) {
}
