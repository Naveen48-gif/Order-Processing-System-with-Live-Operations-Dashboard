package com.ordermanagement.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Order submission payload.
 *
 * <p>{@code idempotencyKey} is optional but strongly recommended for machine clients: a retried HTTP
 * request carrying the same key returns the original order instead of reserving stock twice.
 */
public record SubmitOrderRequest(

        @NotNull(message = "productId is required")
        Long productId,

        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be greater than zero")
        Integer quantity,

        @Size(max = 120, message = "idempotencyKey must be at most 120 characters")
        String idempotencyKey
) {
}
