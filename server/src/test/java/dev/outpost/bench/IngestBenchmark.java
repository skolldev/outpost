package dev.outpost.bench;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.ingest.IngestQueue;
import dev.outpost.support.EnvelopeFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Answers "how many Sentry envelopes can we ingest before things go wrong", and
 * which thing goes wrong first. Opt-in ({@code ./gradlew ingestBenchmark},
 * excluded from {@code test}): it reports throughput rather than asserting a
 * threshold, only asserting that the driver delivered the offered load and the
 * server survived.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("benchmark")
@Tag("ingest")
class IngestBenchmark {

	/**
	 * Offered envelope rates per plateau; the knee should land mid-ladder. Rates
	 * differ per signal because an envelope isn't a unit of work — a log envelope
	 * carries {@link #LOG_RECORDS_PER_ITEM} records and a transaction fans into
	 * {@link #SPANS_PER_TRANSACTION} spans — so one shared ladder would saturate
	 * the heavier signals immediately.
	 */
	private static final int[] ERROR_RATES = { 200, 400, 800, 1600, 3200 };

	private static final int[] LOG_RATES = { 40, 80, 160, 320, 640 };

	private static final int[] TRANSACTION_RATES = { 100, 200, 400, 800, 1600 };

	/** Long enough for the buffer to visibly fill at an unsustainable rate. */
	private static final Duration STEP_DURATION = Duration.ofSeconds(15);

	/**
	 * Highest rate already demonstrated to reach the application without exhausting
	 * the local HTTP driver or ephemeral-port range.
	 */
	private static final int BURST_RATE = 3200;

	private static final int BURST_QUEUE_MULTIPLE = 3;

	private static final int LOG_RECORDS_PER_ITEM = 100;

	private static final int SPANS_PER_TRANSACTION = 5;

	private static final int PROJECTS = 4;

	private static final BenchReport REPORT = new BenchReport("Outpost ingest benchmark");

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IngestQueue queue;

	@Autowired
	MeterRegistry meters;

	final EnvelopeFactory envelopes = new EnvelopeFactory();

	final LoadDriver driver = new LoadDriver();

	final AllocationProbe allocation = new AllocationProbe();

	List<Long> projectIds = new ArrayList<>();

	List<String> keys = new ArrayList<>();

	@BeforeEach
	void seedProjects() throws InterruptedException {
		// Drain before dropping projects: queued items still reference them, and deleting first just logs FK failures.
		awaitDrain(Duration.ofMinutes(2));
		clearTelemetry();
		jdbc.sql("DELETE FROM project").update();
		projectIds = new ArrayList<>();
		keys = new ArrayList<>();
		for (int i = 0; i < PROJECTS; i++) {
			long id = jdbc.sql("INSERT INTO project (slug, name) VALUES (?, ?) RETURNING id")
				.param("bench-" + i)
				.param("Bench " + i)
				.query(Long.class)
				.single();
			String key = String.format("%032x", i + 1);
			jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, ?)").param(id).param(key).update();
			projectIds.add(id);
			keys.add(key);
		}
		REPORT.condition("queue_capacity", queue.capacity());
		REPORT.condition("step_seconds", STEP_DURATION.toSeconds());
	}

	@Test
	void errorEnvelopeStepLoad() throws Exception {
		warmUp(() -> envelopes.error("prod"), 0);
		stepLoad("error", "event", ERROR_RATES, () -> envelopes.error("prod"), 0);
	}

	@Test
	void logEnvelopeStepLoad() throws Exception {
		warmUp(() -> envelopes.log("prod", LOG_RECORDS_PER_ITEM), 0);
		// One envelope carries 100 records, so stored/s here counts records, while offered/s stays envelopes/s.
		stepLoad("log x" + LOG_RECORDS_PER_ITEM, "log_record", LOG_RATES,
				() -> envelopes.log("prod", LOG_RECORDS_PER_ITEM), 0);
	}

	@Test
	void transactionEnvelopeStepLoad() throws Exception {
		warmUp(() -> envelopes.transaction("prod", SPANS_PER_TRANSACTION), 0);
		stepLoad("transaction", "txn", TRANSACTION_RATES, () -> envelopes.transaction("prod", SPANS_PER_TRANSACTION), 0);
	}

	/**
	 * Same offered rate and worker pool; only the project count differs.
	 * {@code EventStore.storeAll} holds {@code pg_advisory_xact_lock} per project
	 * across the batch, so flat single-project throughput that scales with more
	 * projects points at that lock, not the workers, as the ceiling.
	 */
	@Test
	void singleVersusMultiProjectThroughput() throws Exception {
		int rate = contendedRate();
		warmUp(() -> envelopes.error("prod"), 0);

		drainAndClear();
		REPORT.add(measure("1 project", "event", rate, request(0, () -> envelopes.error("prod"))));

		drainAndClear();
		REPORT.add(measure(PROJECTS + " projects", "event", rate, roundRobinRequest(() -> envelopes.error("prod"))));
	}

	/**
	 * Deliberate overload: offers {@link #BURST_QUEUE_MULTIPLE} buffers' worth of
	 * envelopes at a fixed rate and checks the server sheds rather than falls over
	 * (ADR 0002). Rate stays fixed as capacity grows — duration carries the extra
	 * volume — since scaling rate with capacity can exhaust the driver's in-flight
	 * limit or ephemeral ports before requests reach ingest.
	 */
	@Test
	void burstBeyondQueueCapacity() throws Exception {
		warmUp(() -> envelopes.error("prod"), 0);
		drainAndClear();

		LoadDriver.Step step = burstStep(queue.capacity());
		QueueWaitMark waitBefore = queueWaitMark("error");
		allocation.start();
		LoadDriver.Result result = driver.run(step, request(0, () -> envelopes.error("prod")));
		allocation.finishAcceptPhase();

		// Sampled while the backlog is still there — after the drain below, readiness would just read green idle.
		int readiness = readyz();
		long depth = queue.size();
		System.out.printf("burst of %d envelopes at %d/s: %d accepted, %d rejected, %d failed, %d shed%n",
				result.offered(), step.ratePerSecond(), result.status(200), result.status(429), result.failures(),
				result.shed());
		result.failureCounts().forEach((cause, count) -> System.out.printf("  %6d × %s%n", count, cause));

		// Drain inside the allocation window, as in measure(): a burst stores most of its rows after load stops.
		awaitDrain(Duration.ofMinutes(2));
		double waitAverage = queueWaitAverageMillis("error", waitBefore);
		REPORT.add(new BenchReport.Row("burst", step.ratePerSecond() + "/s for " + step.duration().toSeconds() + "s",
				result, Double.NaN, depth, waitAverage, allocation.stop()));

		long serverErrors = result.statusCounts()
			.entrySet()
			.stream()
			.filter(entry -> entry.getKey() >= 500)
			.mapToLong(Map.Entry::getValue)
			.sum();
		assertDriverHealthy(result, "burst");
		assertThat(serverErrors).as("server errors under burst").isZero();
		assertThat(result.status(429)).as("429s: the buffer must shed once full").isPositive();
		assertThat(readiness).as("readiness under a full buffer").isEqualTo(200);

		// Reported, not asserted: a shortfall means acknowledged telemetry we failed to store — a defect, not a gate.
		System.out.printf("burst durability: %d stored vs %d acknowledged%n", rowCount("event"), result.status(200));
	}

	@AfterAll
	void writeReport() {
		driver.close();
		REPORT.write();
	}

	// ------------------------------------------------------------------ scenario

	private void stepLoad(String scenario, String table, int[] rates, Supplier<String> envelope, int projectIndex)
			throws Exception {
		for (int rate : rates) {
			// Each plateau starts from an empty buffer, so it doesn't inherit the previous step's backlog.
			drainAndClear();
			REPORT.add(measure(scenario, table, rate, request(projectIndex, envelope)));
		}
	}

	/** Runs one plateau and pairs the client-side result with what the server saw. */
	private BenchReport.Row measure(String scenario, String table, int rate, Supplier<HttpRequest> requests)
			throws InterruptedException {
		long rowsBefore = rowCount(table);
		Instant startedAt = Instant.now();
		QueueWaitMark waitBefore = queueWaitMark(signalOf(table));
		allocation.start();
		LoadDriver.Result result = driver.run(new LoadDriver.Step(rate, STEP_DURATION), requests);
		allocation.finishAcceptPhase();
		double elapsedSeconds = Duration.between(startedAt, Instant.now()).toMillis() / 1000.0;
		// Read before draining: queue depth is the leading indicator and stored/s is a rate over the offered window.
		long depth = queue.size();
		double stored = (rowCount(table) - rowsBefore) / elapsedSeconds;
		// Allocation spans the drain too, since past the knee most store work happens after load stops.
		awaitDrain(Duration.ofMinutes(2));
		double waitAverage = queueWaitAverageMillis(signalOf(table), waitBefore);
		long allocated = allocation.stop();
		System.out.printf("%-14s %5d/s offered → %6d ok, %6d 429, depth %5d, %.0f rows/s, %.0f MB allocated%n", scenario,
				rate, result.status(200), result.status(429), depth, stored, allocated / (1024.0 * 1024.0));
		assertDriverHealthy(result, scenario + " @ " + rate + "/s");
		return new BenchReport.Row(scenario, rate + "/s", result, stored, depth, waitAverage, allocated);
	}

	/**
	 * Above the expected single-project knee, so both arms are supply-limited and
	 * the comparison measures lock contention, not driver throughput.
	 */
	private int contendedRate() {
		return ERROR_RATES[ERROR_RATES.length - 1];
	}

	static LoadDriver.Step burstStep(int queueCapacity) {
		long offered = Math.multiplyExact((long) queueCapacity, BURST_QUEUE_MULTIPLE);
		long seconds = Math.ceilDiv(offered, BURST_RATE);
		return new LoadDriver.Step(BURST_RATE, Duration.ofSeconds(seconds));
	}

	// ------------------------------------------------------------------ helpers

	private Supplier<HttpRequest> request(int projectIndex, Supplier<String> envelope) {
		URI uri = envelopeUri(projectIndex);
		return () -> post(uri, envelope.get());
	}

	/** Spreads envelopes across every seeded project, one per request, round-robin. */
	private Supplier<HttpRequest> roundRobinRequest(Supplier<String> envelope) {
		List<URI> uris = new ArrayList<>();
		for (int i = 0; i < PROJECTS; i++) {
			uris.add(envelopeUri(i));
		}
		AtomicInteger next = new AtomicInteger();
		return () -> post(uris.get(Math.floorMod(next.getAndIncrement(), uris.size())), envelope.get());
	}

	private URI envelopeUri(int projectIndex) {
		return URI.create("http://localhost:" + port + "/api/" + projectIds.get(projectIndex) + "/envelope/?sentry_key="
				+ keys.get(projectIndex));
	}

	private static HttpRequest post(URI uri, String envelope) {
		return HttpRequest.newBuilder(uri)
			.timeout(LoadDriver.REQUEST_TIMEOUT)
			.header("Content-Type", "application/x-sentry-envelope")
			.POST(HttpRequest.BodyPublishers.ofByteArray(envelope.getBytes(StandardCharsets.UTF_8)))
			.build();
	}

	/** Warms JIT, the connection pool, and partition DDL, which is a one-off cost. */
	private void warmUp(Supplier<String> envelope, int projectIndex) throws InterruptedException {
		LoadDriver.Result result = driver.run(new LoadDriver.Step(50, Duration.ofSeconds(5)),
				request(projectIndex, envelope));
		awaitDrain(Duration.ofSeconds(60));
		assertDriverHealthy(result, "warm-up");
	}

	private void drainAndClear() throws InterruptedException {
		awaitDrain(Duration.ofMinutes(2));
		clearTelemetry();
	}

	private void awaitDrain(Duration timeout) throws InterruptedException {
		awaitIdle(queue::outstanding, timeout);
	}

	static void awaitIdle(IntSupplier outstanding, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (true) {
			int remaining = outstanding.getAsInt();
			if (remaining == 0) {
				return;
			}
			long nanosLeft = deadline - System.nanoTime();
			if (nanosLeft <= 0) {
				throw new AssertionError(remaining + (remaining == 1 ? " item" : " items")
						+ " still queued or in flight after " + timeout);
			}
			Thread.sleep(Math.min(200, Math.max(1, Duration.ofNanos(nanosLeft).toMillis())));
		}
	}

	private void clearTelemetry() {
		jdbc.sql("DELETE FROM span").update();
		jdbc.sql("DELETE FROM txn").update();
		jdbc.sql("DELETE FROM log_record").update();
		jdbc.sql("DELETE FROM event").update();
		// Both issue rollups are FK-cascaded from `issue`, so deleting it clears them.
		jdbc.sql("DELETE FROM issue").update();
	}

	private long rowCount(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
	}

	private static String signalOf(String table) {
		return switch (table) {
			case "log_record" -> "log";
			case "txn" -> "transaction";
			default -> "error";
		};
	}

	private QueueWaitMark queueWaitMark(String signal) {
		Timer timer = meters.get("outpost.ingest.queue.wait")
			.tag("signal", signal)
			.timer();
		return new QueueWaitMark(timer.count(), timer.totalTime(TimeUnit.MILLISECONDS));
	}

	private double queueWaitAverageMillis(String signal, QueueWaitMark before) {
		QueueWaitMark after = queueWaitMark(signal);
		long count = after.count() - before.count();
		return count == 0 ? Double.NaN : (after.totalMillis() - before.totalMillis()) / count;
	}

	private static void assertDriverHealthy(LoadDriver.Result result, String scenario) {
		assertThat(result.failures()).as("driver/socket failures during " + scenario).isZero();
		assertThat(result.shed()).as("requests shed by the load driver during " + scenario).isZero();
	}

	private record QueueWaitMark(long count, double totalMillis) {
	}

	private int readyz() throws Exception {
		try (HttpClient http = HttpClient.newHttpClient()) {
			return http
				.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/readyz")).build(),
						HttpResponse.BodyHandlers.discarding())
				.statusCode();
		}
	}

}
