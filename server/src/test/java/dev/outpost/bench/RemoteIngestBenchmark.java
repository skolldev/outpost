package dev.outpost.bench;

import dev.outpost.support.EnvelopeFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The same load driver pointed at a real deployment, for when an absolute
 * capacity number is wanted rather than a before/after comparison. Boots no
 * Spring context and starts no container.
 *
 * <pre>
 * ./gradlew ingestBenchmark -Pbench.target=http://host:8080 \
 *     -Pbench.projectId=1 -Pbench.key=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
 * </pre>
 *
 * <p>Deliberately thinner than {@link IngestBenchmark}: with the server on
 * another host there is no JDBC handle for stored-row counts and no {@code
 * MeterRegistry} to read, so this reports client-side figures plus whatever the
 * instance's own {@code outpost_ingest_*} meters say — scrape
 * {@code /actuator/prometheus} on the management port during the run to pair
 * queue depth and queue wait with the table below.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "outpost.bench.target", matches = ".+")
@Tag("benchmark")
@Tag("ingest")
class RemoteIngestBenchmark {

	private static final int[] STEP_RATES = { 200, 400, 800, 1600, 3200 };

	private static final Duration STEP_DURATION = Duration.ofSeconds(15);

	private final BenchReport report = new BenchReport("Outpost ingest benchmark (remote target)");

	private final EnvelopeFactory envelopes = new EnvelopeFactory();

	private final LoadDriver driver = new LoadDriver();

	private final String target = System.getProperty("outpost.bench.target");

	private final String projectId = System.getProperty("outpost.bench.projectId", "1");

	private final String key = System.getProperty("outpost.bench.key", "");

	@Test
	void errorEnvelopeStepLoad() throws Exception {
		report.condition("target", target);
		report.condition("project_id", projectId);
		verifyTargetAcceptsEnvelopes();

		// Warm-up: JIT plus, on a cold instance, the first week's partition DDL.
		driver.run(new LoadDriver.Step(50, Duration.ofSeconds(5)), requests());
		for (int rate : STEP_RATES) {
			LoadDriver.Result result = driver.run(new LoadDriver.Step(rate, STEP_DURATION), requests());
			System.out.printf("%5d/s offered → %6d ok, %6d 429, p99 %.1f ms%n", rate, result.status(200),
					result.status(429), result.p99Millis());
			// Storage, queue and allocation columns stay blank: not visible from here.
			report.add(new BenchReport.Row("error", rate + "/s", result, Double.NaN, -1, Double.NaN, -1));
		}
	}

	@AfterAll
	void writeReport() {
		driver.close();
		report.write();
	}

	/** Fails fast and loudly rather than reporting a table of 403s as a result. */
	private void verifyTargetAcceptsEnvelopes() throws Exception {
		try (HttpClient http = HttpClient.newHttpClient()) {
			HttpResponse<String> response = http.send(requests().get(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IllegalStateException("target rejected a probe envelope with HTTP " + response.statusCode()
						+ " (" + response.body() + "); check -Pbench.projectId and -Pbench.key");
			}
		}
	}

	private Supplier<HttpRequest> requests() {
		URI uri = URI.create(target + "/api/" + projectId + "/envelope/?sentry_key=" + key);
		Supplier<String> envelope = () -> envelopes.error("prod");
		return () -> HttpRequest.newBuilder(uri)
			.timeout(LoadDriver.REQUEST_TIMEOUT)
			.header("Content-Type", "application/x-sentry-envelope")
			.POST(HttpRequest.BodyPublishers.ofByteArray(envelope.get().getBytes(StandardCharsets.UTF_8)))
			.build();
	}

}
