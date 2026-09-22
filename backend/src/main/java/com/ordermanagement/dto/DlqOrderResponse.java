package com.ordermanagement.dto;

import java.time.Instant;

/** Dead-letter queue row for the API and the live `/topic/dlq` feed (frozen in AGENT.md §4/§5). */
public record DlqOrderResponse(
        Long orderId,
        Long productId,
        int quantity,
        int retryCount,
        String failureReason,
        Instant deadLetteredAt
) {

    public static DlqOrderResponse from(OrderResponse order) {
        return new DlqOrderResponse(
                order.id(), order.productId(), order.quantity(),
                order.retryCount(), order.failureReason(), order.updatedAt());
    }
}
