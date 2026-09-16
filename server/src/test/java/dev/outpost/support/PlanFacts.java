package dev.outpost.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The machine-independent facts of one {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT
 * JSON)}: logical I/O, which relations were read and how, and whether anything
 * spilled to disk. Buffer counts are summed across the whole plan tree, so the
 * total is a comparable index of I/O rather than a literal block count, and a
 * partition pruned at runtime ({@code "Actual Loops": 0}) does not count as scanned.
 */
public record PlanFacts(long sharedHits, long sharedReads, long tempReadBlocks, long tempWrittenBlocks,
		Set<String> relationsScanned, Set<String> sequentiallyScanned, Set<String> indexesUsed, Set<String> nodeTypes,
		Set<String> correlatedSubplans, String plan) {

	/** The identity for {@link #merge}: a scenario whose plan facts are not yet known. */
	public static final PlanFacts NONE = new PlanFacts(0, 0, 0, 0, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), "");

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Runs the statement under {@code EXPLAIN} and parses what came back. */
	public static PlanFacts explain(JdbcClient jdbc, String sql, List<Object> params) {
		String json = jdbc.sql("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)
			.params(params)
			.query((rs, i) -> rs.getString(1))
			.single();
		return parse(json);
	}

	/** Parses the JSON document {@code EXPLAIN (FORMAT JSON)} returns. */
	public static PlanFacts parse(String planJson) {
		JsonNode root = MAPPER.readTree(planJson);
		Accumulator accumulator = new Accumulator(planJson);
		for (JsonNode statement : root.isArray() ? root : List.of(root)) {
			accumulator.addBuffers(statement.get("Planning"));
			accumulator.visit(statement.get("Plan"));
		}
		return accumulator.toFacts();
	}

	/** Combines the facts of several statements into one row's worth of cost. */
	public PlanFacts merge(PlanFacts other) {
		Set<String> relations = new TreeSet<>(relationsScanned);
		relations.addAll(other.relationsScanned());
		Set<String> sequential = new TreeSet<>(sequentiallyScanned);
		sequential.addAll(other.sequentiallyScanned());
		Set<String> indexes = new TreeSet<>(indexesUsed);
		indexes.addAll(other.indexesUsed());
		Set<String> nodes = new TreeSet<>(nodeTypes);
		nodes.addAll(other.nodeTypes());
		Set<String> subplans = new TreeSet<>(correlatedSubplans);
		subplans.addAll(other.correlatedSubplans());
		return new PlanFacts(sharedHits + other.sharedHits(), sharedReads + other.sharedReads(),
				tempReadBlocks + other.tempReadBlocks(), tempWrittenBlocks + other.tempWrittenBlocks(), relations,
				sequential, indexes, nodes, subplans, plan.isEmpty() ? other.plan() : plan + "\n" + other.plan());
	}

	/** Whether the executor ran a node of this type, e.g. {@code "Sort"} or {@code "Seq Scan"}. */
	public boolean ran(String nodeType) {
		return nodeTypes.contains(nodeType);
	}

	/**
	 * Every shared block the query touched, whether cached or read from disk.
	 * Counting only the hits would let a cold cache pass a runaway plan.
	 */
	public long logicalIo() {
		return sharedHits + sharedReads;
	}

	/** Blocks that went through a temp file — a sort or hash that did not fit in {@code work_mem}. */
	public long tempBlocks() {
		return tempReadBlocks + tempWrittenBlocks;
	}

	/**
	 * The weekly partitions of {@code table} this plan actually read. Empty means the
	 * table was untouched, which is not the same as "pruned to nothing" — assert the
	 * query returned rows too.
	 */
	public Set<String> partitionsScanned(String table) {
		Set<String> partitions = new TreeSet<>();
		for (String relation : relationsScanned) {
			if (relation.startsWith(table + "_p")) {
				partitions.add(relation);
			}
		}
		return partitions;
	}

	/** Sequential scans of {@code table} or any of its partitions. */
	public Set<String> sequentialScansOf(String table) {
		Set<String> scans = new TreeSet<>();
		for (String relation : sequentiallyScanned) {
			if (relation.equals(table) || relation.startsWith(table + "_p")) {
				scans.add(relation);
			}
		}
		return scans;
	}

	private static final class Accumulator {

		private final String plan;

		private long sharedHits;

		private long sharedReads;

		private long tempReadBlocks;

		private long tempWrittenBlocks;

		private final Set<String> relations = new LinkedHashSet<>();

		private final Set<String> sequential = new LinkedHashSet<>();

		private final Set<String> indexes = new LinkedHashSet<>();

		private final Set<String> nodes = new LinkedHashSet<>();

		private final Set<String> subplans = new LinkedHashSet<>();

		Accumulator(String plan) {
			this.plan = plan;
		}

		void visit(JsonNode node) {
			if (node == null || !node.isObject()) {
				return;
			}
			addBuffers(node);
			String nodeType = text(node, "Node Type");
			JsonNode relation = node.get("Relation Name");
			JsonNode index = node.get("Index Name");
			if (executed(node)) {
				if (nodeType != null) {
					nodes.add(nodeType);
				}
				if (relation != null) {
					relations.add(relation.asString());
					if ("Seq Scan".equals(nodeType)) {
						sequential.add(relation.asString());
					}
				}
				if (index != null) {
					indexes.add(index.asString());
				}
				String subplan = text(node, "Subplan Name");
				// A CTE scan is evaluated once and isn't counted; only "SubPlan N" re-runs per row.
				if (subplan != null && subplan.startsWith("SubPlan")) {
					subplans.add(subplan);
				}
			}
			JsonNode children = node.get("Plans");
			if (children != null) {
				for (JsonNode child : children) {
					visit(child);
				}
			}
		}

		void addBuffers(JsonNode node) {
			if (node == null || !node.isObject()) {
				return;
			}
			sharedHits += number(node, "Shared Hit Blocks");
			sharedReads += number(node, "Shared Read Blocks");
			tempReadBlocks += number(node, "Temp Read Blocks");
			tempWrittenBlocks += number(node, "Temp Written Blocks");
		}

		/**
		 * {@code Actual Loops} is absent without {@code ANALYZE}, in which case every
		 * node in the plan is one the executor would run.
		 */
		private static boolean executed(JsonNode node) {
			JsonNode loops = node.get("Actual Loops");
			return loops == null || loops.asLong() > 0;
		}

		private static long number(JsonNode node, String field) {
			JsonNode value = node.get(field);
			return value == null ? 0 : value.asLong();
		}

		private static String text(JsonNode node, String field) {
			JsonNode value = node.get(field);
			return value == null ? null : value.asString();
		}

		PlanFacts toFacts() {
			return new PlanFacts(sharedHits, sharedReads, tempReadBlocks, tempWrittenBlocks, new TreeSet<>(relations),
					new TreeSet<>(sequential), new TreeSet<>(indexes), new TreeSet<>(nodes), new TreeSet<>(subplans),
					plan);
		}

	}

}
