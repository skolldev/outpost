package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The bucket ladder that every time-series chart rests on, producing buckets a
 * person can name (the "14:05 bar"), shared by the Log Timeline and the
 * Transaction Group duration trend. These are unit tests since the ladder is a
 * pure function of the window; grouping cost is covered separately in
 * {@link LogTimelinePerformanceTest} and {@link TransactionGroupPerformanceTest}.
 */
class TimeBucketsTest {

	private static final Instant NOW = Instant.parse("2026-08-05T14:23:47Z");

	/** Midnight UTC — on every sub-day rung's grid, so a window ending here spans whole buckets. */
	private static final Instant ALIGNED_NOW = Instant.parse("2026-08-05T00:00:00Z");

	/**
	 * Each entry of the range picker in {@code ui/src/app/core/filters.ts} and the
	 * rung it draws at, pinned exactly rather than checked "in band" so a ladder
	 * that picks a different rung for every window would fail. The last row is the
	 * Performance trend's 30-day cap (ADR-0015), drawn at 6 hours, ~120 points.
	 */
	@ParameterizedTest(name = "{0}h draws {2} buckets of {1}")
	@CsvSource({ "1, PT1M, 60", "24, PT15M, 96", "168, PT2H, 84", "336, PT4H, 84", "720, PT6H, 120" })
	void eachRangeDrawsItsRung(long windowHours, Duration expected, long expectedBuckets) {
		Instant from = ALIGNED_NOW.minus(windowHours, ChronoUnit.HOURS);

		Duration bucket = TimeBuckets.width(from, ALIGNED_NOW);

		assertThat(bucket).isEqualTo(expected);
		assertThat(Duration.between(from, ALIGNED_NOW).dividedBy(bucket)).isEqualTo(expectedBuckets);
	}

	/**
	 * The rung does not change when the window does not start on its grid — only the
	 * bucket count does, by the one partial bucket at the leading edge.
	 */
	@ParameterizedTest
	@CsvSource({ "1, PT1M", "24, PT15M", "168, PT2H", "336, PT4H", "720, PT6H" })
	void anUnalignedWindowSpansOneMoreBucketThanItsLength(long windowHours, Duration expected) {
		Instant from = NOW.minus(windowHours, ChronoUnit.HOURS); // 14:23:47 — on no rung's grid

		assertThat(TimeBuckets.width(from, NOW)).isEqualTo(expected);
	}

	/**
	 * Every rung has to keep the chart inside the band it was chosen for — counting
	 * the way the client draws it, from the aligned start and rounding up. Counting
	 * any other way is how a 151-bucket chart passes a 150-bucket guard.
	 */
	@ParameterizedTest
	@CsvSource({ "1", "6", "24", "72", "168", "336", "720", "2160", "8760" })
	void noWindowUpToAYearDrawsMoreThanTheBandAllows(long windowHours) {
		Instant from = NOW.minus(windowHours, ChronoUnit.HOURS);

		assertThat(bucketsDrawn(from, NOW)).isBetween(1L, 150L);
	}

	/**
	 * Aligning is idempotent and never moves an instant forward — properties every
	 * client's index arithmetic assumes. A grid start after the window's own start
	 * would put the first bucket at a negative index.
	 */
	@ParameterizedTest
	@CsvSource({ "PT1M", "PT15M", "PT6H", "P1D", "P7D" })
	void aligningFloorsOntoTheGridAndStaysThere(Duration bucket) {
		Instant aligned = TimeBuckets.alignDown(NOW, bucket);

		assertThat(aligned).isBeforeOrEqualTo(NOW).isAfter(NOW.minus(bucket));
		assertThat(TimeBuckets.alignDown(aligned, bucket)).isEqualTo(aligned);
		assertThat(Duration.between(TimeBuckets.ORIGIN, aligned).toSeconds() % bucket.toSeconds()).isZero();
	}

	/**
	 * What a client renders: {@code ceil((to - alignedFrom) / width)}, mirroring
	 * `bucketCount` in {@code ui/src/app/shared/log-timeline.ts} and
	 * {@code ui/src/app/shared/duration-trend.ts}.
	 */
	private static long bucketsDrawn(Instant from, Instant to) {
		Duration bucket = TimeBuckets.width(from, to);
		Instant aligned = TimeBuckets.alignDown(from, bucket);
		return Math.ceilDiv(to.getEpochSecond() - aligned.getEpochSecond(), bucket.toSeconds());
	}

	/**
	 * Past the last rung the bucket count grows rather than the bucket widening
	 * further — a four-year window draws more, thinner bars instead of silently
	 * bucketing by the month.
	 */
	@Test
	void aHistoryLongerThanTheLadderGetsMoreBucketsRatherThanACoarserOne() {
		Instant from = NOW.minus(4 * 365, ChronoUnit.DAYS);

		Duration bucket = TimeBuckets.width(from, NOW);

		assertThat(bucket).isEqualTo(Duration.ofDays(7));
		assertThat(Duration.between(from, NOW).dividedBy(bucket)).isGreaterThan(150);
	}

	/**
	 * An empty or inverted window is a state the UI can reach — the log brush is
	 * cleared by a range change, and a clock skew can put {@code to} behind
	 * {@code from} — so it resolves to the finest rung rather than dividing by zero.
	 */
	@Test
	void anEmptyWindowResolvesToTheFinestRung() {
		assertThat(TimeBuckets.width(NOW, NOW)).isEqualTo(Duration.ofMinutes(1));
		assertThat(TimeBuckets.width(NOW, NOW.minusSeconds(60))).isEqualTo(Duration.ofMinutes(1));
	}

}
