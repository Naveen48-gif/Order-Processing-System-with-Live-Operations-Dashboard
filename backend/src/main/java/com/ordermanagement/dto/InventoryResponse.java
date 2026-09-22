package com.ordermanagement.dto;

import com.ordermanagement.entity.Inventory;

/** Inventory view for the dashboard: what a product has on hand right now. */
public record InventoryResponse(
        Long productId,
        String productName,
        int quantity,
        StockStatus stockStatus
) {

    public static InventoryResponse from(Inventory inventory) {
        return new InventoryResponse(
                inventory.getProduct().getId(),
                inventory.getProduct().getName(),
                inventory.getQuantity(),
                StockStatus.from(inventory.getQuantity()));
    }

    /** Builds the same view from a raw quantity, for callers that already know the product. */
    public static InventoryResponse of(Long productId, String productName, int quantity) {
        return new InventoryResponse(productId, productName, quantity, StockStatus.from(quantity));
    }
}
