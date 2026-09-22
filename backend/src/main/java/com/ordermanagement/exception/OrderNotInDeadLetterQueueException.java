package com.ordermanagement.exception;

import com.ordermanagement.entity.OrderStatus;

/** Thrown when a replay is requested for an order that is not currently sitting in the DLQ. */
public class OrderNotInDeadLetterQueueException extends BusinessRuleViolationException {

    public OrderNotInDeadLetterQueueException(Long orderId, OrderStatus currentStatus) {
        super("Order %d is not in the dead-letter queue (current status: %s)".formatted(orderId, currentStatus));
    }
}
