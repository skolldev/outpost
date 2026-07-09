package dev.outpost.symbolication;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.outpost.artifacts.DebugId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Ingest-time JS symbolication (§6.2), synchronous within the worker.
 * Mutates the event's exception stacktraces in place: original frames are
 * preserved as {@code raw_stacktrace} (like Sentry), mapped frames get
 * original file/line/column, source context from {@code sourcesContent},
 * and the in_app heuristic. Lookup misses are recorded under
 * {@code _outpost_symbolication.missing} for the UI warning banner.
 */
@Component
public class Symbolicator {

	public static final String STATUS_NONE = "none";
	public static final String STATUS_SYMBOLICATED = "symbolicated";
	public static final String STATUS_PARTIAL = "partial";
	public static final String STATUS_MISSING_SOURCEMAP = "missing_sourcemap";

	private static final int CONTEXT_LINES = 5;

	private final ArtifactRepository artifacts;

	public Symbolicator(ArtifactRepository artifacts) {
		this.artifacts = artifacts;
	}

	/**
	 * Symbolicates all exception stacktraces of the event; returns the status
	 * to store. Events without sourcemap debug images (e.g. JVM events, which
	 * arrive already symbolic) come back {@code none} untouched.
	 */
	public String symbolicate(ObjectNode event, long projectId) {
		Map<String, String> debugIdByFile = debugImages(event);
		JsonNode exceptions = event.path("exception").path("values");
		String release = event.path("release").asText(null);
		if (!exceptions.isArray() || exceptions.isEmpty()) {
			return STATUS_NONE;
		}

		int mapped = 0;
		int missed = 0;
		boolean sawJsFrame = false;
		// debug_id (or file name when unknown) → example abs_path, for the UI banner
		Map<String, String> missing = new LinkedHashMap<>();
		Map<String, Optional<SourceMapConsumer>> consumers = new HashMap<>();

		for (JsonNode exceptionNode : exceptions) {
			if (!(exceptionNode instanceof ObjectNode exception)) {
				continue;
			}
			JsonNode frames = exception.path("stacktrace").path("frames");
			if (!frames.isArray() || frames.isEmpty()) {
				continue;
			}
			ArrayNode newFrames = exception.arrayNode();
			boolean touched = false;
			for (JsonNode frameNode : frames) {
				ObjectNode frame = (ObjectNode) frameNode.deepCopy();
				String absPath = frame.path("abs_path").asText(frame.path("filename").asText(null));
				int lineno = frame.path("lineno").asInt(-1);
				int colno = frame.path("colno").asInt(-1);
				if (absPath == null || lineno < 1 || colno < 1 || !looksLikeJs(absPath)) {
					newFrames.add(frame);
					continue;
				}
				sawJsFrame = true;
				String debugId = debugIdByFile.get(absPath);
				Optional<SourceMapConsumer> consumer = consumers.computeIfAbsent(
						debugId != null ? debugId : absPath,
						key -> lookup(debugId, projectId, release, absPath));
				SourceMapConsumer.OriginalPosition position = consumer
					.map(c -> c.find(lineno - 1, colno - 1))
					.orElse(null);
				if (position == null) {
					missing.putIfAbsent(debugId != null ? debugId : fileName(absPath), absPath);
					missed++;
					newFrames.add(frame);
					continue;
				}
				applyMapping(frame, position);
				mapped++;
				touched = true;
				newFrames.add(frame);
			}
			if (touched) {
				JsonNode original = exception.get("stacktrace");
				ObjectNode symbolicated = exception.objectNode();
				original.properties().forEach(entry -> {
					if (!entry.getKey().equals("frames")) {
						symbolicated.set(entry.getKey(), entry.getValue());
					}
				});
				symbolicated.set("frames", newFrames);
				exception.set("raw_stacktrace", original);
				exception.set("stacktrace", symbolicated);
			}
		}

		if (!sawJsFrame) {
			return STATUS_NONE;
		}
		String status = mapped == 0 ? STATUS_MISSING_SOURCEMAP : missed == 0 ? STATUS_SYMBOLICATED : STATUS_PARTIAL;
		if (!missing.isEmpty()) {
			ObjectNode info = event.putObject("_outpost_symbolication");
			info.put("status", status);
			ArrayNode missingList = info.putArray("missing");
			missing.forEach((debugId, absPath) -> missingList.addObject()
				.put("debug_id", debugId)
				.put("abs_path", absPath));
		}
		return status;
	}

	private Optional<SourceMapConsumer> lookup(String debugId, long projectId, String release, String absPath) {
		if (debugId != null) {
			Optional<SourceMapConsumer> byId = artifacts.findByDebugId(debugId);
			if (byId.isPresent()) {
				return byId;
			}
		}
		if (release != null && !release.isBlank()) {
			return artifacts.findByReleaseAndFileName(projectId, release, fileName(absPath));
		}
		return Optional.empty();
	}

	private void applyMapping(ObjectNode frame, SourceMapConsumer.OriginalPosition position) {
		String path = normalizeSourcePath(position.source());
		frame.put("filename", path);
		frame.put("lineno", position.line() + 1);
		frame.put("colno", position.column() + 1);
		if (position.name() != null && !position.name().isBlank()) {
			frame.put("function", position.name());
		}
		frame.put("in_app", isInApp(path));
		if (position.sourceContent() != null) {
			attachContext(frame, position.sourceContent(), position.line());
		}
	}

	private void attachContext(ObjectNode frame, String sourceContent, int line) {
		String[] lines = sourceContent.split("\n", -1);
		if (line < 0 || line >= lines.length) {
			return;
		}
		ArrayNode pre = frame.putArray("pre_context");
		for (int i = Math.max(0, line - CONTEXT_LINES); i < line; i++) {
			pre.add(stripCr(lines[i]));
		}
		frame.put("context_line", stripCr(lines[line]));
		ArrayNode post = frame.putArray("post_context");
		for (int i = line + 1; i <= Math.min(lines.length - 1, line + CONTEXT_LINES); i++) {
			post.add(stripCr(lines[i]));
		}
	}

	/** code_file → debug_id from debug_meta.images[] of type sourcemap (§6.2). */
	private Map<String, String> debugImages(JsonNode event) {
		Map<String, String> byFile = new HashMap<>();
		for (JsonNode image : event.path("debug_meta").path("images")) {
			if ("sourcemap".equals(image.path("type").asText(null)) && image.hasNonNull("code_file")
					&& image.hasNonNull("debug_id")) {
				byFile.put(image.get("code_file").asText(), DebugId.normalize(image.get("debug_id").asText()));
			}
		}
		return byFile;
	}

	/** Strip bundler prefixes ({@code webpack://<project>/}) and leading {@code ./} for display and matching. */
	static String normalizeSourcePath(String source) {
		String path = source;
		int scheme = path.indexOf("://");
		if (scheme >= 0) {
			path = path.substring(scheme + 3);
			int slash = path.indexOf('/');
			path = slash >= 0 ? path.substring(slash + 1) : path;
		}
		while (path.startsWith("./") || path.startsWith("../")) {
			path = path.substring(path.indexOf('/') + 1);
		}
		return path;
	}

	/**
	 * §6.2 heuristic: node_modules is never in-app; application sources are
	 * {@code src/…} — plain for Angular's esbuild output, {@code webpack://…}
	 * prefixes already stripped by {@link #normalizeSourcePath}.
	 */
	static boolean isInApp(String normalizedPath) {
		if (normalizedPath.contains("node_modules")) {
			return false;
		}
		return normalizedPath.startsWith("src/") || normalizedPath.contains("/src/");
	}

	private static boolean looksLikeJs(String absPath) {
		String path = absPath.toLowerCase(Locale.ROOT);
		int query = path.indexOf('?');
		if (query >= 0) {
			path = path.substring(0, query);
		}
		return path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".cjs");
	}

	private static String fileName(String absPath) {
		String path = absPath;
		int query = path.indexOf('?');
		if (query >= 0) {
			path = path.substring(0, query);
		}
		return path.substring(path.lastIndexOf('/') + 1);
	}

	private static String stripCr(String line) {
		return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
	}
}
