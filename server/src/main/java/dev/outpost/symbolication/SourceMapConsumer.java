package dev.outpost.symbolication;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Source Map v3 consumer: standard and index ("sections") maps, base64-VLQ
 * mappings, {@code sourcesContent}. Own implementation per the §13.2 spike:
 * {@code com.atlassian.sourcemap} lacks index-map support and is only
 * published to Atlassian's own Maven repository.
 *
 * <p>Lookup follows the "greatest mapping at or before the generated
 * position" rule of Chrome DevTools / Node's {@code node:module} SourceMap,
 * which the unit test uses as the reference implementation. All positions
 * are 0-based (Sentry frames are 1-based; callers convert).
 */
public final class SourceMapConsumer {

	/** A resolved original position; {@code line}/{@code column} 0-based. */
	public record OriginalPosition(String source, int line, int column, String name, String sourceContent) {
	}

	// Segments flattened across sections, sorted by (generatedLine, generatedColumn).
	private final int[] genLines;
	private final int[] genCols;
	private final int[] srcIndexes; // -1 = segment carries no source
	private final int[] srcLines;
	private final int[] srcCols;
	private final int[] nameIndexes; // -1 = segment carries no name
	private final List<String> sources;
	private final List<String> sourcesContent;
	private final List<String> names;

	public static SourceMapConsumer parse(JsonNode map) {
		Builder builder = new Builder();
		if (map.path("sections").isArray()) {
			for (JsonNode section : map.get("sections")) {
				JsonNode offset = section.path("offset");
				builder.addMap(section.path("map"), offset.path("line").asInt(0), offset.path("column").asInt(0));
			}
		}
		else {
			builder.addMap(map, 0, 0);
		}
		return builder.build();
	}

	/** Greatest mapping at or before the generated position, or null before the first mapping. */
	public OriginalPosition find(int generatedLine, int generatedColumn) {
		int low = 0;
		int high = genLines.length - 1;
		int found = -1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (genLines[mid] < generatedLine
					|| (genLines[mid] == generatedLine && genCols[mid] <= generatedColumn)) {
				found = mid;
				low = mid + 1;
			}
			else {
				high = mid - 1;
			}
		}
		if (found < 0 || srcIndexes[found] < 0) {
			return null;
		}
		int src = srcIndexes[found];
		return new OriginalPosition(sources.get(src), srcLines[found], srcCols[found],
				nameIndexes[found] >= 0 ? names.get(nameIndexes[found]) : null, sourcesContent.get(src));
	}

	private SourceMapConsumer(Builder builder) {
		int n = builder.segments.size();
		genLines = new int[n];
		genCols = new int[n];
		srcIndexes = new int[n];
		srcLines = new int[n];
		srcCols = new int[n];
		nameIndexes = new int[n];
		// Generators emit segments in generated order, but the spec doesn't
		// guarantee it — sort to make binary search safe.
		builder.segments.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
		for (int i = 0; i < n; i++) {
			int[] segment = builder.segments.get(i);
			genLines[i] = segment[0];
			genCols[i] = segment[1];
			srcIndexes[i] = segment[2];
			srcLines[i] = segment[3];
			srcCols[i] = segment[4];
			nameIndexes[i] = segment[5];
		}
		sources = builder.sources;
		sourcesContent = builder.sourcesContent;
		names = builder.names;
	}

	private static final class Builder {

		final List<int[]> segments = new ArrayList<>();
		final List<String> sources = new ArrayList<>();
		final List<String> sourcesContent = new ArrayList<>();
		final List<String> names = new ArrayList<>();

		void addMap(JsonNode map, int lineOffset, int columnOffset) {
			int sourceBase = sources.size();
			int nameBase = names.size();
			String sourceRoot = map.path("sourceRoot").asText("");
			JsonNode sourceList = map.path("sources");
			JsonNode contentList = map.path("sourcesContent");
			for (int i = 0; i < sourceList.size(); i++) {
				String source = sourceList.get(i).asText(null);
				sources.add(source != null && !sourceRoot.isEmpty() ? joinSourceRoot(sourceRoot, source) : source);
				JsonNode content = contentList.isArray() && i < contentList.size() ? contentList.get(i) : null;
				sourcesContent.add(content != null && content.isString() ? content.asText() : null);
			}
			for (JsonNode name : map.path("names")) {
				names.add(name.asText());
			}
			decodeMappings(map.path("mappings").asText(""), lineOffset, columnOffset, sourceBase, nameBase);
		}

		private void decodeMappings(String mappings, int lineOffset, int columnOffset, int sourceBase, int nameBase) {
			int genLine = 0;
			int genCol = 0;
			int src = 0;
			int srcLine = 0;
			int srcCol = 0;
			int name = 0;
			int[] values = new int[5];
			int pos = 0;
			int length = mappings.length();
			while (pos < length) {
				char c = mappings.charAt(pos);
				if (c == ';') {
					genLine++;
					genCol = 0;
					pos++;
					continue;
				}
				if (c == ',') {
					pos++;
					continue;
				}
				int count = 0;
				while (pos < length && (c = mappings.charAt(pos)) != ';' && c != ',') {
					pos = decodeVlq(mappings, pos, values, count++);
				}
				genCol += values[0];
				// Per the index-map spec, the column offset applies only to the
				// section's first generated line.
				int outCol = genLine == 0 ? genCol + columnOffset : genCol;
				if (count >= 4) {
					src += values[1];
					srcLine += values[2];
					srcCol += values[3];
					if (count >= 5) {
						name += values[4];
					}
					segments.add(new int[] { genLine + lineOffset, outCol, sourceBase + src, srcLine, srcCol,
							count >= 5 ? nameBase + name : -1 });
				}
				else {
					segments.add(new int[] { genLine + lineOffset, outCol, -1, 0, 0, -1 });
				}
			}
		}

		/** Decodes one base64 VLQ value starting at {@code pos} into {@code values[index]}; returns the next position. */
		private static int decodeVlq(String mappings, int pos, int[] values, int index) {
			int result = 0;
			int shift = 0;
			while (true) {
				if (pos >= mappings.length()) {
					throw new IllegalArgumentException("truncated VLQ in mappings");
				}
				int digit = base64(mappings.charAt(pos++));
				result += (digit & 0x1f) << shift;
				if ((digit & 0x20) == 0) {
					break;
				}
				shift += 5;
			}
			boolean negative = (result & 1) == 1;
			result >>>= 1;
			values[index] = negative ? -result : result;
			return pos;
		}

		private static int base64(char c) {
			if (c >= 'A' && c <= 'Z') {
				return c - 'A';
			}
			if (c >= 'a' && c <= 'z') {
				return c - 'a' + 26;
			}
			if (c >= '0' && c <= '9') {
				return c - '0' + 52;
			}
			if (c == '+') {
				return 62;
			}
			if (c == '/') {
				return 63;
			}
			throw new IllegalArgumentException("invalid base64 character in mappings: " + c);
		}

		private static String joinSourceRoot(String root, String source) {
			return root.endsWith("/") ? root + source : root + "/" + source;
		}

		SourceMapConsumer build() {
			return new SourceMapConsumer(this);
		}
	}
}
