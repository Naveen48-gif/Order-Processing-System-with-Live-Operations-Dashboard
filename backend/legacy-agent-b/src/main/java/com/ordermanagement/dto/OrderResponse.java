package com.ordermanagement.dto;

import com.ordermanagement.model.Order;
import com.ordermanagement.model.OrderStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class OrderResponse {
    private Long id;
    private Long productId;
    private String productName;
    private int quantity;
    private OrderStatus status;
    private String failureReason;
    private int retryCount;
    private Instant createdAt;
    private Instant updatedAt;

    public static OrderResponse from(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .productId(order.getProductId())
                .productName(order.getProductName())
                .quantity(order.getQuantity())
                .status(order.getStatus())
                .failureReason(order.getFailureReason())
                .retryCount(order.getRetryCount())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}
