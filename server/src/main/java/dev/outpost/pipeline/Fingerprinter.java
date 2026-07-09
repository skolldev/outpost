package dev.outpost.pipeline;

import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Computes the grouping fingerprint of an error event (§6.2), in priority order:
 * SDK-provided {@code fingerprint} array (with {@code {{ default }}}
 * substitution), exception-based (in-app frames + type, message excluded),
 * message fallback with variable data replaced by placeholders.
 */
final class Fingerprinter {

	private Fingerprinter() {
	}

	static String fingerprint(JsonNode event) {
		List<String> parts = new ArrayList<>();
		JsonNode custom = event.get("fingerprint");
		if (custom != null && custom.isArray() && !custom.isEmpty()) {
			for (JsonNode part : custom) {
				String text = part.asText();
				if ("{{ default }}".equals(text) || "{{default}}".equals(text)) {
					parts.addAll(defaultParts(event));
				}
				else {
					parts.add(text);
				}
			}
		}
		else {
			parts.addAll(defaultParts(event));
		}
		return sha256(String.join("\0", parts));
	}

	private static List<String> defaultParts(JsonNode event) {
		JsonNode values = event.path("exception").path("values");
		if (values.isArray() && !values.isEmpty()) {
			return exceptionParts(values);
		}
		return List.of("message", normalizeMessage(EventFields.message(event)));
	}

	private static List<String> exceptionParts(JsonNode values) {
		List<String> parts = new ArrayList<>();
		JsonNode primary = values.get(values.size() - 1);
		parts.add("type:" + primary.path("type").asText(""));

		List<JsonNode> frames = new ArrayList<>();
		for (JsonNode value : values) {
			JsonNode valueFrames = value.path("stacktrace").path("frames");
			if (valueFrames.isArray()) {
				valueFrames.forEach(frames::add);
			}
		}
		List<JsonNode> inApp = frames.stream().filter(f -> f.path("in_app").asBoolean(false)).toList();
		// No in-app frames (fully vendored trace): fall back to all frames so the
		// fingerprint still reflects the stack rather than just the type.
		for (JsonNode frame : inApp.isEmpty() ? frames : inApp) {
			String module = frame.hasNonNull("module") ? frame.get("module").asText()
					: frame.path("filename").asText("");
			parts.add(FrameNormalizer.normalizeModule(module) + "|"
					+ FrameNormalizer.normalizeFunction(frame.path("function").asText("")));
		}
		return parts;
	}

	/** Masks variable data (ids, numbers, hex) so it doesn't fragment message-fallback grouping. */
	static String normalizeMessage(String message) {
		if (message == null) {
			return "";
		}
		return message
			.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "<uuid>")
			.replaceAll("\\b0x[0-9a-fA-F]+\\b", "<hex>")
			.replaceAll("\\b[0-9a-fA-F]{8,}\\b", "<hex>")
			.replaceAll("\\d+", "<num>");
	}

	private static String sha256(String input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
