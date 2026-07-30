package dev.outpost.bench;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.Map;

/**
 * Measures how many bytes ingest allocates over a window, so a change that
 * claims to remove a copy has to prove it. Throughput cannot: dropping a
 * {@code deepCopy()} shows up as allocation immediately and as stored/s only
 * once GC is the constraint, which on a 2 GB heap it usually is not.
 *
 * <p>{@code TODO.md} #3 (ingest holds ~6 copies of every event) and #102
 * (spool envelope bytes to disk, so per-item heap stops tracking envelope size)
 * both state their acceptance in bytes. This is the instrument for both.
 *
 * <h2>Why per-thread counters rather than heap deltas</h2>
 *
 * The obvious approach — {@code Runtime.totalMemory() - freeMemory()} before and
 * after — measures <em>retention</em> at two arbitrary points on the GC's
 * sawtooth, so it reports whatever the collector happened to be doing. The
 * per-thread counter behind {@code getThreadAllocatedBytes} is monotonic and
 * GC-independent: it counts every byte the thread ever handed out, whether it
 * survived a millisecond or the whole run. That makes runs comparable without
 * forcing collections or pinning GC flags.
 *
 * <h2>Why the thread filter</h2>
 *
 * On the in-JVM benchmark the load driver shares the process with the server, so
 * an unfiltered total would include envelope generation and the HTTP client —
 * exactly the allocation no product change will ever move. Only threads that do
 * server-side work are sampled: Tomcat's {@code http-nio-*} for the accept path,
 * and {@code ingest-worker-*} for the drain. {@link LoadDriver} dispatches on
 * virtual threads, whose allocation is charged to their {@code ForkJoinPool}
 * carriers, so the driver falls outside the filter by construction rather than
 * by luck.
 *
 * <p>The corollary is that this counts the two threads pools we can actually
 * change, not process-wide allocation. Hikari's housekeeper and the JDBC driver
 * allocate on whichever thread calls them — the workers — so they are counted,
 * correctly.
 */
final class AllocationProbe {

	/**
	 * Tomcat's request threads and the ingest workers. If Tomcat is ever switched
	 * to virtual threads ({@code spring.threads.virtual.enabled}) the first prefix
	 * stops matching and the probe reports nothing rather than something wrong —
	 * see {@link #stop()}.
	 */
	private static final String[] SERVER_THREADS = { "http-nio-", "ingest-worker-" };

	private final com.sun.management.ThreadMXBean threads = threadBean();

	private Map<Long, Long> baseline;

	/** Null on a JVM without the {@code com.sun.management} extension. */
	private static com.sun.management.ThreadMXBean threadBean() {
		if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
				&& bean.isThreadAllocatedMemorySupported()) {
			bean.setThreadAllocatedMemoryEnabled(true);
			return bean;
		}
		return null;
	}

	/** Begins a window. Cheap enough to sit inside a measured plateau. */
	void start() {
		baseline = (this.threads == null) ? null : sample();
	}

	/**
	 * Ends the window and returns bytes allocated by server-side threads, or -1
	 * when the figure would be a guess — an unsupported JVM, or a filter that
	 * matched no threads at all. -1 renders as an em dash rather than as a
	 * confident zero, because a measurement tool reporting zero when it has lost
	 * sight of its subject is worse than one reporting nothing.
	 */
	long stop() {
		if (baseline == null) {
			return -1;
		}
		Map<Long, Long> end = sample();
		if (end.isEmpty()) {
			System.out.println("warning: allocation probe matched no server threads; "
					+ "the names in AllocationProbe.SERVER_THREADS are stale");
			return -1;
		}
		long total = 0;
		for (Map.Entry<Long, Long> thread : end.entrySet()) {
			Long before = baseline.get(thread.getKey());
			// Absent from the baseline means the thread started mid-window — Tomcat
			// grows its pool under load — so all of its allocation belongs to us.
			total += (before == null) ? thread.getValue() : thread.getValue() - before;
		}
		long vanished = baseline.keySet().stream().filter(id -> !end.containsKey(id)).count();
		if (vanished > 0) {
			// Their share since the baseline died with them, so the total is a floor.
			System.out.printf("warning: %d sampled thread(s) exited mid-window; allocation is understated%n", vanished);
		}
		baseline = null;
		return total;
	}

	private Map<Long, Long> sample() {
		long[] ids = this.threads.getAllThreadIds();
		// maxDepth 0: names only. Walking stacks here would cost more than it tells us.
		ThreadInfo[] infos = this.threads.getThreadInfo(ids, 0);
		long[] allocated = this.threads.getThreadAllocatedBytes(ids);
		Map<Long, Long> sampled = new HashMap<>();
		for (int i = 0; i < ids.length; i++) {
			// A null info is a thread that died between the two calls; -1 bytes is a
			// thread the JVM will not account for. Neither can contribute a delta.
			if (infos[i] != null && allocated[i] >= 0 && isServerThread(infos[i].getThreadName())) {
				sampled.put(ids[i], allocated[i]);
			}
		}
		return sampled;
	}

	private static boolean isServerThread(String name) {
		for (String prefix : SERVER_THREADS) {
			if (name.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

}
