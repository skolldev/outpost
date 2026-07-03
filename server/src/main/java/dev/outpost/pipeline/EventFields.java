package dev.outpost.pipeline;

import tools.jackson.databind.JsonNode;

/** Shared accessors for the loosely-typed Sentry event JSON. */
final class EventFields {

	private EventFields() {
	}

	/** Message from {@code logentry.formatted}, {@code message} (string or object). */
	static String message(JsonNode event) {
		JsonNode logentry = event.path("logentry");
		if (logentry.hasNonNull("formatted")) {
			return logentry.get("formatted").asText();
		}
		if (logentry.hasNonNull("message")) {
			return logentry.get("message").asText();
		}
		JsonNode message = event.get("message");
		if (message == null) {
			return null;
		}
		if (message.isObject()) {
			return message.hasNonNull("formatted") ? message.get("formatted").asText()
					: message.path("message").asText(null);
		}
		return message.asText(null);
	}

	/** The primary (last) exception of the chain, or null. */
	static JsonNode primaryException(JsonNode event) {
		JsonNode values = event.path("exception").path("values");
		return values.isArray() && !values.isEmpty() ? values.get(values.size() - 1) : null;
	}
}
