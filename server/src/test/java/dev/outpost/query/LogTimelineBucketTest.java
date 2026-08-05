package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The bucket ladder, which is what the timeline's readability rests on: a bucket
 * a person can name ("the 14:05 bar") rather than one that falls where the window
 * width happens to put it.
 *
 * <p>These are unit tests because the ladder is a pure function of the window. The
 * cost of the grouping it produces is a different question, guarded against a real
 * dataset in {@link LogTimelinePerformanceTest}.
 */
class LogTimelineBucketTest {

	private static final Instant NOW = Instant.parse("2026-08-05T14:23:47Z");

	/** Midnight UTC — on every sub-day rung's grid, so a window ending here spans whole buckets. */
	private static final Instant ALIGNED_NOW = Instant.parse("2026-08-05T00:00:00Z");

	/**
	 * Each entry of the range picker in {@code ui/src/app/core/filters.ts}, and the
	 * rung it draws at. Pinned as a table because the alternative — asserting only
	 * that the bar count is in band — passes for a ladder that picks a different
	 * rung for every window, which is the property the ladder exists to prevent.
	 *
	 * <p>Measured from an aligned instant so the bar counts are the table's. A window
	 * starting mid-bucket spans one more; that is
	 * {@link #anUnalignedWindowSpansOneMoreBarThanItsLength()}, not a different ladder.
	 */
	@ParameterizedTest(name = "{0}h draws {2} bars of {1}")
	@CsvSource({ "1, PT1M, 60", "24, PT15M, 96", "168, PT2H, 84", "336, PT4H, 84", "720, PT6H, 120" })
	void eachRangeDrawsItsRung(long windowHours, Duration expected, long expectedBars) {
		Instant from = ALIGNED_NOW.minus(windowHours, ChronoUnit.HOURS);

		Duration bucket = LogController.timelineBucket(from, ALIGNED_NOW);

		assertThat(bucket).isEqualTo(expected);
		assertThat(Duration.between(from, ALIGNED_NOW).dividedBy(bucket)).isEqualTo(expectedBars);
	}

	/**
	 * The rung does not change when the window does not start on its grid — only the
	 * bar count does, by the one partial bucket at the leading edge.
	 */
	@ParameterizedTest
	@CsvSource({ "1, PT1M", "24, PT15M", "168, PT2H", "336, PT4H", "720, PT6H" })
	void anUnalignedWindowSpansOneMoreBarThanItsLength(long windowHours, Duration expected) {
		Instant from = NOW.minus(windowHours, ChronoUnit.HOURS); // 14:23:47 — on no rung's grid

		assertThat(LogController.timelineBucket(from, NOW)).isEqualTo(expected);
	}

	/**
	 * Every rung has to keep the chart inside the band it was chosen for — counting
	 * the way the client draws it, from the aligned start and rounding up. Counting
	 * any other way is how a 151-bar chart passes a 150-bar guard.
	 */
	@ParameterizedTest
	@CsvSource({ "1", "6", "24", "72", "168", "336", "720", "2160", "8760" })
	void noWindowUpToAYearDrawsMoreThanTheBandAllows(long windowHours) {
		Instant from = NOW.minus(windowHours, ChronoUnit.HOURS);

		assertThat(barsDrawn(from, NOW)).isBetween(1L, 150L);
	}

	/**
	 * What the client renders: {@code ceil((to - alignedFrom) / width)}, mirroring
	 * `bucketCount` in {@code ui/src/app/shared/log-timeline.ts`}.
	 */
	private static long barsDrawn(Instant from, Instant to) {
		Duration bucket = LogController.timelineBucket(from, to);
		long width = bucket.toSeconds();
		Instant origin = Instant.parse("1970-01-05T00:00:00Z");
		long aligned = Math.floorDiv(from.getEpochSecond() - origin.getEpochSecond(), width) * width
				+ origin.getEpochSecond();
		return Math.ceilDiv(to.getEpochSecond() - aligned, width);
	}

	/**
	 * Past the last rung the bar count grows rather than the bucket widening. A
	 * four-year retention drawing 200 thin bars is a legible failure; one silently
	 * bucketing by the month is a chart that says something untrue about when things
	 * happened.
	 */
	@Test
	void aHistoryLongerThanTheLadderGetsMoreBarsRatherThanACoarserBucket() {
		Instant from = NOW.minus(4 * 365, ChronoUnit.DAYS);

		Duration bucket = LogController.timelineBucket(from, NOW);

		assertThat(bucket).isEqualTo(Duration.ofDays(7));
		assertThat(Duration.between(from, NOW).dividedBy(bucket)).isGreaterThan(150);
	}

	/**
	 * An empty or inverted window is a state the UI can reach — the brush is cleared
	 * by a range change, and a clock skew can put {@code to} behind {@code from} — so
	 * it resolves to the finest rung rather than dividing by zero.
	 */
	@Test
	void anEmptyWindowResolvesToTheFinestRung() {
		assertThat(LogController.timelineBucket(NOW, NOW)).isEqualTo(Duration.ofMinutes(1));
		assertThat(LogController.timelineBucket(NOW, NOW.minusSeconds(60))).isEqualTo(Duration.ofMinutes(1));
	}

}
