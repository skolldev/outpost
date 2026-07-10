package dev.outpost.demo.api;

import dev.outpost.demo.catalog.Product;
import dev.outpost.demo.catalog.ProductRepository;
import dev.outpost.demo.catalog.OrderRepository;
import dev.outpost.demo.catalog.ShopOrder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api")
public class CheckoutController {

	private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

	private final ProductRepository products;
	private final OrderRepository orders;
	private final RestClient selfClient;
	private final Random rnd = new Random();
	private final AtomicInteger checkoutCounter = new AtomicInteger();

	public CheckoutController(ProductRepository products, OrderRepository orders, RestClient selfClient) {
		this.products = products;
		this.orders = orders;
		this.selfClient = selfClient;
	}

	public record CartLine(String sku, int quantity) {
	}

	public record CheckoutRequest(String email, String zip, List<CartLine> items) {
	}

	@PostMapping("/checkout")
	@Transactional
	public Map<String, Object> checkout(@RequestBody CheckoutRequest request) {
		int seq = checkoutCounter.incrementAndGet();
		log.info("Checkout #{} started for {} with {} line(s)", seq, request.email(), request.items().size());

		ShopOrder order = new ShopOrder(request.email(), "PENDING", Instant.now());
		int totalCents = 0;
		for (CartLine line : request.items()) {
			Product product = products.findBySku(line.sku())
					.orElseThrow(() -> new IllegalArgumentException("Unknown SKU: " + line.sku()));
			order.addItem(product, line.quantity());
			totalCents += product.getPriceCents() * line.quantity();
		}
		orders.save(order);

		// Outgoing HTTP call inside the same trace: http.client span here, and a
		// second http.server transaction for /api/shipping/quote in the waterfall.
		Map<?, ?> quote = selfClient.get()
				.uri(uri -> uri.path("/api/shipping/quote").queryParam("zip", request.zip()).build())
				.retrieve()
				.body(Map.class);

		log.info("Checkout #{} completed: order {} total {} cents", seq, order.getId(), totalCents);
		return Map.of(
				"orderId", order.getId(),
				"totalCents", totalCents,
				"shipping", quote);
	}

	@GetMapping("/shipping/quote")
	public Map<String, Object> shippingQuote(@RequestParam(defaultValue = "94103") String zip) throws InterruptedException {
		long latency = 100 + rnd.nextInt(300);
		Thread.sleep(latency);
		int cents = 495 + rnd.nextInt(1500);
		log.info("Shipping quote for zip {} computed in {} ms: {} cents", zip, latency, cents);
		return Map.of("zip", zip, "carrier", "DemoPost", "cents", cents);
	}
}
