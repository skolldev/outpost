package dev.outpost.bench;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.support.PlanFacts;
import dev.outpost.support.TelemetrySeeder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Self-tests for the retrieval harness. Runs in CI — the harness is code, and a
 * broken harness reports confident nonsense: a plan parser that silently returned
 * zero would make every guard in {@code dev.outpost.query} pass, and a page-walk
 * check that never fired would let the deep-pagination scenarios measure page 1
 * fifty times and call it fast.
 */
class RetrievalBenchmarkTest {

	@Nested
	class Scaling {

		@Test
		void scalesRowCountsButNotTheShapeOfTheData() {
			TelemetrySeeder.Scale tenth = TelemetrySeeder.Scale.DEFAULT.times(0.1);

			assertThat(tenth.events()).isEqualTo(TelemetrySeeder.Scale.DEFAULT.events() / 10);
			assertThat(tenth.logs()).isEqualTo(TelemetrySeeder.Scale.DEFAULT.logs() / 10);
			assertThat(tenth.txns()).isEqualTo(TelemetrySeeder.Scale.DEFAULT.txns() / 10);
			// Cardinalities and the window are what make the dataset production-shaped.
			// Shrinking them with the volume would give a small dataset that is also the
			// wrong shape, and the run would measure a plan production never gets. Two of
			// these matter more than the rest: `users` is the divisor of the suspect
			// count(DISTINCT user_ident), and `issues` decides how many pages deep the
			// deep-pagination scenario can actually go.
			assertThat(tenth.users()).isEqualTo(TelemetrySeeder.Scale.DEFAULT.users());
			assertThat(tenth.issues()).isEqualTo(TelemetrySeeder.Scale.DEFAULT.issues());
			assertThat(tenth.windowDays()).isEqualTo(TelemetrySeeder.Scale.DEFAULT.windowDays());
			assertThat(tenth.releases()).isEqualTo(TelemetrySeeder.Scale.DEFAULT.releases());
			assertThat(tenth.projects()).isEqualTo(TelemetrySeeder.Scale.DEFAULT.projects());
			assertThat(tenth.spansPerTxn()).isEqualTo(TelemetrySeeder.Scale.DEFAULT.spansPerTxn());
		}

		@Test
		void neverScalesRowCountsAwayToNothing() {
			TelemetrySeeder.Scale tiny = TelemetrySeeder.Scale.GUARD.times(0.000001);

			assertThat(tiny.events()).isPositive();
			assertThat(tiny.logs()).isPositive();
			assertThat(tiny.txns()).isPositive();
		}

		@Test
		void derivesSpanCountFromTransactions() {
			assertThat(TelemetrySeeder.Scale.DEFAULT.spans())
				.isEqualTo(TelemetrySeeder.Scale.DEFAULT.txns() * TelemetrySeeder.Scale.DEFAULT.spansPerTxn());
		}

		/** The guards' pruning assertions are vacuous against a dataset that fits in one week. */
		@Test
		void guardScaleStillSpansSeveralWeeks() {
			assertThat(TelemetrySeeder.Scale.GUARD.windowDays()).isGreaterThan(28);
		}

	}

	@Nested
	class PageWalking {

		@Test
		void acceptsAPageOfFreshIds() {
			PageWalk walk = PageWalk.inspect(List.of("a", "b"), List.of("c", "d"));

			assertThat(walk.advanced()).isTrue();
		}

		/** The exact failure a broken cursor predicate produces: the same page, forever. */
		@Test
		void rejectsAPageThatRepeatsTheOneBeforeIt() {
			PageWalk walk = PageWalk.inspect(List.of("a", "b"), List.of("a", "b"));

			assertThat(walk.advanced()).isFalse();
			assertThat(walk.sharedWithPreviousPage()).containsExactly("a", "b");
			assertThat(walk.describe()).contains("really page 1");
		}

		@Test
		void rejectsAPartialOverlapToo() {
			PageWalk walk = PageWalk.inspect(List.of("a", "b"), List.of("b", "c"));

			assertThat(walk.advanced()).isFalse();
			assertThat(walk.sharedWithPreviousPage()).containsExactly("b");
		}

		/** An unstable sort order can hand the same row back twice inside one page. */
		@Test
		void rejectsAPageThatRepeatsARowWithinItself() {
			PageWalk walk = PageWalk.inspect(List.of(), List.of("a", "a", "b"));

			assertThat(walk.advanced()).isFalse();
			assertThat(walk.repeatedWithinPage()).containsExactly("a");
		}

		@Test
		void treatsTheFirstPageAsAdvanced() {
			assertThat(PageWalk.inspect(List.of(), List.of("a", "b")).advanced()).isTrue();
		}

	}

	/**
	 * Against a plan recorded from a real {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT
	 * JSON)} of a time-bounded log page —
	 * {@code src/test/resources/plans/log-page-bounded.json}. The expectations below
	 * are the numbers in that file, so a parser that stopped descending, or started
	 * counting a relation twice, changes them.
	 */
	@Nested
	class PlanParsing {

		private final PlanFacts facts = PlanFacts.parse(fixture("plans/log-page-bounded.json"));

		/** 58 blocks on each of Limit/Sort/Append, 11 + 16 on two partitions, 31 on a third, 93 planning. */
		@Test
		void sumsBufferCountsAcrossEveryNodeAndPlanning() {
			assertThat(facts.sharedHits()).isEqualTo(325);
			assertThat(facts.sharedReads()).isZero();
			assertThat(facts.logicalIo()).isEqualTo(325);
		}

		@Test
		void collectsThePartitionsTheQueryActuallyRead() {
			assertThat(facts.partitionsScanned("log_record")).containsExactly("log_record_p20260713",
					"log_record_p20260720", "log_record_p20260727", "log_record_p20260803", "log_record_p20260810");
			assertThat(facts.partitionsScanned("event")).isEmpty();
		}

		@Test
		void separatesSequentialScansFromOtherAccess() {
			assertThat(facts.sequentialScansOf("log_record")).hasSize(5);
			assertThat(facts.sequentialScansOf("event")).isEmpty();
		}

		@Test
		void recordsWhichNodeTypesRan() {
			assertThat(facts.ran("Sort")).isTrue();
			assertThat(facts.ran("Append")).isTrue();
			assertThat(facts.ran("Index Scan")).isFalse();
		}

		@Test
		void reportsNoTempIoForAPlanThatSortedInMemory() {
			assertThat(facts.tempBlocks()).isZero();
		}

	}

	/**
	 * The two things a healthy recorded plan cannot demonstrate, so they are
	 * constructed: a partition eliminated by runtime pruning (which appears in the
	 * plan with {@code "Actual Loops": 0}) and a sort that spilled. Counting the
	 * pruned partition would make every pruning assertion vacuous; missing the spill
	 * would make {@code assertNoTempFiles} unable to fail.
	 */
	@Nested
	class PlanParsingEdgeCases {

		private static final String PLAN = """
				[
				  {
				    "Plan": {
				      "Node Type": "Sort",
				      "Actual Loops": 1,
				      "Shared Hit Blocks": 100,
				      "Shared Read Blocks": 7,
				      "Temp Read Blocks": 40,
				      "Temp Written Blocks": 44,
				      "Plans": [
				        {
				          "Node Type": "Append",
				          "Actual Loops": 1,
				          "Shared Hit Blocks": 100,
				          "Plans": [
				            {
				              "Node Type": "Seq Scan",
				              "Relation Name": "event_p20260720",
				              "Actual Loops": 1,
				              "Shared Hit Blocks": 100
				            },
				            {
				              "Node Type": "Seq Scan",
				              "Relation Name": "event_p20260601",
				              "Actual Loops": 0,
				              "Shared Hit Blocks": 0
				            }
				          ]
				        }
				      ]
				    }
				  }
				]
				""";

		private final PlanFacts facts = PlanFacts.parse(PLAN);

		@Test
		void ignoresPartitionsTheExecutorNeverTouched() {
			assertThat(facts.partitionsScanned("event")).containsExactly("event_p20260720");
			assertThat(facts.sequentialScansOf("event")).containsExactly("event_p20260720");
		}

		@Test
		void sumsTempBlocksInBothDirections() {
			assertThat(facts.tempReadBlocks()).isEqualTo(40);
			assertThat(facts.tempWrittenBlocks()).isEqualTo(44);
			assertThat(facts.tempBlocks()).isEqualTo(84);
		}

		@Test
		void countsBlocksReadFromDiskAlongsideBlocksFoundInCache() {
			// 100 on each of Sort, Append and the one executed Seq Scan: Postgres reports
			// buffers cumulatively up the tree, so the total is an index of logical I/O
			// rather than a block count. That is deliberate — it is what the text-format
			// regex this replaced produced, which is what the trace guard's 50 000 means.
			assertThat(facts.sharedHits()).isEqualTo(300);
			assertThat(facts.sharedReads()).isEqualTo(7);
			assertThat(facts.logicalIo()).isEqualTo(307);
		}

		/** Trace detail issues four statements; reporting them apart would hide the page's real cost. */
		@Test
		void mergesTheFactsOfSeveralStatements() {
			PlanFacts merged = PlanFacts.NONE.merge(facts).merge(facts);

			assertThat(merged.logicalIo()).isEqualTo(2 * facts.logicalIo());
			assertThat(merged.partitionsScanned("event")).containsExactly("event_p20260720");
		}

	}

	private static String fixture(String path) {
		try (var in = RetrievalBenchmarkTest.class.getClassLoader().getResourceAsStream(path)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
