package dev.outpost.query;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The bucket ladder every time-series aggregate in the query package bins on: a
 * width a person can name ("the 14:05 bar", "Tuesday afternoon") rather than one
 * that falls wherever the window width happens to put it.
 *
 * <p>It lives here rather than on a controller because two surfaces now draw
 * against it — the Log Timeline (#141) and the Transaction Group duration trend
 * (#163) — and a second ladder would be the same idea with different rungs. Two
 * charts of the same 30-day range disagreeing about how wide "a bucket" is is a
 * difference nothing on either screen would explain.
 *
 * <p>Package-private, and deliberately not a Spring bean: it is a pure function of
 * the window, so the guards can read the width the endpoint <em>would</em> bind
 * without standing anything up.
 */
final class TimeBuckets {

	private TimeBuckets() {
	}

	/**
	 * Widths a chart may draw at, smallest first. 7d is the last rung deliberately:
	 * past it the honest failure is more bars, not a bucket silently becoming a
	 * month.
	 */
	private static final List<Duration> RUNGS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
			Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(4), Duration.ofHours(6),
			Duration.ofDays(1), Duration.ofDays(7));

	private static final int MAX_BUCKETS = 150;

	/**
	 * Monday 1970-01-05 — the origin every bucket is binned from. Midnight UTC, so
	 * every sub-day rung lands on a clock boundary; a Monday, so the weekly rung
	 * agrees with the weeks {@code PartitionManager} cuts partitions on rather than
	 * starting on the epoch's Thursday.
	 */
	static final Instant ORIGIN = Instant.parse("1970-01-05T00:00:00Z");

	/**
	 * The bucket width a window of this length is drawn at: the smallest rung
	 * yielding at most {@value #MAX_BUCKETS} buckets once the window has been aligned
	 * to that rung's grid.
	 *
	 * <p>A rule rather than a {@code range -> rung} map because the window is not
	 * always one of the range picker's: the Log Timeline's "All time" is however long
	 * this installation has retained logs, and a future zoom-to-selection would hand
	 * it arbitrary windows.
	 *
	 * <p>The bucket count is a <em>ceiling</em>, and it counts from {@link
	 * #alignDown(Instant, Duration) the aligned start}, because both are what the
	 * client will draw: a window covering 150.9 rungs occupies 151 buckets, and one
	 * starting mid-bucket occupies one more than its length suggests.
	 */
	static Duration width(Instant from, Instant to) {
		for (Duration rung : RUNGS) {
			if (count(from, to, rung) <= MAX_BUCKETS) {
				return rung;
			}
		}
		return RUNGS.getLast();
	}

	/** How many whole buckets of {@code rung} the aligned window spans. */
	private static long count(Instant from, Instant to, Duration rung) {
		long width = rung.toSeconds();
		long span = to.getEpochSecond() - alignDown(from, rung).getEpochSecond();
		return Math.max(1, Math.ceilDiv(span, width));
	}

	/**
	 * {@code instant} floored onto the grid {@code date_bin} bins to.
	 *
	 * <p>Load-bearing, and the reason is easy to miss. {@code date_bin} bins from
	 * {@link #ORIGIN}, so a bucket always starts at {@code origin + k·width} — never
	 * at whatever instant the request happened to ask for. Every client places
	 * buckets by {@code (bucket.start - gridStart) / width}, so unless the instant it
	 * is handed sits on that same grid, every bucket lands one index low, the first
	 * one floors to {@code -1} and is dropped, and the whole chart shifts a bucket
	 * left. Aligning here — and echoing the aligned instant — is what makes the
	 * response's own arithmetic come out whole.
	 */
	static Instant alignDown(Instant instant, Duration bucket) {
		long width = bucket.toSeconds();
		long offset = instant.getEpochSecond() - ORIGIN.getEpochSecond();
		return ORIGIN.plusSeconds(Math.floorDiv(offset, width) * width);
	}

	/** The origin as a bind value, since {@code date_bin}'s third argument is one. */
	static java.sql.Timestamp originParam() {
		return java.sql.Timestamp.from(ORIGIN);
	}

}
