package dev.outpost.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DataRetentionSchedulerTest {

	@Test
	void survivesLifecycleRestart() {
		DataRetentionScheduler scheduler = new DataRetentionScheduler(null, null);
		scheduler.start();
		scheduler.stop();
		scheduler.start();
		scheduler.stop();
		assertThat(scheduler.isRunning()).isFalse();
	}

	@Test
	void calculatesNextTwoAmUtcAroundBoundary() {
		assertThat(DataRetentionScheduler.nextRunAfter(Instant.parse("2026-07-19T01:59:59.999Z")))
			.isEqualTo(Instant.parse("2026-07-19T02:00:00Z"));
		assertThat(DataRetentionScheduler.nextRunAfter(Instant.parse("2026-07-19T02:00:00Z")))
			.isEqualTo(Instant.parse("2026-07-20T02:00:00Z"));
		assertThat(DataRetentionScheduler.nextRunAfter(Instant.parse("2026-07-19T02:00:00.001Z")))
			.isEqualTo(Instant.parse("2026-07-20T02:00:00Z"));
	}
}
