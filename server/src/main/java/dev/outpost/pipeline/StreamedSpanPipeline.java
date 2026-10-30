package dev.outpost.pipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import dev.outpost.ingest.IngestItem;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps a v2 {@code span} item (span streaming, the SDK v11 default) onto the same
 * txn/span model as a {@code transaction} item: a segment span is a
 * {@link ProcessedTransaction}, every other span a {@link ProcessedSpan}.
 * <p>
 * Spans are flushed as they finish, so children usually arrive before their
 * segment. Both row ids are derived from (project, trace, span id) rather than
 * random: a child names its txn by {@code sentry.segment.id} without waiting for
 * it, and an SDK retry dedupes on the primary key instead of storing twice.
 */
@Component
public class StreamedSpanPipeline {

	private static final Logger log = LoggerFactory.getLogger(StreamedSpanPipeline.class);

	/**
	 * Browser web-vital spans are sent as their own segment once the pageload or
	 * navigation has ended. They are measurements, not activities, so they are stored as
	 * spans to stay out of Traces and Performance.
	 */
	private static final List<String> MEASUREMENT_OP_PREFIXES = List.of("ui.interaction.", "ui.webvital.");

	// Promoted to columns (attributes are re-added flattened under "data"); excluded to avoid duplicating them.
	private static final Set<String> PROMOTED = Set.of("trace_id", "span_id", "parent_span_id", "name",
			"start_timestamp", "end_timestamp", "status", "is_segment", "attributes");

	public StreamedSpans process(IngestItem.SpanBatch batch) {
		List<ProcessedTransaction> segments = new ArrayList<>();
		List<ProcessedSpan> spans = new ArrayList<>();
		for (JsonNode item : batch.payload().path("items")) {
			if (!item.isObject()) {
				continue;
			}
			try {
				process(item, batch, segments, spans);
			}
			catch (RuntimeException e) {
				log.warn("dropping unprocessable streamed span for project {}: {}", batch.projectId(), e.toString());
			}
		}
		return new StreamedSpans(segments, spans);
	}

	private void process(JsonNode item, IngestItem.SpanBatch batch, List<ProcessedTransaction> segments,
			List<ProcessedSpan> spans) {
		String traceId = text(item, "trace_id");
		String spanId = text(item, "span_id");
		if (traceId == null || spanId == null) {
			throw new IllegalArgumentException("span missing span_id/trace_id");
		}
		ObjectNode attributes = Attributes.flatten(item.get("attributes"));
		String op = Attributes.text(attributes, "sentry.op");
		Instant start = Timestamps.clamp(Timestamps.parse(item.get("start_timestamp")), batch.receivedAt());
		Instant end = Timestamps.clamp(Timestamps.parse(item.get("end_timestamp")), batch.receivedAt());
		if (end.isBefore(start)) {
			end = start;
		}
		double durationMs = Duration.between(start, end).toNanos() / 1_000_000.0;
		long projectId = batch.projectId();
		boolean segment = item.path("is_segment").asBoolean(false);

		if (segment && !isMeasurement(op)) {
			String environment = Attributes.text(attributes, "sentry.environment");
			String name = text(item, "name");
			if (name == null) {
				name = Attributes.text(attributes, "sentry.segment.name");
			}
			segments.add(new ProcessedTransaction(stableId(projectId, traceId, spanId), projectId,
					environment != null ? environment : "production", Attributes.text(attributes, "sentry.release"),
					traceId, spanId, text(item, "parent_span_id"), name != null ? name : "<unnamed>", op, start, end,
					durationMs, text(item, "status"), data(item, attributes), List.of()));
			return;
		}
		String segmentId = segment ? spanId : Attributes.text(attributes, "sentry.segment.id");
		UUID txnId = stableId(projectId, traceId, segmentId != null ? segmentId : spanId);
		spans.add(new ProcessedSpan(stableId(projectId, traceId, spanId), txnId, projectId, traceId, spanId,
				text(item, "parent_span_id"), op, text(item, "name"), start, end, durationMs, text(item, "status"),
				data(item, attributes)));
	}

	/** Same shape as a v1 span's stored data — attributes under {@code data} — so the UI reads both alike. */
	private static JsonNode data(JsonNode item, ObjectNode attributes) {
		ObjectNode copy = ((ObjectNode) item).deepCopy();
		PROMOTED.forEach(copy::remove);
		copy.set("data", attributes);
		return copy;
	}

	private static boolean isMeasurement(String op) {
		return op != null && MEASUREMENT_OP_PREFIXES.stream().anyMatch(op::startsWith);
	}

	static UUID stableId(long projectId, String traceId, String spanId) {
		return UUID.nameUUIDFromBytes((projectId + ":" + traceId + ":" + spanId).getBytes(StandardCharsets.UTF_8));
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && !value.isNull() && !value.asText().isBlank() ? value.asText() : null;
	}
}
