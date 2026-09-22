package com.ordermanagement.dto;

/**
 * Operational stock signal shown on the dashboard.
 *
 * <p>The threshold is not a business rule of the ordering flow (which only cares whether the exact
 * requested quantity can be reserved); it exists purely so an operator can spot a product that is
 * about to sell out.
 */
public enum StockStatus {

    AVAILABLE,
    LOW_STOCK,
    OUT_OF_STOCK;

    /** At or below this level a product is flagged as running low. */
    public static final int LOW_STOCK_THRESHOLD = 5;

    public static StockStatus from(int quantity) {
        if (quantity <= 0) {
            return OUT_OF_STOCK;
        }
        return quantity <= LOW_STOCK_THRESHOLD ? LOW_STOCK : AVAILABLE;
    }
}
