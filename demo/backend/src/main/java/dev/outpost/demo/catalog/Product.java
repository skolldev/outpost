package dev.outpost.demo.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String sku;
	private String name;
	private int priceCents;
	private int stock;

	protected Product() {
	}

	public Product(String sku, String name, int priceCents, int stock) {
		this.sku = sku;
		this.name = name;
		this.priceCents = priceCents;
		this.stock = stock;
	}

	public Long getId() {
		return id;
	}

	public String getSku() {
		return sku;
	}

	public String getName() {
		return name;
	}

	public int getPriceCents() {
		return priceCents;
	}

	public int getStock() {
		return stock;
	}
}
