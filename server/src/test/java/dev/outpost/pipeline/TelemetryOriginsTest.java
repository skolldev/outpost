package dev.outpost.pipeline;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

class TelemetryOriginsTest {

	/** Stands in for any processed signal — only the three origin fields matter. */
	private record Origin(long projectId, String environment, String release) implements ProcessedTelemetry {
	}

	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

	private final TelemetryOrigins origins = new TelemetryOrigins(jdbc);

	@Test
	void upsertsOnceForABatchSharingOneOrigin() {
		List<Origin> batch = IntStream.range(0, 500).mapToObj(i -> new Origin(1L, "prod", "shop@1.0.0")).toList();

		origins.ensure(batch);

		verify(jdbc, times(1)).update(contains("INTO environment"), eq(1L), eq("prod"));
		verify(jdbc, times(1)).update(contains("INTO release"), eq(1L), eq("shop@1.0.0"));
		verifyNoMoreInteractions(jdbc);
	}

	@Test
	void upsertsOncePerDistinctPair() {
		origins.ensure(List.of(new Origin(1L, "prod", "shop@1.0.0"), new Origin(1L, "prod", "shop@1.0.1"),
				new Origin(1L, "staging", "shop@1.0.0"), new Origin(2L, "prod", "shop@1.0.0")));

		verify(jdbc).update(contains("INTO environment"), eq(1L), eq("prod"));
		verify(jdbc).update(contains("INTO environment"), eq(1L), eq("staging"));
		verify(jdbc).update(contains("INTO environment"), eq(2L), eq("prod"));
		verify(jdbc).update(contains("INTO release"), eq(1L), eq("shop@1.0.0"));
		verify(jdbc).update(contains("INTO release"), eq(1L), eq("shop@1.0.1"));
		verify(jdbc).update(contains("INTO release"), eq(2L), eq("shop@1.0.0"));
		verifyNoMoreInteractions(jdbc);
	}

	@Test
	void skipsReleaselessOrigins() {
		origins.ensure(List.of(new Origin(1L, "prod", null), new Origin(1L, "prod", "shop@1.0.0")));

		verify(jdbc, times(1)).update(contains("INTO environment"), eq(1L), eq("prod"));
		verify(jdbc, times(1)).update(contains("INTO release"), eq(1L), eq("shop@1.0.0"));
		verifyNoMoreInteractions(jdbc);
	}

	@Test
	void upsertsInASortedOrderSoConcurrentBatchesCannotDeadlock() {
		origins.ensure(List.of(new Origin(2L, "staging", "shop@1.0.1"), new Origin(1L, "prod", "shop@1.0.0")));

		InOrder order = inOrder(jdbc);
		order.verify(jdbc).update(contains("INTO environment"), eq(1L), eq("prod"));
		order.verify(jdbc).update(contains("INTO environment"), eq(2L), eq("staging"));
		order.verify(jdbc).update(contains("INTO release"), eq(1L), eq("shop@1.0.0"));
		order.verify(jdbc).update(contains("INTO release"), eq(2L), eq("shop@1.0.1"));
	}

	@Test
	void doesNothingForAnEmptyBatch() {
		origins.ensure(List.of());

		verify(jdbc, times(0)).update(anyString(), eq(1L), eq("prod"));
		verifyNoMoreInteractions(jdbc);
	}
}
