package com.ordermanagement.dto;

import com.ordermanagement.model.Product;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductResponse {
    private Long id;
    private String name;
    private String sku;
    private int quantity;

    public static ProductResponse from(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .sku(product.getSku())
                .quantity(product.getQuantity())
                .build();
    }
}
