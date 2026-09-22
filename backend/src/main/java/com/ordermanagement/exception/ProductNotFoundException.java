package com.ordermanagement.exception;

/**
 * The referenced product does not exist. Terminal: creating stock out of nowhere is not something a
 * retry can fix, so this never consumes retry budget.
 */
public class ProductNotFoundException extends BusinessRuleViolationException {

    private final Long productId;

    public ProductNotFoundException(Long productId) {
        super("Product %s does not exist".formatted(productId));
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
