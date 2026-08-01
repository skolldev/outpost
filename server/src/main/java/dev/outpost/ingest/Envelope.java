package dev.outpost.ingest;

import tools.jackson.databind.JsonNode;
/** Types shared by the streaming envelope parser and its consumers. */
public final class Envelope {

	private Envelope() {
	}

	public enum ItemKind {
		EVENT, LOG, TRANSACTION, ATTACHMENT, CLIENT_REPORT, OTHER
	}

	/** One envelope item: JSON header line + payload bytes. */
	public record Item(JsonNode header, byte[] payload) {

		public ItemKind kind() {
			JsonNode type = header.get("type");
			return switch (type == null ? "" : type.asText()) {
				case "event" -> ItemKind.EVENT;
				case "log" -> ItemKind.LOG;
				case "transaction" -> ItemKind.TRANSACTION;
				case "attachment" -> ItemKind.ATTACHMENT;
				case "client_report" -> ItemKind.CLIENT_REPORT;
				default -> ItemKind.OTHER;
			};
		}

		/**
		 * The telemetry signal this item carries, or null when it carries none —
		 * an attachment, a client report, or a type this version does not know.
		 */
		IngestMetrics.Signal signal() {
			return switch (kind()) {
				case EVENT -> IngestMetrics.Signal.ERROR;
				case LOG -> IngestMetrics.Signal.LOG;
				case TRANSACTION -> IngestMetrics.Signal.TRANSACTION;
				case ATTACHMENT, CLIENT_REPORT, OTHER -> null;
			};
		}
	}

	/**
	 * A signal-bearing item whose payload parsed as a JSON object — the shape
	 * both the endpoint and the workers act on.
	 */
	public record SignalItem(IngestMetrics.Signal signal, JsonNode payload) {
	}
}
