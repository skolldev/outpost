package dev.outpost.pipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import dev.outpost.ingest.IngestItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns a {@code transaction} envelope item (§6.4) into one
 * {@link ProcessedTransaction} (the root span + transaction metadata) plus one
 * {@link ProcessedSpan} per entry in {@code spans[]}. Trace identity is read
 * from {@code contexts.trace}; the transaction name from the top-level
 * {@code transaction} field. No grouping, no ingest-time trace linking —
 * correlation to errors/logs happens at query time via {@code trace_id}.
 */
@Component
public class TransactionPipeline {

	private static final Logger log = LoggerFactory.getLogger(TransactionPipeline.class);

	public ProcessedTransaction process(IngestItem.TransactionEvent event) {
		JsonNode payload = event.payload();
		JsonNode trace = payload.path("contexts").path("trace");
		String traceId = text(trace, "trace_id");
		String spanId = text(trace, "span_id");
		if (traceId == null || spanId == null) {
			throw new IllegalArgumentException("transaction missing contexts.trace trace_id/span_id");
		}

		Instant start = Timestamps.clamp(Timestamps.parse(payload.get("start_timestamp")), event.receivedAt());
		Instant end = Timestamps.clamp(Timestamps.parse(payload.get("timestamp")), event.receivedAt());
		if (end.isBefore(start)) {
			end = start;
		}
		String environment = text(payload, "environment");
		String release = text(payload, "release");
		String name = payload.path("transaction").asText("<unnamed>");

		UUID txnId = UUID.randomUUID();
		ProcessedTransaction transaction = new ProcessedTransaction(txnId, event.projectId(),
				environment != null ? environment : "production", release, traceId, spanId, text(trace, "parent_span_id"),
				name, text(trace, "op"), start, end, durationMs(start, end), text(trace, "status"), trimTxn(payload),
				spans(payload.path("spans"), txnId, event));
		return transaction;
	}

	private List<ProcessedSpan> spans(JsonNode spans, UUID txnId, IngestItem.TransactionEvent event) {
		List<ProcessedSpan> result = new ArrayList<>();
		if (!spans.isArray()) {
			return result;
		}
		for (JsonNode span : spans) {
			if (!span.isObject()) {
				continue;
			}
			try {
				result.add(span(span, txnId, event));
			}
			catch (RuntimeException e) {
				log.warn("dropping unprocessable span for project {}: {}", event.projectId(), e.toString());
			}
		}
		return result;
	}

	private ProcessedSpan span(JsonNode span, UUID txnId, IngestItem.TransactionEvent event) {
		String spanId = text(span, "span_id");
		String traceId = text(span, "trace_id");
		if (spanId == null || traceId == null) {
			throw new IllegalArgumentException("span missing span_id/trace_id");
		}
		Instant start = Timestamps.clamp(Timestamps.parse(span.get("start_timestamp")), event.receivedAt());
		Instant end = Timestamps.clamp(Timestamps.parse(span.get("timestamp")), event.receivedAt());
		if (end.isBefore(start)) {
			end = start;
		}
		return new ProcessedSpan(UUID.randomUUID(), txnId, event.projectId(), traceId, spanId,
				text(span, "parent_span_id"), text(span, "op"), text(span, "description"), start, end,
				durationMs(start, end), text(span, "status"), trimSpan(span));
	}

	// Fields promoted to their own columns; keeping them in the stored payload just
	// duplicates every span's identity and timing over the wire and on disk.
	private static final Set<String> SPAN_PROMOTED = Set.of("span_id", "trace_id", "parent_span_id", "op",
			"description", "status", "timestamp", "start_timestamp");

	/**
	 * The child spans are stored as their own rows, so re-embedding the whole
	 * {@code spans[]} array in the transaction payload doubles storage (and, on a
	 * browser pageload, is the bulk of a 200 KB transaction). Drop it; keep the
	 * rest of the payload (contexts, measurements, request, user…).
	 */
	private JsonNode trimTxn(JsonNode payload) {
		if (!payload.isObject()) {
			return payload;
		}
		ObjectNode copy = ((ObjectNode) payload).deepCopy();
		copy.remove("spans");
		return copy;
	}

	/**
	 * Everything in {@link #SPAN_PROMOTED} already lives in a column, so strip it
	 * from the stored span payload. What's left is the useful part: the OTel
	 * attribute bag under {@code data} plus {@code origin}.
	 */
	private JsonNode trimSpan(JsonNode span) {
		if (!span.isObject()) {
			return span;
		}
		ObjectNode copy = ((ObjectNode) span).deepCopy();
		SPAN_PROMOTED.forEach(copy::remove);
		return copy;
	}

	private static double durationMs(Instant start, Instant end) {
		return java.time.Duration.between(start, end).toNanos() / 1_000_000.0;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && !value.isNull() && !value.asText().isBlank() ? value.asText() : null;
	}
}
