package dev.outpost.pipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import dev.outpost.ingest.IngestItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Unpacks a {@code log} envelope item into one {@link ProcessedLog} per
 * record, flattening typed attributes ({@code {"key": {"value": v}}}) to
 * plain {@code {"key": v}}. Environment, release and span id come from
 * well-known {@code sentry.*} attributes.
 */
@Component
public class LogPipeline {

	private static final Logger log = LoggerFactory.getLogger(LogPipeline.class);

	public List<ProcessedLog> process(IngestItem.LogBatch batch) {
		JsonNode items = batch.payload().path("items");
		List<ProcessedLog> records = new ArrayList<>();
		if (!items.isArray()) {
			return records;
		}
		for (JsonNode item : items) {
			if (!item.isObject()) {
				continue;
			}
			try {
				records.add(record(item, batch));
			}
			catch (RuntimeException e) {
				log.warn("dropping unprocessable log record for project {}: {}", batch.projectId(), e.toString());
			}
		}
		return records;
	}

	private ProcessedLog record(JsonNode item, IngestItem.LogBatch batch) {
		ObjectNode attributes = Attributes.flatten(item.path("attributes"));
		String environment = Attributes.text(attributes, "sentry.environment");
		String release = Attributes.text(attributes, "sentry.release");
		String spanId = item.hasNonNull("span_id") ? item.get("span_id").asText()
				: Attributes.text(attributes, "sentry.trace.parent_span_id");
		Instant timestamp = Timestamps.clamp(Timestamps.parse(item.get("timestamp")), batch.receivedAt());
		Integer severityNumber = item.hasNonNull("severity_number") && item.get("severity_number").isNumber()
				? item.get("severity_number").asInt() : null;
		return new ProcessedLog(UUID.randomUUID(), batch.projectId(),
				environment != null ? environment : "production", release, timestamp,
				item.hasNonNull("trace_id") ? item.get("trace_id").asText() : null, spanId,
				item.path("level").asText("info"), severityNumber, item.path("body").asText(""), attributes);
	}
}
