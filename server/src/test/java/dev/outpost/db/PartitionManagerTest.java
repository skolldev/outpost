package dev.outpost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PartitionManagerTest {

	@Test
	void collapsesTimestampsOfTheSameWeekToOneWeek() {
		Instant monday = Instant.parse("2026-07-27T00:00:00Z");
		// Distinct instants a second apart — the shape of a log drain, where no two
		// records share a timestamp but all share a partition.
		List<Instant> timestamps = IntStream.range(0, 10_000).mapToObj(monday::plusSeconds).toList();

		assertThat(PartitionManager.weeksOf(timestamps)).containsExactly(LocalDate.parse("2026-07-27"));
	}

	@Test
	void keepsOneWeekPerPartitionSpanned() {
		Instant monday = Instant.parse("2026-07-27T00:00:00Z");
		Set<LocalDate> weeks = PartitionManager.weeksOf(List.of(monday, monday.plus(Duration.ofDays(3)),
				monday.plus(Duration.ofDays(8)), monday.minus(Duration.ofDays(1))));

		assertThat(weeks).containsExactlyInAnyOrder(LocalDate.parse("2026-07-20"), LocalDate.parse("2026-07-27"),
				LocalDate.parse("2026-08-03"));
	}

	@Test
	void hasNoWeeksForAnEmptyBatch() {
		assertThat(PartitionManager.weeksOf(List.of())).isEmpty();
	}
}
