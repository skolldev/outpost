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

/**
 * Runs retention cleanup every day at 02:00 UTC. With the policy enabled it
 * purges all expired telemetry; disabled, it still caps uptime history at the
 * 90-day default (previously an hourly sweep in {@code UptimeScheduler}), so
 * check rows never accumulate unbounded on installations that keep the
 * opt-in policy off.
 */
@Component
public class DataRetentionScheduler implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(DataRetentionScheduler.class);
	private static final LocalTime RUN_TIME = LocalTime.of(2, 0);

	private final DataRetentionSettings settings;
	private final DataRetentionService cleanup;
	private ScheduledExecutorService executor;
	private volatile boolean running;

	public DataRetentionScheduler(DataRetentionSettings settings, DataRetentionService cleanup) {
		this.settings = settings;
		this.cleanup = cleanup;
	}

	@Override
	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		// Created per start so a lifecycle stop/start cycle gets a live executor.
		executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "data-retention");
			thread.setDaemon(true);
			return thread;
		});
		Instant now = Instant.now();
		long initialDelay = Math.max(0, Duration.between(now, nextRunAfter(now)).toMillis());
		executor.scheduleAtFixedRate(this::runSafely, initialDelay, Duration.ofDays(1).toMillis(),
				TimeUnit.MILLISECONDS);
	}

	@Override
	public synchronized void stop() {
		running = false;
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	void runOnce(Instant runInstant) {
		DataRetentionSettings.Policy policy = settings.get();
		if (!policy.enabled()) {
			sweepUptime(runInstant);
			return;
		}
		Instant cutoff = runInstant.minus(Duration.ofDays(policy.retentionDays()));
		DataRetentionService.CleanupResult result = cleanup.cleanup(cutoff);
		log.info("data retention ({} days): deleted {} events, {} issues; dropped {} telemetry partitions; "
				+ "boundary-deleted {} logs, {} transactions, {} spans; {} uptime checks, {} closed incidents; "
				+ "{} projects deferred", policy.retentionDays(), result.events(), result.issues(),
				result.droppedPartitions(), result.logs(), result.transactions(), result.spans(),
				result.uptimeChecks(), result.uptimeIncidents(), result.deferredProjects());
	}

	private void sweepUptime(Instant runInstant) {
		Instant cutoff = runInstant.minus(Duration.ofDays(DataRetentionSettings.DEFAULT.retentionDays()));
		DataRetentionService.UptimeCleanup result = cleanup.cleanupUptime(cutoff);
		if (result.checks() > 0 || result.incidents() > 0) {
			log.info("uptime retention ({} days): deleted {} checks, {} closed incidents",
					DataRetentionSettings.DEFAULT.retentionDays(), result.checks(), result.incidents());
		}
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
