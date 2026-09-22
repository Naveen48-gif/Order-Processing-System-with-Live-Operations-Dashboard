package com.ordermanagement.exception;

import com.ordermanagement.entity.OrderStatus;

/**
 * Thrown when code attempts a status change that the lifecycle does not allow (for example moving a
 * {@code COMPLETED} order back to {@code PROCESSING}, or re-processing an order that was already
 * rejected for stock reasons).
 *
 * <p>Extends {@link BusinessRuleViolationException}: this is a programming or replay error, so it is
 * never retried and never dead-lettered - it must be fixed, not retried.
 */
public class InvalidOrderStateTransitionException extends BusinessRuleViolationException {

    private final Long orderId;
    private final OrderStatus currentStatus;
    private final OrderStatus attemptedStatus;

    public InvalidOrderStateTransitionException(Long orderId, OrderStatus currentStatus, OrderStatus attemptedStatus) {
        super("Order %s cannot move from %s to %s".formatted(orderId, currentStatus, attemptedStatus));
        this.orderId = orderId;
        this.currentStatus = currentStatus;
        this.attemptedStatus = attemptedStatus;
    }

    public Long getOrderId() {
        return orderId;
    }

    public OrderStatus getCurrentStatus() {
        return currentStatus;
    }

    public OrderStatus getAttemptedStatus() {
        return attemptedStatus;
    }
}
