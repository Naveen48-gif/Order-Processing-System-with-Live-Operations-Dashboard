package com.ordermanagement.service;

import com.ordermanagement.dto.OrderResponse;

/**
 * Outcome of an order submission.
 *
 * <p>The {@code replayed} flag lets the API answer {@code 201 Created} for a genuinely new order and
 * {@code 200 OK} when the same idempotency key was seen before, which tells a client whether its retry
 * created anything.
 */
public record OrderSubmissionResult(OrderResponse order, boolean replayed) {

    public static OrderSubmissionResult created(OrderResponse order) {
        return new OrderSubmissionResult(order, false);
    }

    public static OrderSubmissionResult replayed(OrderResponse order) {
        return new OrderSubmissionResult(order, true);
    }
}
