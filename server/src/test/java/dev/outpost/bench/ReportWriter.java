package dev.outpost.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Everything a benchmark report needs that is not its columns: the conditions
 * block, UTC stamping, the directory, the Markdown/JSON pair, and number
 * formatting.
 *
 * <p>The seam is deliberate. Two benchmarks measure completely different things —
 * ingest reports throughput, queue depth and allocation; retrieval reports
 * latency percentiles and plan facts — but the argument for <em>how</em> a
 * benchmark report should read is the same for both: a table detached from its
 * conditions is worse than no table, and a number whose formatting depends on the
 * default locale emits invalid JSON. Columns belong to each benchmark; this
 * belongs to neither.
 */
public final class ReportWriter {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
		.withZone(ZoneOffset.UTC);

	private final String title;

	private final String directory;

	private final Map<String, String> conditions = new LinkedHashMap<>();

	/** @param directory the leaf under {@code build/reports/} this report's files land in */
	public ReportWriter(String title, String directory) {
		this.title = title;
		this.directory = directory;
		conditions.put("host_cpus", String.valueOf(Runtime.getRuntime().availableProcessors()));
		conditions.put("max_heap_mb", String.valueOf(Runtime.getRuntime().maxMemory() / (1024 * 1024)));
		conditions.put("java", System.getProperty("java.version"));
	}

	public ReportWriter condition(String key, Object value) {
		conditions.put(key, String.valueOf(value));
		return this;
	}

	/**
	 * Title, generation stamp, the caller's caveats, and the conditions block —
	 * everything above the results table.
	 */
	public StringBuilder markdownHeader(String caveats) {
		StringBuilder out = new StringBuilder();
		out.append("# ").append(title).append("\n\n");
		out.append("_Generated ").append(Instant.now()).append("._\n\n");
		out.append(caveats);
		out.append("## Conditions\n\n");
		conditions.forEach((key, value) -> out.append("- `").append(key).append("`: ").append(value).append('\n'));
		return out;
	}

	/** The JSON document up to and including the opening of the {@code rows} array. */
	public StringBuilder jsonHeader() {
		StringBuilder out = new StringBuilder("{\n  \"title\": ").append(quote(title));
		out.append(",\n  \"generated_at\": ").append(quote(Instant.now().toString()));
		out.append(",\n  \"conditions\": {");
		String separator = "";
		for (Map.Entry<String, String> entry : conditions.entrySet()) {
			out.append(separator)
				.append("\n    ")
				.append(quote(entry.getKey()))
				.append(": ")
				.append(quote(entry.getValue()));
			separator = ",";
		}
		out.append("\n  },\n  \"rows\": [");
		return out;
	}

	/** Where this report's files land, without writing any. */
	public Path directory() {
		return Path.of("build", "reports", directory);
	}

	/** Prints the table and writes it alongside a JSON copy; returns the Markdown path. */
	public Path write(String markdown, String json) {
		System.out.println();
		System.out.println(markdown);
		Path target = directory();
		String stamp = STAMP.format(Instant.now());
		try {
			Files.createDirectories(target);
			Path markdownPath = target.resolve(stamp + ".md");
			Files.writeString(markdownPath, markdown);
			Files.writeString(target.resolve(stamp + ".json"), json);
			System.out.println("report written to " + markdownPath.toAbsolutePath());
			return markdownPath;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static String round(double value) {
		return Double.isNaN(value) ? "—" : String.format(Locale.ROOT, "%.1f", value);
	}

	/** {@link Locale#ROOT}, or a comma-decimal default locale emits invalid JSON. */
	public static String number(double value) {
		return Double.isNaN(value) ? "null" : String.format(Locale.ROOT, "%.3f", value);
	}

	public static String quote(String value) {
		return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
	}

}
