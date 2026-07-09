package dev.outpost.demo.api;

import dev.outpost.demo.catalog.OrderRepository;
import dev.outpost.demo.catalog.ProductRepository;
import io.sentry.ISpan;
import io.sentry.Sentry;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CatalogController {

	private static final Logger log = LoggerFactory.getLogger(CatalogController.class);

	private final ProductRepository products;
	private final OrderRepository orders;

	public CatalogController(ProductRepository products, OrderRepository orders) {
		this.products = products;
		this.orders = orders;
	}

	@GetMapping("/products")
	public List<Map<String, Object>> products() {
		log.debug("Listing products");
		return products.findAll().stream()
				.<Map<String, Object>>map(p -> Map.of(
						"id", p.getId(),
						"sku", p.getSku(),
						"name", p.getName(),
						"priceCents", p.getPriceCents(),
						"stock", p.getStock()))
				.toList();
	}

	@GetMapping("/products/slow")
	public List<Map<String, Object>> slowProducts(@RequestParam(defaultValue = "1500") long ms) throws InterruptedException {
		long capped = Math.min(ms, 10_000);
		ISpan parent = Sentry.getSpan();
		ISpan rebuild = parent != null ? parent.startChild("cache.rebuild", "rebuild product cache") : null;
		try {
			log.info("Rebuilding product cache ({} ms simulated)", capped);
			Thread.sleep(capped);
		}
		finally {
			if (rebuild != null) {
				rebuild.finish();
			}
		}
		return products();
	}

	@GetMapping("/orders/nplusone")
	public List<Map<String, Object>> nPlusOne() {
		log.warn("Running the N+1 order report (intentionally unoptimized)");
		// findAll then touching each order's lazy items issues one SELECT per order.
		return orders.findAll().stream()
				.<Map<String, Object>>map(o -> Map.of(
						"id", o.getId(),
						"customer", o.getCustomerEmail(),
						"status", o.getStatus(),
						"lines", o.getItems().size(),
						"totalCents", o.getItems().stream()
								.mapToInt(i -> i.getProduct().getPriceCents() * i.getQuantity()).sum()))
				.toList();
	}
}
