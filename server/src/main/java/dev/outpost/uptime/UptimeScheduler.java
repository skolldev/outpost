package dev.outpost.uptime;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Drives uptime monitors: a single-thread coordinator polls for monitors whose
 * {@code next_check_at} is due and fans probes out to virtual threads, one
 * in-flight probe per monitor. A monitor is provisionally re-armed before its
 * probe starts, then re-armed from completion by
 * {@link UptimeCheckService#recordResult}. The provisional schedule prevents a
 * result-recording failure from making the coordinator immediately probe the
 * still-due monitor again, while the in-flight set prevents slow probes from
 * overlapping.
 *
 * <p>Also sweeps retention hourly: raw check rows and closed incidents older
 * than 90 days.
 *
 * <p>Assumes a single Outpost instance (like the rest of the app). If
 * replicas ever matter, add {@code FOR UPDATE SKIP LOCKED} to the due query.
 */
@Component
public class UptimeScheduler implements SmartLifecycle {

	private record DueMonitor(long id, String url, int intervalSeconds, int timeoutSeconds) {
	}

	private static final Logger log = LoggerFactory.getLogger(UptimeScheduler.class);
	private static final int RETENTION_DAYS = 90;

	private final JdbcClient jdbc;
	private final UptimeProber prober;
	private final UptimeCheckService checkService;
	private final long tickMillis;

	private final ScheduledExecutorService coordinator = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "uptime-coordinator");
		thread.setDaemon(true);
		return thread;
	});
	private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();
	private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
	private volatile boolean running;

	public UptimeScheduler(JdbcClient jdbc, UptimeProber prober, UptimeCheckService checkService,
			@Value("${outpost.uptime.tick-millis:1000}") long tickMillis) {
		this.jdbc = jdbc;
		this.prober = prober;
		this.checkService = checkService;
		this.tickMillis = tickMillis;
	}

	@Override
	public void start() {
		running = true;
		coordinator.scheduleWithFixedDelay(this::tick, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
		coordinator.scheduleWithFixedDelay(this::sweepRetention, 1, 60, TimeUnit.MINUTES);
	}

	@Override
	public void stop() {
		running = false;
		coordinator.shutdownNow();
		// Don't await in-flight probes: virtual threads, longest lingers one
		// timeout (≤30 s) past shutdown — same fire-and-forget as LogTail.
		probes.shutdownNow();
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void tick() {
		try {
			List<DueMonitor> due = jdbc.sql("""
					SELECT id, url, interval_seconds, timeout_seconds FROM uptime_monitor
					WHERE next_check_at <= now()
					""")
				.query((rs, i) -> new DueMonitor(rs.getLong("id"), rs.getString("url"), rs.getInt("interval_seconds"),
						rs.getInt("timeout_seconds")))
				.list();
			for (DueMonitor monitor : due) {
				// One in-flight probe per monitor; parallel across monitors.
				if (inFlight.add(monitor.id())) {
					try {
						// Claim before making the outbound request. recordResult normally
						// moves this to one interval after completion; this provisional
						// schedule is retained if recording the result rolls back.
						int claimed = jdbc.sql("""
								UPDATE uptime_monitor
								SET next_check_at = now() + make_interval(secs => ?)
								WHERE id = ? AND next_check_at <= now()
								""")
							.param(monitor.intervalSeconds())
							.param(monitor.id())
							.update();
						if (claimed == 0) {
							inFlight.remove(monitor.id());
							continue;
						}
						probes.submit(() -> check(monitor));
					}
					catch (RuntimeException e) {
						inFlight.remove(monitor.id());
						throw e;
					}
				}
			}
		}
		catch (RuntimeException e) {
			// A DB hiccup must not kill the coordinator.
			log.warn("uptime tick failed: {}", e.toString());
		}
	}

	private void check(DueMonitor monitor) {
		try {
			var result = prober.probe(monitor.url(), monitor.timeoutSeconds());
			checkService.recordResult(monitor.id(), monitor.intervalSeconds(), result);
		}
		catch (DataIntegrityViolationException e) {
			log.debug("monitor {} deleted mid-probe", monitor.id());
		}
		catch (RuntimeException e) {
			log.warn("uptime check for monitor {} failed: {}", monitor.id(), e.toString());
		}
		finally {
			inFlight.remove(monitor.id());
		}
	}

	void sweepRetention() {
		try {
			int checks = jdbc.sql("DELETE FROM uptime_check WHERE checked_at < now() - make_interval(days => ?)")
				.param(RETENTION_DAYS)
				.update();
			int incidents = jdbc
				.sql("DELETE FROM uptime_incident WHERE closed_at IS NOT NULL AND closed_at < now() - make_interval(days => ?)")
				.param(RETENTION_DAYS)
				.update();
			if (checks > 0 || incidents > 0) {
				log.info("uptime retention: deleted {} checks, {} closed incidents", checks, incidents);
			}
		}
		catch (RuntimeException e) {
			log.warn("uptime retention sweep failed: {}", e.toString());
		}
	}
}
