/**
 * Domain and infrastructure exceptions plus the global {@code @RestControllerAdvice} handler.
 *
 * <p>Two error families are kept strictly apart, because only one of them is retryable:
 * <ul>
 *   <li>Business failures - e.g. {@code OutOfStockException}, {@code InvalidOrderStateTransitionException}.
 *       Terminal. They are never retried and never reach the DLQ.</li>
 *   <li>Technical failures - e.g. {@code OrderProcessingException} caused by transient I/O. Retried a
 *       bounded number of times, then routed to the dead-letter queue.</li>
 * </ul>
 * Every error leaves the API in one consistent JSON shape (timestamp, status, error, message, path).
 */
package com.ordermanagement.exception;
