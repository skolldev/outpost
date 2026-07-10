package dev.outpost.demo.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<ShopOrder, Long> {
}
