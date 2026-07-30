package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.support.EnvelopeFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password",
				"outpost.ingest.workers=1", "outpost.ingest.max-batch=1", "outpost.ingest.linger-millis=10",
				"outpost.ingest.shutdown-timeout=5s" })
@Import(TestcontainersConfiguration.class)
class IngestShutdownIntegrationTest {

	private static final int CONNECTOR_LIFECYCLE_PHASE = 2_147_481_599;

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IngestWorkers workers;

	@Autowired
	IngestQueue queue;

	@Autowired
	Environment environment;

	@Autowired
	ConfigurableApplicationContext context;

	final HttpClient http = HttpClient.newHttpClient();

	final EnvelopeFactory envelopes = new EnvelopeFactory(1);

	long projectId;

	String publicKey;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM event").update();
		jdbc.sql("DELETE FROM issue").update();
		jdbc.sql("DELETE FROM project").update();
		projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES ('shutdown', 'Shutdown') RETURNING id")
			.query(Long.class)
			.single();
		publicKey = "0123456789abcdef0123456789abcdef";
		jdbc.sql("INSERT INTO project_key (project_id, public_key) VALUES (?, ?)")
			.param(projectId)
			.param(publicKey)
			.update();
	}

	@Test
	void workersStopAfterTheConnector() {
		assertThat(workers.getPhase()).isLessThan(CONNECTOR_LIFECYCLE_PHASE);
	}

	@Test
	void gracefulShutdownHasAnExplicitPhaseTimeout() {
		assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
		assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase", Duration.class))
			.isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void applicationShutdownStoresEveryAcceptedEnvelope() {
		int accepted = 200;
		List<CompletableFuture<HttpResponse<String>>> responses = new ArrayList<>(accepted);
		for (int i = 0; i < accepted; i++) {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/api/" + projectId + "/envelope/?sentry_key=" + publicKey))
				.header("Content-Type", "application/x-sentry-envelope")
				.POST(HttpRequest.BodyPublishers.ofString(envelopes.error("prod")))
				.build();
			responses.add(http.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
		}
		CompletableFuture.allOf(responses.toArray(CompletableFuture[]::new)).join();
		assertThat(responses).allSatisfy(response -> assertThat(response.join().statusCode()).isEqualTo(200));
		assertThat(queue.outstanding()).isPositive();

		context.stop();

		assertThat(jdbc.sql("SELECT count(*) FROM event").query(Long.class).single()).isEqualTo(accepted);
	}

}
