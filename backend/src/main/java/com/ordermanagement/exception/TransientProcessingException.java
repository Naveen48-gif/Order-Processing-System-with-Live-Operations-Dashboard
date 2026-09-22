package com.ordermanagement.exception;

/**
 * Base class for <strong>transient technical</strong> failures: a database hiccup, a lock that could
 * not be acquired within the timeout, a broker connection that dropped, a serialisation conflict.
 *
 * <p>These are worth retrying because the same call has a good chance of succeeding a moment later.
 * They consume one unit of the order's bounded retry budget, and once that budget is exhausted the
 * order is parked in the dead-letter queue.
 *
 * <p>The opposite of this is {@link BusinessRuleViolationException} - terminal, never retried.
 */
public abstract class TransientProcessingException extends RuntimeException {

    protected TransientProcessingException(String message) {
        super(message);
    }

    protected TransientProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
