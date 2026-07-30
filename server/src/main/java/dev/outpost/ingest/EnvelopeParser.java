package dev.outpost.ingest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Streams the newline-delimited Sentry envelope format. At most one item payload
 * is materialized at a time, so parsing never retains the whole envelope.
 */
@Component
public class EnvelopeParser {

	private static final Logger log = LoggerFactory.getLogger(EnvelopeParser.class);

	public static final int MAX_ITEM_BYTES = 1024 * 1024;

	private final ObjectMapper mapper;

	public EnvelopeParser(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * Parses one envelope, invoking {@code items} as each bounded payload is read.
	 * Returns the envelope header after the entire body has been validated.
	 */
	public JsonNode parse(InputStream input, Consumer<Envelope.Item> items) throws IOException {
		PushbackInputStream in = new PushbackInputStream(input, 1);
		byte[] headerLine = readLine(in, "envelope header");
		if (headerLine == null) {
			headerLine = new byte[0];
		}
		JsonNode header = parseJson(headerLine, "envelope header");

		byte[] itemHeaderLine;
		while ((itemHeaderLine = readLine(in, "item header")) != null) {
			if (itemHeaderLine.length == 0) {
				continue;
			}
			JsonNode itemHeader = parseJson(itemHeaderLine, "item header");
			JsonNode length = itemHeader.get("length");
			if (length != null && length.isNumber()) {
				long payloadLength = length.asLong();
				if (payloadLength < 0) {
					throw new MalformedEnvelopeException("item length out of bounds");
				}
				if (payloadLength > MAX_ITEM_BYTES) {
					if (!"attachment".equals(itemHeader.path("type").asText())) {
						throw new OversizeException("item exceeds " + MAX_ITEM_BYTES + " bytes");
					}
					skipExactly(in, payloadLength);
					consumeOptionalNewline(in);
					continue;
				}
				byte[] payload = in.readNBytes((int) payloadLength);
				if (payload.length != payloadLength) {
					throw new MalformedEnvelopeException("item length out of bounds");
				}
				consumeOptionalNewline(in);
				items.accept(new Envelope.Item(itemHeader, payload));
			}
			else {
				byte[] payload = readLine(in, "item");
				items.accept(new Envelope.Item(itemHeader, payload == null ? new byte[0] : payload));
			}
		}
		return header;
	}

	public JsonNode parseObjectPayload(Envelope.Item item) {
		try {
			JsonNode node = mapper.readTree(item.payload());
			return node != null && node.isObject() ? node : null;
		}
		catch (tools.jackson.core.JacksonException e) {
			log.debug("dropping item with malformed JSON payload: {}", e.getMessage());
			return null;
		}
	}

	private byte[] readLine(InputStream in, String what) throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		boolean oversize = false;
		while (true) {
			int value = in.read();
			if (value < 0) {
				if (oversize) {
					throw new OversizeException(what + " exceeds " + MAX_ITEM_BYTES + " bytes");
				}
				return line.size() == 0 ? null : line.toByteArray();
			}
			if (value == '\n') {
				if (oversize) {
					throw new OversizeException(what + " exceeds " + MAX_ITEM_BYTES + " bytes");
				}
				return line.toByteArray();
			}
			if (line.size() == MAX_ITEM_BYTES) {
				oversize = true;
			}
			if (!oversize) {
				line.write(value);
			}
		}
	}

	private void skipExactly(InputStream in, long length) throws IOException {
		byte[] buffer = new byte[8192];
		long remaining = length;
		while (remaining > 0) {
			int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
			if (read < 0) {
				throw new MalformedEnvelopeException("item length out of bounds");
			}
			remaining -= read;
		}
	}

	private void consumeOptionalNewline(PushbackInputStream in) throws IOException {
		int value = in.read();
		if (value >= 0 && value != '\n') {
			in.unread(value);
		}
	}

	private JsonNode parseJson(byte[] bytes, String what) {
		try {
			JsonNode node = mapper.readTree(bytes);
			if (node == null || !node.isObject()) {
				throw new MalformedEnvelopeException(what + " is not a JSON object");
			}
			return node;
		}
		catch (tools.jackson.core.JacksonException e) {
			throw new MalformedEnvelopeException(what + " is not valid JSON");
		}
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
