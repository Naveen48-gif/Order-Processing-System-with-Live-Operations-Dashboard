package com.ordermanagement.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Product creation payload.
 *
 * <p>Bean Validation runs before the controller body executes, so an invalid payload never reaches
 * the service layer. {@code initialQuantity} is optional: when omitted the product starts at zero
 * stock, which the dashboard immediately shows as {@code OUT_OF_STOCK}.
 */
public record CreateProductRequest(

        @NotBlank(message = "name is required")
        @Size(max = 160, message = "name must be at most 160 characters")
        String name,

        @NotNull(message = "price is required")
        @DecimalMin(value = "0.00", message = "price must not be negative")
        @Digits(integer = 10, fraction = 2, message = "price must have at most 2 decimal places")
        BigDecimal price,

        @Min(value = 0, message = "initialQuantity must not be negative")
        Integer initialQuantity
) {

    public int initialQuantityOrDefault() {
        return initialQuantity == null ? 0 : initialQuantity;
    }
}
