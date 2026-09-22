package com.ordermanagement.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Payload of a failure drill: how many transient faults to inject for one product.
 *
 * <p>Validated at the edge like every other request body, and bounded at the top end so a typo cannot
 * disable a product for an unbounded number of orders.
 */
public record ArmFaultRequest(

        @NotNull(message = "productId is required")
        Long productId,

        @NotNull(message = "occurrences is required")
        @Min(value = 1, message = "occurrences must be at least 1")
        @Max(value = 50, message = "occurrences must be at most 50")
        Integer occurrences
) {
}