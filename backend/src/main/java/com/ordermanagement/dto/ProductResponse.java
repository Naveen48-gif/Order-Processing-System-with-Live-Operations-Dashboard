package com.ordermanagement.dto;

import com.ordermanagement.entity.Product;

import java.math.BigDecimal;
import java.time.Instant;

/** Catalogue entry returned by the product API. */
public record ProductResponse(
        Long id,
        String name,
        BigDecimal price,
        Instant createdAt
) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getPrice(), product.getCreatedAt());
    }
}
