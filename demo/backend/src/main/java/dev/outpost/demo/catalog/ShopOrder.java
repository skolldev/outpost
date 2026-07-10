package dev.outpost.demo.catalog;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
public class ShopOrder {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String customerEmail;
	private String status;
	private Instant createdAt;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
	private List<OrderItem> items = new ArrayList<>();

	protected ShopOrder() {
	}

	public ShopOrder(String customerEmail, String status, Instant createdAt) {
		this.customerEmail = customerEmail;
		this.status = status;
		this.createdAt = createdAt;
	}

	public void addItem(Product product, int quantity) {
		items.add(new OrderItem(this, product, quantity));
	}

	public Long getId() {
		return id;
	}

	public String getCustomerEmail() {
		return customerEmail;
	}

	public String getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public List<OrderItem> getItems() {
		return items;
	}
}
