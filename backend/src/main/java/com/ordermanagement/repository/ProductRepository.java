package com.ordermanagement.repository;

import com.ordermanagement.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    /** Catalogue listing for the dashboard and the product API. */
    List<Product> findAllByOrderByNameAsc();
}
