package dev.outpost.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.outpost.support.PlanFacts;
import dev.outpost.support.TelemetrySeeder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Self-tests for the retrieval harness. Runs in CI: a broken harness reports
 * confident nonsense — a plan parser that silently returns zero, or a
 * page-walk check that never fires, would let a deep-pagination scenario
 * measure page 1 fifty times and call it fast.
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
			// Cardinalities and window make the dataset production-shaped; `users` and `issues` also gate
			// correctness elsewhere (the distinct-user divisor, and deep-pagination depth).
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

		/** Must span several weekly partitions, or partition-pruning checks have nothing to prune. */
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
	 * The arithmetic that decides whether "page 50" is page 50 — a walk one page
	 * short reports page 49 under that label and nothing says so.
	 */
	@Nested
	class CursorWalking {

		private static final int PAGE_SIZE = 10;

		@Test
		void returnsTheCursorThatOpensTheRequestedPage() throws Exception {
			CursorWalk walk = CursorWalk.to(5, pagesOf(200));

			assertThat(walk.depth()).isEqualTo(5);
			assertThat(walk.cursor()).isEqualTo(cursorFor(5));
			assertThat(walk.pageIsFull()).isTrue();
		}

		/** Off by one in either direction is a different page, so both neighbours are pinned. */
		@Test
		void doesNotStopAPageEarlyOrRunAPageLate() throws Exception {
			assertThat(CursorWalk.to(2, pagesOf(200)).cursor()).isEqualTo(cursorFor(2));
			assertThat(CursorWalk.to(20, pagesOf(200)).cursor()).isEqualTo(cursorFor(20));
		}

		/** A smoke run at a tenth scale runs out of rows; measuring what it reached beats failing. */
		@Test
		void stopsAtTheLastPageWhenTheDataRunsOut() throws Exception {
			CursorWalk walk = CursorWalk.to(50, pagesOf(35));

			assertThat(walk.depth()).isEqualTo(4);
			assertThat(walk.cursor()).isEqualTo(cursorFor(4));
			assertThat(walk.pageIsFull()).isFalse();
		}

		/**
		 * {@code pageIsFull} is read from the cursor, not the depth reached: a dataset
		 * ending on a page boundary returns a full page with no cursor after it.
		 */
		@Test
		void reportsAnExactlyFullFinalPageAsNotGuaranteedFull() throws Exception {
			CursorWalk walk = CursorWalk.to(50, pagesOf(40));

			assertThat(walk.depth()).isEqualTo(4);
			assertThat(walk.pageIsFull()).isFalse();
		}

		@Test
		void refusesADatasetWithNowhereToWalk() {
			assertThatThrownBy(() -> CursorWalk.to(50, pagesOf(PAGE_SIZE)))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("needs somewhere to go");
		}

		/** The failure a broken cursor predicate produces, seen through the walk. */
		@Test
		void failsWhenTheCursorStopsAdvancing() {
			CursorWalk.Pages stuck = cursor -> new CursorWalk.Page(List.of("a", "b"), "always-page-1");

			assertThatThrownBy(() -> CursorWalk.to(50, stuck)).isInstanceOf(AssertionError.class)
				.hasMessageContaining("really page 1");
		}

		/** A page of {@code PAGE_SIZE} ids per page, over a dataset of {@code rows}. */
		private static CursorWalk.Pages pagesOf(int rows) {
			return cursor -> {
				int page = cursor == null ? 1 : Integer.parseInt(cursor.substring("page-".length()));
				int firstRow = (page - 1) * PAGE_SIZE;
				List<String> ids = new ArrayList<>();
				for (int row = firstRow; row < Math.min(firstRow + PAGE_SIZE, rows); row++) {
					ids.add("row-" + row);
				}
				// KeysetPage emits a cursor only when a full page was returned and more rows exist behind it.
				boolean more = firstRow + PAGE_SIZE < rows;
				return new CursorWalk.Page(ids, more ? cursorFor(page + 1) : null);
			};
		}

		private static String cursorFor(int page) {
			return "page-" + page;
		}

	}

	/**
	 * Against a real recorded {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)} plan
	 * ({@code src/test/resources/plans/log-page-bounded.json}); the expectations
	 * below are the exact numbers in that file.
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
	 * Two things a healthy recorded plan can't demonstrate, so they're constructed
	 * here: a partition eliminated by runtime pruning ({@code "Actual Loops": 0})
	 * and a sort that spilled.
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
			// Postgres reports buffers cumulatively up the tree, so 100 counts on Sort, Append and the Seq Scan alike.
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
