package dev.outpost.pipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.outpost.ingest.IngestItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Unpacks a {@code log} envelope item (§6.3, Sentry logs protocol): one
 * {@link ProcessedLog} per record. Attributes arrive typed
 * ({@code {"key": {"value": v, "type": t}}}) and are flattened to plain
 * {@code {"key": v}} — the type tag adds nothing to a Kibana-lite search.
 * Environment, release and span id live in well-known {@code sentry.*}
 * attributes. No grouping — logs are an append-only stream.
 */
@Component
public class LogPipeline {

	private static final Logger log = LoggerFactory.getLogger(LogPipeline.class);

	private final ObjectMapper mapper;

	public LogPipeline(ObjectMapper mapper) {
		this.mapper = mapper;
	}

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
		ObjectNode attributes = flatten(item.path("attributes"));
		String environment = attribute(attributes, "sentry.environment");
		String release = attribute(attributes, "sentry.release");
		String spanId = item.hasNonNull("span_id") ? item.get("span_id").asText()
				: attribute(attributes, "sentry.trace.parent_span_id");
		Instant timestamp = Timestamps.clamp(Timestamps.parse(item.get("timestamp")), batch.receivedAt());
		Integer severityNumber = item.hasNonNull("severity_number") && item.get("severity_number").isNumber()
				? item.get("severity_number").asInt() : null;
		return new ProcessedLog(UUID.randomUUID(), batch.projectId(),
				environment != null ? environment : "production", release, timestamp,
				item.hasNonNull("trace_id") ? item.get("trace_id").asText() : null, spanId,
				item.path("level").asText("info"), severityNumber, item.path("body").asText(""), attributes);
	}

	private ObjectNode flatten(JsonNode attributes) {
		ObjectNode flat = mapper.createObjectNode();
		if (attributes.isObject()) {
			for (Map.Entry<String, JsonNode> attribute : attributes.properties()) {
				JsonNode value = attribute.getValue();
				flat.set(attribute.getKey(), value.isObject() && value.has("value") ? value.get("value") : value);
			}
		}
		return flat;
	}

	private String attribute(ObjectNode attributes, String key) {
		JsonNode value = attributes.get(key);
		return value != null && !value.isNull() && !value.asText().isBlank() ? value.asText() : null;
	}
}
