package dev.outpost.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class IngestBenchmarkTest {

	@Test
	void burstRateStaysWithinTheKnownGoodDriverRateAsCapacityGrows() {
		LoadDriver.Step step = IngestBenchmark.burstStep(50_000);

		assertThat(step.ratePerSecond()).isEqualTo(3_200);
		assertThat(step.duration()).isEqualTo(Duration.ofSeconds(47));
		assertThat(step.ratePerSecond() * step.duration().toSeconds()).isGreaterThanOrEqualTo(150_000);
	}

	@Test
	void drainWaitRejectsInFlightWorkEvenWhenTheBufferIsEmpty() {
		assertThatThrownBy(() -> IngestBenchmark.awaitIdle(() -> 1, Duration.ZERO))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("1 item still queued or in flight");
	}

	@Test
	void drainWaitReturnsWhenNothingIsOutstanding() throws InterruptedException {
		IngestBenchmark.awaitIdle(() -> 0, Duration.ZERO);
	}

}
