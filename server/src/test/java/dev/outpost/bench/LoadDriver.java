package dev.outpost.bench;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.concurrent.locks.LockSupport;

/**
 * An open-loop HTTP load generator: it offers requests at a fixed rate
 * regardless of how fast the server answers, and measures each request's latency
 * from the instant it was <em>due to be sent</em>.
 *
 * <p>Both properties matter. A closed-loop driver — N threads each looping
 * send-then-wait — throttles itself the moment the server slows down, so offered
 * load silently tracks served load and the saturation point never appears. And
 * timing from the actual send instead of the intended one is the classic
 * coordinated-omission error: a server stalled for a second looks fast because
 * the driver simply stopped asking during the stall. Here the backlog shows up
 * in the percentiles, which is the whole point of the exercise.
 *
 * <p>Requests are dispatched onto virtual threads, so a slow server costs
 * parked continuations rather than platform threads. {@link #MAX_IN_FLIGHT} caps
 * the backlog so a wedged server exhausts the driver's patience rather than its
 * heap; anything shed past the cap is reported rather than hidden.
 */
public final class LoadDriver implements AutoCloseable {

	/** Enough backlog to expose a real stall, small enough not to OOM the driver. */
	private static final int MAX_IN_FLIGHT = 20_000;

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

	/** One rate plateau: offer {@code ratePerSecond} for {@code duration}. */
	public record Step(int ratePerSecond, Duration duration) {
	}

	/**
	 * What one plateau produced. {@code statusCounts} is keyed by HTTP status;
	 * the interesting keys are 200 (queued) and 429 (buffer full).
	 */
	public record Result(int targetRate, long offered, long completed, double achievedSendRate,
			Map<Integer, Long> statusCounts, Map<String, Long> failureCounts, long shed, double p50Millis,
			double p95Millis, double p99Millis, double maxMillis) {

		public long status(int code) {
			return statusCounts.getOrDefault(code, 0L);
		}

		public long failures() {
			return failureCounts.values().stream().mapToLong(Long::longValue).sum();
		}

	}

	private final HttpClient http = HttpClient.newBuilder()
		// HTTP/1.1 with connection pooling is what the Sentry SDKs actually do,
		// and it keeps the driver from multiplexing everything onto one stream.
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(5))
		.executor(Executors.newVirtualThreadPerTaskExecutor())
		.build();

	/**
	 * Offers requests from {@code requests} at the step's rate for its duration,
	 * then waits out the in-flight tail.
	 *
	 * @param requests called once per request, on the sending virtual thread — it
	 * must be thread-safe and should return a fresh envelope each time
	 */
	public Result run(Step step, Supplier<HttpRequest> requests) throws InterruptedException {
		int offered = Math.toIntExact(step.ratePerSecond() * step.duration().toMillis() / 1000);
		long intervalNanos = TimeUnit.SECONDS.toNanos(1) / step.ratePerSecond();

		long[] latencies = new long[offered];
		AtomicInteger latencyIndex = new AtomicInteger();
		Map<Integer, LongAdder> statuses = new ConcurrentHashMap<>();
		Map<String, LongAdder> failures = new ConcurrentHashMap<>();
		LongAdder shed = new LongAdder();
		Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);
		CountDownLatch done = new CountDownLatch(offered);

		long startNanos = System.nanoTime();
		for (int i = 0; i < offered; i++) {
			long dueAt = startNanos + i * intervalNanos;
			parkUntil(dueAt);
			if (!inFlight.tryAcquire()) {
				shed.increment();
				done.countDown();
				continue;
			}
			Thread.ofVirtual().start(() -> {
				try {
					HttpResponse<Void> response = http.send(requests.get(), HttpResponse.BodyHandlers.discarding());
					statuses.computeIfAbsent(response.statusCode(), code -> new LongAdder()).increment();
					latencies[latencyIndex.getAndIncrement()] = System.nanoTime() - dueAt;
				}
				catch (Exception e) {
					// Classified, because "9454 failed" is not a finding: a connection
					// reset means the accept backlog broke before the ingest buffer
					// did, which is a completely different bottleneck from a timeout.
					failures.computeIfAbsent(describe(e), key -> new LongAdder()).increment();
				}
				finally {
					inFlight.release();
					done.countDown();
				}
			});
		}
		long offerNanos = System.nanoTime() - startNanos;
		// Generous: the tail after a saturated plateau is exactly what we want to see.
		if (!done.await(2, TimeUnit.MINUTES)) {
			// Requests still in flight would race the array read below, so say so
			// rather than sorting a half-written buffer and reporting the result.
			failures.computeIfAbsent("still in flight after 2m", key -> new LongAdder()).add(done.getCount());
		}

		long[] samples = Arrays.copyOf(latencies, latencyIndex.get());
		Arrays.sort(samples);
		Map<Integer, Long> statusCounts = new TreeMap<>();
		statuses.forEach((code, count) -> statusCounts.put(code, count.sum()));
		Map<String, Long> failureCounts = new TreeMap<>();
		failures.forEach((cause, count) -> failureCounts.put(cause, count.sum()));
		return new Result(step.ratePerSecond(), offered, samples.length,
				offered / (offerNanos / (double) TimeUnit.SECONDS.toNanos(1)), statusCounts, failureCounts, shed.sum(),
				percentileMillis(samples, 0.50), percentileMillis(samples, 0.95), percentileMillis(samples, 0.99),
				percentileMillis(samples, 1.0));
	}

	/** Exception type plus message, trimmed to something that fits a table cell. */
	private static String describe(Exception e) {
		Throwable root = e;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		String message = root.getMessage();
		if (message == null) {
			return root.getClass().getSimpleName();
		}
		// Collapse ports, addresses and counts, or every ephemeral port becomes its
		// own bucket and the breakdown is thousands of rows long.
		return root.getClass().getSimpleName() + ": " + message.split("\\R")[0].replaceAll("\\d+", "N");
	}

	private static void parkUntil(long dueAtNanos) {
		long wait = dueAtNanos - System.nanoTime();
		if (wait > 0) {
			LockSupport.parkNanos(wait);
		}
	}

	private static double percentileMillis(long[] sortedNanos, double quantile) {
		if (sortedNanos.length == 0) {
			return Double.NaN;
		}
		int index = (int) Math.ceil(quantile * sortedNanos.length) - 1;
		return sortedNanos[Math.clamp(index, 0, sortedNanos.length - 1)] / 1_000_000.0;
	}

	@Override
	public void close() {
		http.close();
	}

}
