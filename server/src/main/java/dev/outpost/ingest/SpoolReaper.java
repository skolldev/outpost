package dev.outpost.ingest;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Sweeps the ingest spool directory on a fixed delay, removing files no live
 * queue entry points at that have gone untouched for {@code spool-max-age}.
 * Requires {@code spool-max-age} to exceed the shutdown drain timeout, so the
 * sweep can't reap a file a worker is still draining.
 */
@Component
public class SpoolReaper implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(SpoolReaper.class);

	private final EnvelopeSpool spool;
	private final IngestMetrics metrics;
	private final Duration maxAge;
	private final Duration interval;
	// Recreated each start(): stop() shuts the executor down for good.
	private ScheduledExecutorService sweeper;
	private volatile boolean running;

	public SpoolReaper(EnvelopeSpool spool, IngestMetrics metrics,
			@Value("${outpost.ingest.spool-max-age:1h}") Duration maxAge,
			@Value("${outpost.ingest.spool-sweep-interval:5m}") Duration interval,
			@Value("${outpost.ingest.shutdown-timeout:" + IngestWorkers.DEFAULT_SHUTDOWN_TIMEOUT + "}")
			Duration shutdownTimeout) {
		if (!interval.isPositive()) {
			throw new IllegalArgumentException("outpost.ingest.spool-sweep-interval must be positive");
		}
		if (maxAge.compareTo(shutdownTimeout) <= 0) {
			throw new IllegalArgumentException("outpost.ingest.spool-max-age (" + maxAge
					+ ") must exceed outpost.ingest.shutdown-timeout (" + shutdownTimeout
					+ "), or the sweep can reap a file a worker is still draining");
		}
		this.spool = spool;
		this.metrics = metrics;
		this.maxAge = maxAge;
		this.interval = interval;
	}

	@Override
	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "ingest-spool-reaper");
			thread.setDaemon(true);
			return thread;
		});
		sweeper.scheduleWithFixedDelay(this::sweep, interval.toMillis(), interval.toMillis(),
				TimeUnit.MILLISECONDS);
	}

	void sweep() {
		try {
			EnvelopeSpool.Sweep sweep = spool.reap(maxAge);
			if (sweep.isEmpty()) {
				// Debug only, but confirms the sweep runs even when it finds nothing.
				log.debug("ingest spool sweep found nothing older than {}", maxAge);
				return;
			}
			metrics.spoolReaped(sweep.files(), sweep.bytes());
			log.info("reaped {} orphaned ingest spool file{}, {} bytes reclaimed", sweep.files(),
					sweep.files() == 1 ? "" : "s", sweep.bytes());
		}
		catch (RuntimeException e) {
			// A single bad sweep must not kill the scheduled task.
			log.warn("ingest spool sweep failed", e);
		}
	}

	@Override
	public synchronized void stop() {
		running = false;
		// Null-checked: a manual/edge call could invoke stop() before start().
		if (sweeper != null) {
			sweeper.shutdownNow();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

}
