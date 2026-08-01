package dev.outpost.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The plumbing {@link BenchReport} and {@link RetrievalReport} share. Two of
 * these look pedantic and are not: the ingest report's output is quoted in PR
 * bodies and diffed across runs, so the header layout is a contract, and a
 * comma-decimal default locale silently emits JSON nothing can parse.
 */
class ReportWriterTest {

	@Test
	void writesTheHeaderCaveatsAndConditionsInThatOrder() {
		String markdown = new ReportWriter("Outpost ingest benchmark", "ingest-benchmark").condition("queue_capacity",
				50_000).markdownHeader("Caveats go here.\n\n").toString();

		assertThat(markdown).startsWith("# Outpost ingest benchmark\n\n_Generated ");
		assertThat(markdown).contains("._\n\nCaveats go here.\n\n## Conditions\n\n");
		assertThat(markdown).endsWith("- `queue_capacity`: 50000\n");
	}

	/** A table detached from the machine it ran on is worse than no table. */
	@Test
	void alwaysRecordsTheHostAndRuntimeWithoutBeingAsked() {
		String markdown = new ReportWriter("t", "d").markdownHeader("").toString();

		assertThat(markdown).contains("- `host_cpus`: ").contains("- `max_heap_mb`: ").contains("- `java`: ");
	}

	@Test
	void opensTheJsonDocumentWithTheSameConditions() {
		String json = new ReportWriter("t", "d").condition("bench_scale", 0.1).jsonHeader().toString();

		assertThat(json).startsWith("{\n  \"title\": \"t\",\n  \"generated_at\": \"");
		assertThat(json).contains("\"bench_scale\": \"0.1\"");
		assertThat(json).endsWith("\n  },\n  \"rows\": [");
	}

	/**
	 * The reason {@code Locale.ROOT} is pinned rather than left to the default: on a
	 * German or French machine the default would render {@code 1,5}, and every
	 * number in the JSON copy would be a syntax error.
	 */
	@Test
	void formatsNumbersLocaleIndependently() {
		Locale original = Locale.getDefault();
		try {
			Locale.setDefault(Locale.GERMANY);

			assertThat(ReportWriter.round(1.25)).isEqualTo("1.3");
			assertThat(ReportWriter.number(1.25)).isEqualTo("1.250");
		}
		finally {
			Locale.setDefault(original);
		}
	}

	/** A missing figure has to read as missing, not as zero. */
	@Test
	void distinguishesAnAbsentFigureFromZero() {
		assertThat(ReportWriter.round(Double.NaN)).isEqualTo("—");
		assertThat(ReportWriter.number(Double.NaN)).isEqualTo("null");
		assertThat(ReportWriter.round(0.0)).isEqualTo("0.0");
	}

	@Test
	void escapesQuotesAndBackslashesInJsonStrings() {
		assertThat(ReportWriter.quote("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
	}

	/** The two reports must not write over each other's files. */
	@Test
	void keepsEachBenchmarksReportsInItsOwnDirectory() {
		assertThat(new ReportWriter("i", "ingest-benchmark").directory().getFileName())
			.hasToString("ingest-benchmark");
		assertThat(new ReportWriter("r", "retrieval-benchmark").directory().getFileName())
			.hasToString("retrieval-benchmark");
	}

	/** The ingest table's columns are quoted in `measuring-ingest.md`; the split must not have moved them. */
	@Test
	void keepsTheIngestTableColumnsUnchanged() {
		BenchReport report = new BenchReport("Outpost ingest benchmark");
		report.add(new BenchReport.Row("error", "200/s",
				new LoadDriver.Result(200, 3000, 2999, 199.4, Map.of(200, 2900L), Map.of(), 0, 1.25, 9.5, 22.75, 190.5),
				812.5, 1234, 44.25, 1_234_567_890L));

		String markdown = read(report.write());

		String header = "| scenario | step | offered/s | scheduled/s | dispatched/s | 200 | 429 | failed | shed "
				+ "| accepted/s | stored/s | p50 ms | p95 ms | p99 ms | max ms | queue depth | queue wait avg ms "
				+ "| alloc MB | alloc KB/env |";
		assertThat(markdown).contains(header);
		// The data row has to line up with that header, or the table renders shifted.
		assertThat(markdown).contains("| error | 200/s | 200 | 199.4 |");
		assertThat(columnsOf(markdown, "| error |")).isEqualTo(columnsOf(header, "| scenario |"));
	}

	private static int columnsOf(String markdown, String rowPrefix) {
		return markdown.lines()
			.filter(line -> line.startsWith(rowPrefix))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no row starting " + rowPrefix))
			.split("\\|", -1).length;
	}

	private static String read(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
