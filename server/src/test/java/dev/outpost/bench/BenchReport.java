package dev.outpost.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Collects benchmark rows and writes them to stdout plus a timestamped
 * Markdown and JSON pair under {@code build/reports/ingest-benchmark/}. The
 * Markdown is meant to be pasted into a PR body — {@code TODO.md} item #3 asks
 * for exactly that, a stated before/after under a repeatable benchmark.
 *
 * <p>The written header carries the caveats deliberately, because a throughput
 * table detached from its conditions is worse than no table.
 */
public final class BenchReport {

	/**
	 * One rate plateau's outcome. Storage, queue and allocation figures are
	 * {@link Double#NaN} / -1 when the run cannot see them (the remote driver has
	 * no DB handle and cannot sample the target's threads).
	 *
	 * @param allocatedBytes bytes allocated by server-side threads over the
	 * plateau <em>and its drain</em> — see {@link AllocationProbe}
	 */
	public record Row(String scenario, String step, LoadDriver.Result load, double storedPerSecond, long queueDepthAtEnd,
			double queueWaitAverageMillis, long allocatedBytes) {
	}

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
		.withZone(ZoneOffset.UTC);

	private final String title;

	private final Map<String, String> conditions = new LinkedHashMap<>();

	private final List<Row> rows = new ArrayList<>();

	public BenchReport(String title) {
		this.title = title;
		conditions.put("host_cpus", String.valueOf(Runtime.getRuntime().availableProcessors()));
		conditions.put("max_heap_mb", String.valueOf(Runtime.getRuntime().maxMemory() / (1024 * 1024)));
		conditions.put("java", System.getProperty("java.version"));
	}

	public BenchReport condition(String key, Object value) {
		conditions.put(key, String.valueOf(value));
		return this;
	}

	public void add(Row row) {
		rows.add(row);
	}

	/** Prints the table and writes it alongside a JSON copy; returns the Markdown path. */
	public Path write() {
		String markdown = markdown();
		System.out.println();
		System.out.println(markdown);
		Path directory = Path.of("build", "reports", "ingest-benchmark");
		String stamp = STAMP.format(Instant.now());
		try {
			Files.createDirectories(directory);
			Path markdownPath = directory.resolve(stamp + ".md");
			Files.writeString(markdownPath, markdown);
			Files.writeString(directory.resolve(stamp + ".json"), json());
			System.out.println("report written to " + markdownPath.toAbsolutePath());
			return markdownPath;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private String markdown() {
		StringBuilder out = new StringBuilder();
		out.append("# ").append(title).append("\n\n");
		out.append("_Generated ").append(Instant.now()).append("._\n\n");
		out.append("""
				**Read these numbers as relative, not as a capacity statement.** Unless run against a
				remote target, the load driver shares a JVM and a machine with the server, so its
				virtual threads compete with Tomcat and the ingest workers for CPU. That makes the
				figures a floor, and reproducible enough to compare a before against an after on one
				machine — which is what they are for.

				`accepted/s` is what the endpoint acknowledged; `stored/s` is what actually reached
				Postgres. When accepted greatly exceeds stored, the run is filling a buffer rather
				than ingesting, and `stored/s` is the honest capacity number.

				`alloc KB/env` counts bytes allocated on Tomcat's request threads and the ingest
				workers, divided by the envelopes they handled (200s and 429s alike — a rejected
				envelope was still parsed). It covers the plateau *and* the drain that follows it,
				so the store-side cost of a backlog is attributed to the step that created it.
				Unlike `stored/s` it is not a rate, and it is GC-independent, so it is the column to
				quote when a change claims to have removed a copy.

				""");
		out.append("## Conditions\n\n");
		conditions.forEach((key, value) -> out.append("- `").append(key).append("`: ").append(value).append('\n'));
		out.append("\n## Results\n\n");
		out.append("`scheduled/s` is the pace requested by the benchmark; `dispatched/s` excludes work the load "
				+ "driver shed before opening a connection. Any non-zero `failed` or `shed` value invalidates a "
				+ "server-capacity comparison.\n\n");
		out.append("| scenario | step | offered/s | scheduled/s | dispatched/s | 200 | 429 | failed | shed "
				+ "| accepted/s | stored/s | p50 ms | p95 ms | p99 ms | max ms | queue depth "
				+ "| queue wait avg ms | alloc MB | alloc KB/env |\n");
		out.append("|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|\n");
		for (Row row : rows) {
			LoadDriver.Result load = row.load();
			out.append("| ").append(row.scenario()).append(" | ").append(row.step());
			out.append(" | ").append(load.targetRate());
			out.append(" | ").append(round(load.achievedOfferRate()));
			out.append(" | ").append(round(load.achievedDispatchRate()));
			out.append(" | ").append(load.status(200));
			out.append(" | ").append(load.status(429));
			out.append(" | ").append(load.failures());
			out.append(" | ").append(load.shed());
			out.append(" | ").append(round(load.status(200) / seconds(load)));
			out.append(" | ").append(round(row.storedPerSecond()));
			out.append(" | ").append(round(load.p50Millis()));
			out.append(" | ").append(round(load.p95Millis()));
			out.append(" | ").append(round(load.p99Millis()));
			out.append(" | ").append(round(load.maxMillis()));
			out.append(" | ").append(row.queueDepthAtEnd() < 0 ? "—" : row.queueDepthAtEnd());
			out.append(" | ").append(round(row.queueWaitAverageMillis()));
			out.append(" | ").append(round(allocatedMegabytes(row)));
			out.append(" | ").append(round(allocatedKilobytesPerEnvelope(row)));
			out.append(" |\n");
		}
		Map<String, Long> failures = new LinkedHashMap<>();
		for (Row row : rows) {
			row.load()
				.failureCounts()
				.forEach((cause, count) -> failures.merge(row.scenario() + " @ " + row.step() + " — " + cause, count,
						Long::sum));
		}
		if (!failures.isEmpty()) {
			out.append("\n### Failures\n\n");
			failures.forEach((cause, count) -> out.append("- ").append(count).append(" × ").append(cause).append('\n'));
		}
		return out.toString();
	}

	private static double seconds(LoadDriver.Result load) {
		return load.offered() / (double) load.targetRate();
	}

	private static double allocatedMegabytes(Row row) {
		return row.allocatedBytes() < 0 ? Double.NaN : row.allocatedBytes() / (1024.0 * 1024.0);
	}

	/**
	 * Normalized by envelopes the server actually handled, which includes the 429s:
	 * shedding happens after the parse, so a rejected envelope has already paid for
	 * one. Dividing by 200s alone would make an overloaded step look profligate.
	 */
	private static double allocatedKilobytesPerEnvelope(Row row) {
		long handled = row.load().status(200) + row.load().status(429);
		return (row.allocatedBytes() < 0 || handled == 0) ? Double.NaN : row.allocatedBytes() / 1024.0 / handled;
	}

	private static String round(double value) {
		return Double.isNaN(value) ? "—" : String.format(Locale.ROOT, "%.1f", value);
	}

	private String json() {
		StringBuilder out = new StringBuilder("{\n  \"title\": ").append(quote(title));
		out.append(",\n  \"generated_at\": ").append(quote(Instant.now().toString()));
		out.append(",\n  \"conditions\": {");
		String separator = "";
		for (Map.Entry<String, String> entry : conditions.entrySet()) {
			out.append(separator).append("\n    ").append(quote(entry.getKey())).append(": ")
				.append(quote(entry.getValue()));
			separator = ",";
		}
		out.append("\n  },\n  \"rows\": [");
		separator = "";
		for (Row row : rows) {
			LoadDriver.Result load = row.load();
			out.append(separator).append("\n    {");
			out.append("\"scenario\": ").append(quote(row.scenario()));
			out.append(", \"step\": ").append(quote(row.step()));
			out.append(", \"offered_per_second\": ").append(load.targetRate());
			out.append(", \"scheduled_per_second\": ").append(number(load.achievedOfferRate()));
			out.append(", \"dispatched_per_second\": ").append(number(load.achievedDispatchRate()));
			out.append(", \"status_200\": ").append(load.status(200));
			out.append(", \"status_429\": ").append(load.status(429));
			out.append(", \"failures\": ").append(load.failures());
			out.append(", \"shed\": ").append(load.shed());
			out.append(", \"stored_per_second\": ").append(number(row.storedPerSecond()));
			out.append(", \"p50_ms\": ").append(number(load.p50Millis()));
			out.append(", \"p95_ms\": ").append(number(load.p95Millis()));
			out.append(", \"p99_ms\": ").append(number(load.p99Millis()));
			out.append(", \"max_ms\": ").append(number(load.maxMillis()));
			out.append(", \"queue_depth_at_end\": ").append(row.queueDepthAtEnd());
			out.append(", \"queue_wait_average_ms\": ").append(number(row.queueWaitAverageMillis()));
			out.append(", \"allocated_bytes\": ").append(row.allocatedBytes());
			out.append(", \"allocated_kb_per_envelope\": ").append(number(allocatedKilobytesPerEnvelope(row)));
			out.append('}');
			separator = ",";
		}
		out.append("\n  ]\n}\n");
		return out.toString();
	}

	/** {@link Locale#ROOT}, or a comma-decimal default locale emits invalid JSON. */
	private static String number(double value) {
		return Double.isNaN(value) ? "null" : String.format(Locale.ROOT, "%.3f", value);
	}

	private static String quote(String value) {
		return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
	}

}
