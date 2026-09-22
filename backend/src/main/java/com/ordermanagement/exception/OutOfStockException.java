package com.ordermanagement.exception;

/**
 * Not enough stock to satisfy the request.
 *
 * <p>This is deliberately a {@link BusinessRuleViolationException}: the pipeline marks the order
 * {@code OUT_OF_STOCK} and stops. Retrying would not create inventory, and treating it as a technical
 * failure would push non-actionable work into the dead-letter queue.
 */
public class OutOfStockException extends BusinessRuleViolationException {

    private final Long productId;
    private final int requestedQuantity;
    private final int availableQuantity;

    public OutOfStockException(Long productId, int requestedQuantity, int availableQuantity) {
        super("Insufficient inventory for product %s: requested %d, available %d"
                .formatted(productId, requestedQuantity, availableQuantity));
        this.productId = productId;
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
    }

    public Long getProductId() {
        return productId;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }
}
