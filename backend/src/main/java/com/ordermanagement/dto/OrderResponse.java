package com.ordermanagement.dto;

import com.ordermanagement.entity.Order;
import com.ordermanagement.entity.OrderStatus;

import java.time.Instant;

/**
 * Order view for the API and the live dashboard feed (frozen in AGENT.md §4).
 *
 * <p>The product name is denormalised into the response on purpose: the dashboard renders hundreds of
 * rows and must not trigger a lazy load per row, and an order should stay readable in the history even
 * if the catalogue entry is later renamed or removed.
 */
public record OrderResponse(
        Long id,
        Long productId,
        String productName,
        int quantity,
        OrderStatus status,
        int retryCount,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getProduct().getId(),
                order.getProduct().getName(),
                order.getQuantity(),
                order.getStatus(),
                order.getRetryCount(),
                order.getFailureReason(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
