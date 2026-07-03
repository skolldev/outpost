package dev.outpost.ingest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Parses the newline-delimited Sentry envelope format (§4.2): one JSON envelope
 * header line, then per item one JSON item-header line followed by the payload —
 * either exactly {@code length} bytes, or up to the next newline when no length
 * is given.
 */
@Component
public class EnvelopeParser {

	public static final int MAX_ENVELOPE_BYTES = 20 * 1024 * 1024;
	public static final int MAX_ITEM_BYTES = 1024 * 1024;

	private final ObjectMapper mapper;

	public EnvelopeParser(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	public Envelope parse(byte[] body) {
		if (body.length > MAX_ENVELOPE_BYTES) {
			throw new OversizeException("envelope exceeds " + MAX_ENVELOPE_BYTES + " bytes");
		}
		int pos = indexOfNewline(body, 0);
		int headerEnd = pos < 0 ? body.length : pos;
		JsonNode header = parseJson(body, 0, headerEnd, "envelope header");
		pos = headerEnd + 1;

		List<Envelope.Item> items = new ArrayList<>();
		while (pos < body.length) {
			int itemHeaderEnd = indexOfNewline(body, pos);
			if (itemHeaderEnd < 0) {
				itemHeaderEnd = body.length;
			}
			if (itemHeaderEnd == pos) { // blank line (trailing newline) — skip
				pos++;
				continue;
			}
			JsonNode itemHeader = parseJson(body, pos, itemHeaderEnd, "item header");
			pos = itemHeaderEnd + 1;

			byte[] payload;
			JsonNode length = itemHeader.get("length");
			if (length != null && length.isNumber()) {
				long len = length.asLong();
				if (len < 0 || pos + len > body.length) {
					throw new MalformedEnvelopeException("item length out of bounds");
				}
				if (len > MAX_ITEM_BYTES) {
					// Oversize attachments are dropped per §4.2; anything else is a client error.
					if (!"attachment".equals(itemHeader.path("type").asText())) {
						throw new OversizeException("item exceeds " + MAX_ITEM_BYTES + " bytes");
					}
					pos += (int) len;
					if (pos < body.length && body[pos] == '\n') {
						pos++;
					}
					continue;
				}
				payload = Arrays.copyOfRange(body, pos, pos + (int) len);
				pos += (int) len;
				if (pos < body.length && body[pos] == '\n') {
					pos++;
				}
			}
			else {
				int payloadEnd = indexOfNewline(body, pos);
				if (payloadEnd < 0) {
					payloadEnd = body.length;
				}
				if (payloadEnd - pos > MAX_ITEM_BYTES) {
					throw new OversizeException("item exceeds " + MAX_ITEM_BYTES + " bytes");
				}
				payload = Arrays.copyOfRange(body, pos, payloadEnd);
				pos = payloadEnd + 1;
			}
			items.add(new Envelope.Item(itemHeader, payload));
		}
		return new Envelope(header, items);
	}

	private JsonNode parseJson(byte[] body, int from, int to, String what) {
		try {
			JsonNode node = mapper.readTree(body, from, to - from);
			if (node == null || !node.isObject()) {
				throw new MalformedEnvelopeException(what + " is not a JSON object");
			}
			return node;
		}
		catch (tools.jackson.core.JacksonException e) {
			throw new MalformedEnvelopeException(what + " is not valid JSON");
		}
	}

	private static int indexOfNewline(byte[] body, int from) {
		for (int i = from; i < body.length; i++) {
			if (body[i] == '\n') {
				return i;
			}
		}
		return -1;
	}

	public static class MalformedEnvelopeException extends RuntimeException {
		public MalformedEnvelopeException(String message) {
			super(message);
		}
	}

	public static class OversizeException extends RuntimeException {
		public OversizeException(String message) {
			super(message);
		}
	}
}
