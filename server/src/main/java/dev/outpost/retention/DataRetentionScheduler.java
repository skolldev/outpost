package dev.outpost.retention;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Runs enabled retention cleanup every day at 02:00 UTC. */
@Component
public class DataRetentionScheduler implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(DataRetentionScheduler.class);
	private static final LocalTime RUN_TIME = LocalTime.of(2, 0);

	private final DataRetentionSettings settings;
	private final DataRetentionService cleanup;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "data-retention");
		thread.setDaemon(true);
		return thread;
	});
	private volatile boolean running;

	public DataRetentionScheduler(DataRetentionSettings settings, DataRetentionService cleanup) {
		this.settings = settings;
		this.cleanup = cleanup;
	}

	@Override
	public void start() {
		if (running) {
			return;
		}
		running = true;
		Instant now = Instant.now();
		long initialDelay = Math.max(0, Duration.between(now, nextRunAfter(now)).toMillis());
		executor.scheduleAtFixedRate(this::runSafely, initialDelay, Duration.ofDays(1).toMillis(),
				TimeUnit.MILLISECONDS);
	}

	@Override
	public void stop() {
		running = false;
		executor.shutdownNow();
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	void runOnce(Instant runInstant) {
		DataRetentionSettings.Policy policy = settings.get();
		if (!policy.enabled()) {
			return;
		}
		Instant cutoff = runInstant.minus(Duration.ofDays(policy.retentionDays()));
		DataRetentionService.CleanupResult result = cleanup.cleanup(cutoff);
		log.info("data retention ({} days): deleted {} events, {} issues, {} logs, {} transactions, {} spans, "
				+ "{} uptime checks, {} closed incidents", policy.retentionDays(), result.events(), result.issues(),
				result.logs(), result.transactions(), result.spans(), result.uptimeChecks(), result.uptimeIncidents());
	}

	static Instant nextRunAfter(Instant instant) {
		ZonedDateTime now = instant.atZone(ZoneOffset.UTC);
		ZonedDateTime candidate = now.toLocalDate().atTime(RUN_TIME).atZone(ZoneOffset.UTC);
		if (!candidate.toInstant().isAfter(instant)) {
			candidate = candidate.plusDays(1);
		}
		return candidate.toInstant();
	}

	private void runSafely() {
		try {
			runOnce(Instant.now());
		}
		catch (RuntimeException e) {
			log.warn("data retention cleanup failed; will retry at the next daily run: {}", e.toString());
		}
	}
}
