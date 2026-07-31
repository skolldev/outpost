package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class SpoolReaperTest {

	private static final Duration MAX_AGE = Duration.ofHours(1);

	private final EnvelopeSpool spool = mock(EnvelopeSpool.class);

	private final IngestMetrics metrics = mock(IngestMetrics.class);

	@Test
	void rejectsAMaxAgeThatDoesNotExceedTheShutdownDrainTimeout() {
		assertThatThrownBy(() -> reaper(Duration.ofSeconds(25), Duration.ofSeconds(25)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("outpost.ingest.spool-max-age");
	}

	@Test
	void rejectsANonPositiveSweepInterval() {
		assertThatThrownBy(
				() -> new SpoolReaper(spool, metrics, MAX_AGE, Duration.ZERO, Duration.ofSeconds(25)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("outpost.ingest.spool-sweep-interval");
	}

	@Test
	void acceptsAMaxAgeComfortablyBeyondTheDrainTimeout() {
		assertThat(reaper(MAX_AGE, Duration.ofSeconds(25))).isNotNull();
	}

	@Test
	void recordsWhatTheSweepReclaimed() {
		when(spool.reap(MAX_AGE)).thenReturn(new EnvelopeSpool.Sweep(3, 4096));

		reaper(MAX_AGE, Duration.ofSeconds(25)).sweep();

		verify(metrics).spoolReaped(3, 4096);
	}

	@Test
	void recordsNothingWhenTheSweepFoundNothing() {
		when(spool.reap(MAX_AGE)).thenReturn(new EnvelopeSpool.Sweep(0, 0));

		reaper(MAX_AGE, Duration.ofSeconds(25)).sweep();

		verify(metrics, never()).spoolReaped(ArgumentMatchers.anyInt(), ArgumentMatchers.anyLong());
	}

	@Test
	void aFailedSweepDoesNotEscapeAndKillTheSchedule() {
		when(spool.reap(MAX_AGE)).thenThrow(new IllegalStateException("filesystem gone"));

		assertThatCode(() -> reaper(MAX_AGE, Duration.ofSeconds(25)).sweep()).doesNotThrowAnyException();
	}

	private SpoolReaper reaper(Duration maxAge, Duration shutdownTimeout) {
		return new SpoolReaper(spool, metrics, maxAge, Duration.ofMinutes(5), shutdownTimeout);
	}
}
