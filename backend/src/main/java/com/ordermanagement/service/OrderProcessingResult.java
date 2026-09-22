package com.ordermanagement.service;

import com.ordermanagement.entity.Order;
import com.ordermanagement.entity.OrderStatus;

/**
 * Result of one processing attempt, returned to the worker so it can decide whether to retry, stop, or
 * hand the order to a human. Keeping the decision out of the transactional services makes both sides
 * easy to reason about: the transactional side only reads and writes state, the worker only routes.
 */
public record OrderProcessingResult(
        Long orderId,
        Outcome outcome,
        String message,
        int retryCount,
        OrderStatus status
) {

    public enum Outcome {
        /** Stock was reserved and the order is COMPLETED. */
        COMPLETED,
        /** Not enough stock: terminal business rejection, intentionally not retryable. */
        OUT_OF_STOCK,
        /** A transient fault occurred; the order stays FAILED and may be retried while budget lasts. */
        RETRYABLE_FAILURE,
        /** Retry budget exhausted or human action required: the order is now in the DLQ. */
        DEAD_LETTERED,
        /** Nothing to do - the order was already terminal (idempotent replay). */
        SKIPPED
    }

    static OrderProcessingResult completed(Order order) {
        return new OrderProcessingResult(order.getId(), Outcome.COMPLETED, null,
                order.getRetryCount(), order.getStatus());
    }

    static OrderProcessingResult outOfStock(Order order, String message) {
        return new OrderProcessingResult(order.getId(), Outcome.OUT_OF_STOCK, message,
                order.getRetryCount(), order.getStatus());
    }

    static OrderProcessingResult retryableFailure(Order order, String message) {
        return new OrderProcessingResult(order.getId(), Outcome.RETRYABLE_FAILURE, message,
                order.getRetryCount(), order.getStatus());
    }

    static OrderProcessingResult deadLettered(Order order, String message) {
        return new OrderProcessingResult(order.getId(), Outcome.DEAD_LETTERED, message,
                order.getRetryCount(), order.getStatus());
    }

    static OrderProcessingResult skipped(Long orderId, OrderStatus status) {
        return new OrderProcessingResult(orderId, Outcome.SKIPPED,
                "order already in terminal state %s".formatted(status), 0, status);
    }

    public boolean isTerminal() {
        return outcome == Outcome.COMPLETED || outcome == Outcome.OUT_OF_STOCK || outcome == Outcome.SKIPPED;
    }
}
