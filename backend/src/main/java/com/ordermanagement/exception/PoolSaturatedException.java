package com.ordermanagement.exception;

/**
 * Raised when the order intake path cannot hand work to the bounded worker pool because the pool and
 * its queue are both saturated.
 *
 * <p>Rejecting is the point: an unbounded queue would hide the overload behind growing latency and
 * memory, whereas a fast, explicit rejection lets the caller back off and keeps the process healthy.
 */
public class PoolSaturatedException extends RuntimeException {

    public PoolSaturatedException(String message, Throwable cause) {
        super(message, cause);
    }
}
