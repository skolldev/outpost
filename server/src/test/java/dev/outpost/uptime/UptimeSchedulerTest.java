package dev.outpost.uptime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class UptimeSchedulerTest {

	// A tick one hour out never fires during the test, so the null collaborators
	// (jdbc/prober/checkService) are never touched by the scheduled body.
	private UptimeScheduler scheduler() {
		return new UptimeScheduler(null, null, null, 3_600_000L);
	}

	@Test
	void stopBeforeStartDoesNotThrow() {
		UptimeScheduler scheduler = scheduler();
		assertThatCode(scheduler::stop).doesNotThrowAnyException();
		assertThat(scheduler.isRunning()).isFalse();
	}

	@Test
	void survivesLifecycleRestart() {
		UptimeScheduler scheduler = scheduler();
		scheduler.start();
		scheduler.stop();
		scheduler.start();
		scheduler.stop();
		assertThat(scheduler.isRunning()).isFalse();
	}

	@Test
	void repeatedStartIsIdempotent() {
		UptimeScheduler scheduler = scheduler();
		scheduler.start();
		// A second start() while running must be a no-op — no orphaned executor,
		// no duplicate tick schedule.
		scheduler.start();
		assertThat(scheduler.isRunning()).isTrue();
		scheduler.stop();
		assertThat(scheduler.isRunning()).isFalse();
	}
}
