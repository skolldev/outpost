package dev.outpost.demo.api;

import tools.jackson.databind.ObjectMapper;
import io.sentry.Attachment;
import io.sentry.Hint;
import io.sentry.Sentry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ScenarioController {

	private static final Logger log = LoggerFactory.getLogger(ScenarioController.class);

	private final ObjectMapper objectMapper;
	private final Random rnd = new Random();

	public ScenarioController(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@GetMapping("/boom")
	public Map<String, Object> boom() {
		log.error("Payment provider handshake failed, giving up");
		throw new IllegalStateException("Payment provider unreachable");
	}

	@GetMapping("/flaky")
	public Map<String, Object> flaky() {
		if (rnd.nextInt(10) < 3) {
			throw new RuntimeException("Inventory sync race: stock count went negative");
		}
		log.debug("Flaky endpoint survived this time");
		return Map.of("ok", true);
	}

	@PostMapping("/handled")
	public Map<String, Object> handled() throws Exception {
		Map<String, Object> cart = Map.of(
				"items", List.of(
						Map.of("sku", "SKU-1003", "quantity", 1),
						Map.of("sku", "SKU-1008", "quantity", 2)),
				"couponCode", "SUMMER-EXPIRED",
				"totalCents", 44700);
		byte[] snapshot = objectMapper.writeValueAsBytes(cart);
		try {
			throw new IllegalStateException("Payment declined: card expired");
		}
		catch (IllegalStateException e) {
			log.warn("Payment declined, captured with cart snapshot attachment");
			Hint hint = new Hint();
			hint.addAttachment(new Attachment(snapshot, "cart-snapshot.json", "application/json"));
			Sentry.captureException(e, hint, scope -> {
				scope.setTag("checkout.step", "payment");
				scope.setTag("payment.method", "card");
				scope.setFingerprint(List.of("demo", "payment-declined"));
			});
			return Map.of("captured", true);
		}
	}

	@PostMapping("/logburst")
	public Map<String, Object> logBurst(@RequestParam(defaultValue = "200") int n) {
		int count = Math.min(n, 10_000);
		String[] components = { "inventory", "payments", "recommendations", "email", "search" };
		for (int i = 0; i < count; i++) {
			String component = components[i % components.length];
			int orderId = 1000 + rnd.nextInt(9000);
			switch (i % 5) {
				case 0 -> log.trace("[{}] cache probe {} for order {}", component, i, orderId);
				case 1 -> log.debug("[{}] recomputed ranking for order {} in {} ms", component, orderId, rnd.nextInt(40));
				case 2 -> log.info("[{}] processed order {} ({} of {})", component, orderId, i + 1, count);
				case 3 -> log.warn("[{}] retrying webhook for order {}, attempt {}", component, orderId, 1 + rnd.nextInt(3));
				default -> log.error("[{}] dead-letter: order {} exhausted retries", component, orderId);
			}
		}
		log.info("Log burst finished: {} records emitted", count);
		return Map.of("emitted", count);
	}
}
