package dev.outpost.bench;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.Map;

/**
 * Measures bytes allocated by server-side threads over a window, using
 * per-thread allocation counters. Only threads matching {@link #REQUEST_THREAD_PREFIX}
 * or {@link #WORKER_THREAD_PREFIX} are counted, excluding the load driver's own
 * allocation on virtual threads charged to {@code ForkJoinPool} carriers.
 */
final class AllocationProbe {

	private static final String REQUEST_THREAD_PREFIX = "http-nio-";

	private static final String WORKER_THREAD_PREFIX = "ingest-worker-";

	private final com.sun.management.ThreadMXBean threads = threadBean();

	private Map<Long, Long> requestBaseline;

	private Map<Long, Long> workerBaseline;

	private long requestAllocated;

	private boolean active;

	private boolean requestPhaseFinished;

	private boolean matched;

	/** Null on a JVM without the {@code com.sun.management} extension. */
	private static com.sun.management.ThreadMXBean threadBean() {
		if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
				&& bean.isThreadAllocatedMemorySupported()) {
			bean.setThreadAllocatedMemoryEnabled(true);
			return bean;
		}
		return null;
	}

	/** Cheap enough to sit inside a measured plateau. */
	void start() {
		if (this.threads == null) {
			active = false;
			return;
		}
		Sample baseline = sample();
		requestBaseline = baseline.requests();
		workerBaseline = baseline.workers();
		requestAllocated = 0;
		active = true;
		requestPhaseFinished = false;
		matched = !requestBaseline.isEmpty() || !workerBaseline.isEmpty();
	}

	/**
	 * Captures accept-side allocation before request threads that timed out during a
	 * deep-queue drain take their allocation counters with them. Worker allocation
	 * continues to be counted until {@link #stop()}.
	 */
	void finishAcceptPhase() {
		if (!active || requestPhaseFinished) {
			return;
		}
		Sample end = sample();
		matched |= !end.requests().isEmpty();
		requestAllocated = allocatedBetween(requestBaseline, end.requests(), "request");
		requestPhaseFinished = true;
	}

	/**
	 * Ends the window and returns bytes allocated by server-side threads, or -1 when
	 * the JVM is unsupported or the thread filter matched nothing.
	 */
	long stop() {
		if (!active) {
			return -1;
		}
		finishAcceptPhase();
		Sample end = sample();
		matched |= !end.workers().isEmpty();
		long total = requestAllocated + allocatedBetween(workerBaseline, end.workers(), "worker");
		active = false;
		if (!matched) {
			System.out.println("warning: allocation probe matched no server threads; "
					+ "the request/worker thread prefixes in AllocationProbe are stale");
			return -1;
		}
		return total;
	}

	private long allocatedBetween(Map<Long, Long> baseline, Map<Long, Long> end, String group) {
		long total = 0;
		for (Map.Entry<Long, Long> thread : end.entrySet()) {
			Long before = baseline.get(thread.getKey());
			// Missing from the baseline means the thread started mid-window (e.g. pool growth); count it all.
			total += (before == null) ? thread.getValue() : thread.getValue() - before;
		}
		long vanished = baseline.keySet().stream().filter(id -> !end.containsKey(id)).count();
		if (vanished > 0) {
			// Their share since the baseline died with them, so the total is a floor.
			System.out.printf("warning: %d sampled %s thread(s) exited mid-window; allocation is understated%n",
					vanished, group);
		}
		return total;
	}

	private Sample sample() {
		long[] ids = this.threads.getAllThreadIds();
		// maxDepth 0: names only. Walking stacks here would cost more than it tells us.
		ThreadInfo[] infos = this.threads.getThreadInfo(ids, 0);
		long[] allocated = this.threads.getThreadAllocatedBytes(ids);
		Map<Long, Long> requests = new HashMap<>();
		Map<Long, Long> workers = new HashMap<>();
		for (int i = 0; i < ids.length; i++) {
			// Null info (thread died between calls) or -1 bytes (JVM won't account for it): no delta possible.
			if (infos[i] == null || allocated[i] < 0) {
				continue;
			}
			String name = infos[i].getThreadName();
			if (name.startsWith(REQUEST_THREAD_PREFIX)) {
				requests.put(ids[i], allocated[i]);
			}
			else if (name.startsWith(WORKER_THREAD_PREFIX)) {
				workers.put(ids[i], allocated[i]);
			}
		}
		return new Sample(requests, workers);
	}

	private record Sample(Map<Long, Long> requests, Map<Long, Long> workers) {
	}

}
