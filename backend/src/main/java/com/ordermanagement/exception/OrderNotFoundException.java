package com.ordermanagement.exception;

/** The referenced order does not exist. */
public class OrderNotFoundException extends BusinessRuleViolationException {

    private final Long orderId;

    public OrderNotFoundException(Long orderId) {
        super("Order %s does not exist".formatted(orderId));
        this.orderId = orderId;
    }

    public Long getOrderId() {
        return orderId;
    }
}
