package dev.outpost.bench;

import static dev.outpost.bench.ReportWriter.number;
import static dev.outpost.bench.ReportWriter.quote;
import static dev.outpost.bench.ReportWriter.round;

import dev.outpost.support.PlanFacts;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The <b>retrieval</b> benchmark's columns: latency percentiles beside the plan
 * facts of the same query.
 *
 * <p>Carrying both is the deliberate improvement over the ingest report. Latency
 * alone cannot distinguish an O(rows) plan from a pruning failure from a sort
 * spilling to disk from plain cold I/O — four different follow-up issues that all
 * read as "slow". Shared hits, shared reads, partitions scanned and temp I/O do
 * distinguish them, they are machine-independent, and they all come out of the
 * single {@code EXPLAIN} the guards already parse, so the extra columns cost
 * nothing.
 *
 * <p>The latency columns carry no threshold anywhere. They are for comparing a
 * before against an after on one machine, and the header says so — a percentile
 * table without that caveat gets quoted as a capacity claim.
 */
public final class RetrievalReport {

	/**
	 * One scenario's outcome.
	 *
	 * @param rowsReturned rows in the last response, so an empty result cannot pass
	 * for a fast one
	 * @param plan facts for the same query, or {@link PlanFacts#NONE} when the
	 * scenario has no single statement behind it
	 */
	public record Row(String scenario, String step, LoadDriver.Result load, long datasetRows, long rowsReturned,
			PlanFacts plan) {
	}

	private final ReportWriter writer;

	private final List<Row> rows = new ArrayList<>();

	public RetrievalReport(String title) {
		this.writer = new ReportWriter(title, "retrieval-benchmark");
	}

	public RetrievalReport condition(String key, Object value) {
		writer.condition(key, value);
		return this;
	}

	public void add(Row row) {
		rows.add(row);
	}

	public Path write() {
		return writer.write(markdown(), json());
	}

	private String markdown() {
		StringBuilder out = writer.markdownHeader("""
				**The latency columns are a same-machine before/after, not a capacity statement.** The
				load driver shares a JVM and a machine with the server and with Postgres, so its virtual
				threads compete with Tomcat for CPU and its page cache competes with the dataset. Nothing
				here has a pass/fail threshold: the run asserts correctness and run validity — status,
				page size, well-formed cursors, no row repeated across adjacent pages — and reports the
				timing.

				**The plan columns do not move when the machine is busy**, which is what makes them worth
				more than the latency beside them. Read a slow row by its plan first:

				- `shared hit` + `shared read` far above the page size → an O(rows) plan, not a slow disk.
				- `parts` (weekly partitions read) climbing with retention on a time-bounded query →
				  pruning is not happening.
				- `temp` above zero on a normal page → a sort or hash that did not fit in `work_mem`.
				- high `shared read` with low `shared hit` → cold cache, and only that.

				`rows` is what the last response actually returned. A deep-pagination scenario that
				quietly ran out of rows would otherwise report the fastest numbers in the table.

				""");
		out.append("\n## Results\n\n");
		out.append("| scenario | step | requests | non-200 | p50 ms | p95 ms | p99 ms | max ms | rows "
				+ "| shared hit | shared read | parts | temp |\n");
		out.append("|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|\n");
		for (Row row : rows) {
			LoadDriver.Result load = row.load();
			PlanFacts plan = row.plan();
			out.append("| ").append(row.scenario()).append(" | ").append(row.step());
			out.append(" | ").append(load.offered());
			out.append(" | ").append(nonSuccess(load));
			out.append(" | ").append(round(load.p50Millis()));
			out.append(" | ").append(round(load.p95Millis()));
			out.append(" | ").append(round(load.p99Millis()));
			out.append(" | ").append(round(load.maxMillis()));
			out.append(" | ").append(row.rowsReturned());
			out.append(" | ").append(plan.sharedHits());
			out.append(" | ").append(plan.sharedReads());
			out.append(" | ").append(partitionsScanned(plan));
			out.append(" | ").append(plan.tempBlocks());
			out.append(" |\n");
		}
		out.append("\nDataset: ").append(rows.isEmpty() ? 0 : rows.get(0).datasetRows()).append(" telemetry rows.\n");
		return out.toString();
	}

	private String json() {
		StringBuilder out = writer.jsonHeader();
		String separator = "";
		for (Row row : rows) {
			LoadDriver.Result load = row.load();
			PlanFacts plan = row.plan();
			out.append(separator).append("\n    {");
			out.append("\"scenario\": ").append(quote(row.scenario()));
			out.append(", \"step\": ").append(quote(row.step()));
			out.append(", \"requests\": ").append(load.offered());
			out.append(", \"non_success\": ").append(nonSuccess(load));
			out.append(", \"p50_ms\": ").append(number(load.p50Millis()));
			out.append(", \"p95_ms\": ").append(number(load.p95Millis()));
			out.append(", \"p99_ms\": ").append(number(load.p99Millis()));
			out.append(", \"max_ms\": ").append(number(load.maxMillis()));
			out.append(", \"dataset_rows\": ").append(row.datasetRows());
			out.append(", \"rows_returned\": ").append(row.rowsReturned());
			out.append(", \"shared_hit_blocks\": ").append(plan.sharedHits());
			out.append(", \"shared_read_blocks\": ").append(plan.sharedReads());
			out.append(", \"partitions_scanned\": ").append(partitionsScanned(plan));
			out.append(", \"temp_blocks\": ").append(plan.tempBlocks());
			out.append('}');
			separator = ",";
		}
		out.append("\n  ]\n}\n");
		return out.toString();
	}

	/** Every non-200 counts, including driver-side failures — both invalidate the row. */
	private static long nonSuccess(LoadDriver.Result load) {
		return load.offered() - load.status(200);
	}

	/** Weekly partitions read across all four telemetry tables — the pruning column. */
	private static long partitionsScanned(PlanFacts plan) {
		return plan.relationsScanned().stream().filter(relation -> relation.matches(".*_p\\d{8}$")).count();
	}

}
