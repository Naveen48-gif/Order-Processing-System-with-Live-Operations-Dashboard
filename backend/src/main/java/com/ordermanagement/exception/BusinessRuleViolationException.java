package com.ordermanagement.exception;

/**
 * Base class for <strong>business</strong> rule violations.
 *
 * <p>Classification matters more than the exception itself: the processing pipeline retries
 * {@link TransientProcessingException} only. Anything deriving from this class is terminal - the
 * order is rejected, the reason is recorded, and the retry budget is deliberately left untouched so
 * the dead-letter queue never fills with work a human cannot act on.
 */
public abstract class BusinessRuleViolationException extends RuntimeException {

    protected BusinessRuleViolationException(String message) {
        super(message);
    }

    protected BusinessRuleViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
