package dev.outpost.demo.traffic;

import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Ambient traffic so Outpost's live tail, trace list, and issue trends always
 * have fresh data. Calls the app's own HTTP endpoints through the shared
 * RestClient, so generated traffic produces real http.server transactions —
 * the same envelopes a genuine client would cause. Disable with DEMO_TRAFFIC=false.
 */
@Component
@ConditionalOnBooleanProperty("demo.traffic.enabled")
public class TrafficGenerator {

	private static final Logger log = LoggerFactory.getLogger(TrafficGenerator.class);

	private final RestClient selfClient;
	private final Random rnd = new Random();

	public TrafficGenerator(RestClient selfClient) {
		this.selfClient = selfClient;
	}

	@Scheduled(initialDelay = 15_000, fixedDelay = 20_000)
	public void ambientLogs() {
		String[] components = { "inventory", "payments", "search", "email" };
		int lines = 2 + rnd.nextInt(4);
		for (int i = 0; i < lines; i++) {
			String component = components[rnd.nextInt(components.length)];
			int weight = rnd.nextInt(10);
			if (weight < 5) {
				log.info("[{}] heartbeat ok, queue depth {}", component, rnd.nextInt(20));
			}
			else if (weight < 8) {
				log.debug("[{}] cache hit ratio {}%", component, 70 + rnd.nextInt(30));
			}
			else {
				log.warn("[{}] slow downstream response: {} ms", component, 800 + rnd.nextInt(1200));
			}
		}
	}

	@Scheduled(initialDelay = 30_000, fixedDelay = 45_000)
	public void ambientRequests() {
		int pick = rnd.nextInt(10);
		try {
			if (pick < 5) {
				selfClient.get().uri("/api/products").retrieve().body(String.class);
			}
			else if (pick < 9) {
				selfClient.post().uri("/api/checkout")
						.header("Content-Type", "application/json")
						.body(Map.of(
								"email", "traffic-bot@example.com",
								"zip", String.valueOf(10000 + rnd.nextInt(89999)),
								"items", List.of(Map.of("sku", "SKU-100" + (1 + rnd.nextInt(9)), "quantity", 1))))
						.retrieve().body(String.class);
			}
			else {
				selfClient.get().uri("/api/products/slow?ms=" + (1200 + rnd.nextInt(1800))).retrieve().body(String.class);
			}
		}
		catch (Exception e) {
			log.warn("Ambient request failed: {}", e.getMessage());
		}
	}

	@Scheduled(initialDelay = 60_000, fixedDelay = 240_000)
	public void ambientError() {
		// /api/flaky throws ~30% of the time; a few attempts make an error likely
		// without guaranteeing one — trends stay organic.
		for (int i = 0; i < 4; i++) {
			try {
				selfClient.get().uri("/api/flaky").retrieve().body(String.class);
			}
			catch (Exception e) {
				log.warn("Ambient flaky call errored (that's the point): {}", e.getMessage());
				return;
			}
		}
	}
}
