package dev.outpost.demo.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

@Entity
public class OrderItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	private ShopOrder order;

	@ManyToOne(fetch = FetchType.LAZY)
	private Product product;

	private int quantity;

	protected OrderItem() {
	}

	public OrderItem(ShopOrder order, Product product, int quantity) {
		this.order = order;
		this.product = product;
		this.quantity = quantity;
	}

	public Long getId() {
		return id;
	}

	public Product getProduct() {
		return product;
	}

	public int getQuantity() {
		return quantity;
	}
}
