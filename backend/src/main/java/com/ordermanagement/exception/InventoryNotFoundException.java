package com.ordermanagement.exception;

/**
 * A product exists but has no inventory row. That is a data-integrity problem rather than a transient
 * one, so the order is rejected and the operator is expected to fix the catalogue entry.
 */
public class InventoryNotFoundException extends BusinessRuleViolationException {

    private final Long productId;

    public InventoryNotFoundException(Long productId) {
        super("No inventory record exists for product %s".formatted(productId));
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
