package dev.outpost.query;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The bucket ladder every time-series aggregate in the query package bins on: a width a person
 * can name ("the 14:05 bar") rather than one that falls wherever the window happens to put it.
 * Shared across surfaces (#141, #163) so two charts of the same range never disagree about how
 * wide "a bucket" is.
 */
final class TimeBuckets {

	private TimeBuckets() {
	}

	/**
	 * Widths a chart may draw at, smallest first. 7d is the widest rung on purpose — past it the
	 * honest failure is more bars, not a bucket silently becoming a month.
	 */
	private static final List<Duration> RUNGS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
			Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(4), Duration.ofHours(6),
			Duration.ofDays(1), Duration.ofDays(7));

	private static final int MAX_BUCKETS = 150;

	/**
	 * Monday 1970-01-05 — the origin every bucket is binned from. Midnight UTC aligns sub-day
	 * rungs to clock boundaries; Monday aligns the weekly rung with the weeks {@code
	 * PartitionManager} cuts partitions on.
	 */
	static final Instant ORIGIN = Instant.parse("1970-01-05T00:00:00Z");

	/**
	 * The bucket width a window of this length is drawn at: the smallest rung yielding at most
	 * {@value #MAX_BUCKETS} buckets. The count is a ceiling counted from {@link
	 * #alignDown(Instant, Duration) the aligned start}, since that's what the client actually
	 * draws from.
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
	 * {@code instant} floored onto the grid {@code date_bin} bins to, i.e. {@code origin +
	 * k*width}. Must be echoed back and used by callers: a client indexes buckets by {@code
	 * (bucket.start - gridStart) / width}, so an unaligned instant makes every bucket land one
	 * index low and drops the first.
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
