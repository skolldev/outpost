package dev.outpost.bench;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Exact per-plateau percentiles of {@code outpost.ingest.store}. The production
 * timer's percentiles decay over a two-minute window and cannot be reset, so a
 * plateau read from it would blend in its predecessors. Instead a fresh
 * {@link SimpleMeterRegistry} joins the application's composite registry for one
 * plateau and its drain: every store call in that window, and only those, lands
 * in its histogram.
 */
final class StoreLatencyProbe {

	/** One plateau's store-call distribution; the latencies are NaN when no call happened. */
	record Window(long calls, double p99Millis, double maxMillis) {

		static final Window NONE = new Window(0, Double.NaN, Double.NaN);
	}

	/** One buffer that outlives any plateau, so nothing recorded in the window expires out of it. */
	private static final MeterFilter NON_DECAYING = new MeterFilter() {
		@Override
		public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
			return DistributionStatisticConfig.builder()
				.expiry(Duration.ofHours(1))
				.bufferLength(1)
				.build()
				.merge(config);
		}
	};

	private final CompositeMeterRegistry composite;

	private SimpleMeterRegistry window;

	StoreLatencyProbe(MeterRegistry meters) {
		if (!(meters instanceof CompositeMeterRegistry registry)) {
			throw new IllegalStateException(
					"the store-latency probe needs a composite registry; import StoreLatencyProbe.Registries");
		}
		this.composite = registry;
	}

	void start() {
		window = new SimpleMeterRegistry();
		window.config().meterFilter(NON_DECAYING);
		composite.add(window);
	}

	Window stop(String signal) {
		try {
			Timer timer = window.find("outpost.ingest.store").tag("signal", signal).timer();
			if (timer == null || timer.count() == 0) {
				return Window.NONE;
			}
			double p99 = Double.NaN;
			for (ValueAtPercentile value : timer.takeSnapshot().percentileValues()) {
				if (value.percentile() == 0.99) {
					p99 = value.value(TimeUnit.MILLISECONDS);
				}
			}
			return new Window(timer.count(), p99, timer.max(TimeUnit.MILLISECONDS));
		}
		finally {
			composite.remove(window);
			window.close();
			window = null;
		}
	}

	/**
	 * Replaces the registry Spring Boot's test support would create (export is
	 * disabled under test, so that is a lone {@link SimpleMeterRegistry}) with a
	 * composite over one, which is what {@code IngestMetrics} then registers
	 * against — the precondition for joining a window to it.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class Registries {

		@Bean
		@Primary
		CompositeMeterRegistry benchCompositeMeterRegistry() {
			return new CompositeMeterRegistry(Clock.SYSTEM, List.of(new SimpleMeterRegistry()));
		}
	}

}
