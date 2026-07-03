package dev.outpost.ingest;

import tools.jackson.databind.JsonNode;
import java.util.List;

/** A parsed Sentry envelope: one header line + N items. */
public record Envelope(JsonNode header, List<Item> items) {

	/** One envelope item: JSON header line + payload bytes. */
	public record Item(JsonNode header, byte[] payload) {

		public String type() {
			JsonNode type = header.get("type");
			return type == null ? "" : type.asText();
		}
	}
}
