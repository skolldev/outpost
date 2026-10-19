package dev.outpost.pipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * Sentry's typed attribute bag, shared by log records and streamed spans:
 * {@code {"key": {"type": "string", "value": v}}}.
 */
final class Attributes {

	private Attributes() {
	}

	/** Unwraps each {@code {type, value}} to its plain value; {@code type} is not checked against it. */
	static ObjectNode flatten(JsonNode attributes) {
		ObjectNode flat = JsonNodeFactory.instance.objectNode();
		if (attributes != null && attributes.isObject()) {
			for (Map.Entry<String, JsonNode> attribute : attributes.properties()) {
				JsonNode value = attribute.getValue();
				flat.set(attribute.getKey(), value.isObject() && value.has("value") ? value.get("value") : value);
			}
		}
		return flat;
	}

	/** A non-blank attribute rendered as text, or null. */
	static String text(ObjectNode attributes, String key) {
		JsonNode value = attributes.get(key);
		return value != null && !value.isNull() && !value.asText().isBlank() ? value.asText() : null;
	}
}
