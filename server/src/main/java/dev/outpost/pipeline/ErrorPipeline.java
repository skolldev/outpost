package dev.outpost.pipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.outpost.ingest.IngestItem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Component;

/**
 * Turns a raw event item into a {@link ProcessedEvent}: extracts the indexed
 * columns, computes the grouping fingerprint and issue title, clamps wildly
 * skewed timestamps, and keeps the gzipped original for later re-processing
 * (symbolication arrives in Phase 2).
 */
@Component
public class ErrorPipeline {

	private static final Duration MAX_PAST_SKEW = Duration.ofDays(30);
	private static final Duration MAX_FUTURE_SKEW = Duration.ofHours(1);
	private static final int TITLE_MESSAGE_PREFIX = 120;

	private final ObjectMapper mapper;

	public ErrorPipeline(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	public ProcessedEvent process(IngestItem item) {
		JsonNode event = item.event();
		byte[] rawGzip = gzip(event);
		JsonNode data = withAttachments(event, item);

		UUID id = parseEventId(event.path("event_id").asText(null));
		String environment = event.hasNonNull("environment") && !event.get("environment").asText().isBlank()
				? event.get("environment").asText() : "production";
		String release = event.hasNonNull("release") ? event.get("release").asText() : null;
		Instant timestamp = clamp(parseTimestamp(event.get("timestamp")), item.receivedAt());
		String traceId = event.path("contexts").path("trace").path("trace_id").asText(null);
		String level = event.path("level").asText("error");
		String message = EventFields.message(event);

		JsonNode primary = EventFields.primaryException(event);
		String exceptionType = primary != null ? primary.path("type").asText(null) : null;
		String title = title(primary, message, level);
		String culprit = culprit(event, primary);
		String fingerprint = Fingerprinter.fingerprint(event);
		String userIdent = userIdent(event.path("user"));

		return new ProcessedEvent(id, item.projectId(), environment, release, timestamp, traceId, level, message,
				exceptionType, title, culprit, fingerprint, userIdent, data, rawGzip);
	}

	private String title(JsonNode primary, String message, String level) {
		if (primary != null) {
			String type = primary.path("type").asText("Error");
			String value = primary.path("value").asText("");
			return value.isBlank() ? type : type + ": " + prefix(value);
		}
		if (message != null && !message.isBlank()) {
			return prefix(message);
		}
		return "<unlabeled event: " + level + ">";
	}

	private static String prefix(String text) {
		String firstLine = text.lines().findFirst().orElse(text);
		return firstLine.length() <= TITLE_MESSAGE_PREFIX ? firstLine
				: firstLine.substring(0, TITLE_MESSAGE_PREFIX) + "…";
	}

	private String culprit(JsonNode event, JsonNode primary) {
		if (event.hasNonNull("transaction")) {
			return event.get("transaction").asText();
		}
		if (primary != null) {
			JsonNode frames = primary.path("stacktrace").path("frames");
			if (frames.isArray()) {
				// Last in-app frame (frames are ordered oldest → newest) is where it blew up.
				for (int i = frames.size() - 1; i >= 0; i--) {
					JsonNode frame = frames.get(i);
					if (frame.path("in_app").asBoolean(false)) {
						return frameLabel(frame);
					}
				}
				if (!frames.isEmpty()) {
					return frameLabel(frames.get(frames.size() - 1));
				}
			}
		}
		return null;
	}

	private String frameLabel(JsonNode frame) {
		String location = frame.hasNonNull("module") ? frame.get("module").asText()
				: frame.path("filename").asText("");
		String function = frame.path("function").asText("");
		return function.isBlank() ? location : location + " in " + function;
	}

	private String userIdent(JsonNode user) {
		for (String field : new String[] { "id", "email", "username", "ip_address" }) {
			if (user.hasNonNull(field)) {
				return user.get(field).asText();
			}
		}
		return null;
	}

	private JsonNode withAttachments(JsonNode event, IngestItem item) {
		if (item.attachments().isEmpty() || !event.isObject()) {
			return event;
		}
		ObjectNode copy = (ObjectNode) event.deepCopy();
		ArrayNode attachments = copy.putArray("_outpost_attachments");
		for (IngestItem.Attachment attachment : item.attachments()) {
			attachments.addObject()
				.put("filename", attachment.filename())
				.put("content_type", attachment.contentType())
				.put("size", attachment.content().length)
				.put("data", Base64.getEncoder().encodeToString(attachment.content()));
		}
		return copy;
	}

	private UUID parseEventId(String eventId) {
		if (eventId != null) {
			String hex = eventId.replace("-", "");
			if (hex.length() == 32) {
				try {
					return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16),
							Long.parseUnsignedLong(hex.substring(16), 16));
				}
				catch (NumberFormatException ignored) {
					// fall through to random id
				}
			}
		}
		return UUID.randomUUID();
	}

	private Instant parseTimestamp(JsonNode timestamp) {
		if (timestamp == null) {
			return null;
		}
		if (timestamp.isNumber()) {
			double seconds = timestamp.asDouble();
			return Instant.ofEpochMilli(Math.round(seconds * 1000));
		}
		try {
			return Instant.parse(timestamp.asText());
		}
		catch (DateTimeParseException e) {
			try {
				return OffsetDateTime.parse(timestamp.asText()).toInstant();
			}
			catch (DateTimeParseException e2) {
				return null;
			}
		}
	}

	private Instant clamp(Instant timestamp, Instant receivedAt) {
		if (timestamp == null || timestamp.isBefore(receivedAt.minus(MAX_PAST_SKEW))
				|| timestamp.isAfter(receivedAt.plus(MAX_FUTURE_SKEW))) {
			return receivedAt;
		}
		return timestamp;
	}

	private byte[] gzip(JsonNode event) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {
				mapper.writeValue(gzipOut, event);
			}
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
