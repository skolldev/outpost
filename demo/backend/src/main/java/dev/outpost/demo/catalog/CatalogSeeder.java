package dev.outpost.demo.catalog;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CatalogSeeder implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(CatalogSeeder.class);

	private final ProductRepository products;
	private final OrderRepository orders;

	public CatalogSeeder(ProductRepository products, OrderRepository orders) {
		this.products = products;
		this.orders = orders;
	}

	@Override
	@Transactional
	public void run(String... args) {
		if (products.count() > 0) {
			return;
		}
		List<Product> catalog = products.saveAll(List.of(
				new Product("SKU-1001", "Mechanical Keyboard", 12900, 42),
				new Product("SKU-1002", "Vertical Mouse", 5900, 120),
				new Product("SKU-1003", "4K Monitor 27\"", 38900, 15),
				new Product("SKU-1004", "USB-C Dock", 14900, 63),
				new Product("SKU-1005", "Noise-Cancelling Headphones", 24900, 31),
				new Product("SKU-1006", "Laptop Stand", 4500, 200),
				new Product("SKU-1007", "Webcam 1080p", 8900, 77),
				new Product("SKU-1008", "Desk Mat XXL", 2900, 340),
				new Product("SKU-1009", "Thunderbolt Cable 2m", 3900, 155),
				new Product("SKU-1010", "Ergonomic Chair", 44900, 8),
				new Product("SKU-1011", "Ring Light", 6900, 54),
				new Product("SKU-1012", "Portable SSD 2TB", 18900, 27)));

		Random rnd = new Random(42);
		String[] customers = { "ada@example.com", "grace@example.com", "linus@example.com", "margaret@example.com" };
		for (int i = 0; i < 18; i++) {
			ShopOrder order = new ShopOrder(
					customers[rnd.nextInt(customers.length)],
					rnd.nextInt(10) < 8 ? "SHIPPED" : "PENDING",
					Instant.now().minus(rnd.nextInt(30), ChronoUnit.DAYS));
			int lines = 1 + rnd.nextInt(3);
			for (int j = 0; j < lines; j++) {
				order.addItem(catalog.get(rnd.nextInt(catalog.size())), 1 + rnd.nextInt(4));
			}
			orders.save(order);
		}
		log.info("Seeded {} products and {} orders", catalog.size(), orders.count());
	}
}
