package com.ordermanagement.exception;

/**
 * A <strong>transient, technical</strong> failure while processing an order: lock acquisition timed
 * out, the database connection dropped mid-transaction, a deadlock victim was chosen, the broker
 * connection was lost.
 *
 * <p>Deriving from {@link TransientProcessingException} is what makes the pipeline retry it a bounded
 * number of times and, if the budget runs out, move the order to the dead-letter queue.
 */
public class OrderProcessingException extends TransientProcessingException {

    private final Long orderId;

    public OrderProcessingException(Long orderId, String message, Throwable cause) {
        super("Order %s could not be processed: %s".formatted(orderId, message), cause);
        this.orderId = orderId;
    }

    public Long getOrderId() {
        return orderId;
    }
}
