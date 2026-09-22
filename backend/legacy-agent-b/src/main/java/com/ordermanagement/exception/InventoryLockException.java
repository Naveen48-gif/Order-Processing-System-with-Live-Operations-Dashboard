package com.ordermanagement.exception;

// Raised when stock reservation fails after exhausting the bounded retry policy
// due to transient DB contention (lock timeouts / deadlocks), not business out-of-stock.
public class InventoryLockException extends RuntimeException {
    public InventoryLockException(String message, Throwable cause) {
        super(message, cause);
    }
}
