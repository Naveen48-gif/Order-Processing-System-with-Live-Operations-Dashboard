package com.ordermanagement.messaging;

/**
 * Hands a persisted order to the asynchronous processing pipeline.
 *
 * <p>Two transports implement this contract and both drive the <em>same</em> processor bean, so a
 * behavioural difference between them is impossible:
 * <ul>
 *   <li>{@code rabbitmq} (canonical): durable queue with a dead-letter exchange/queue.</li>
 *   <li>{@code in-process}: the bounded local worker pool, for machines where no broker binary can run.</li>
 * </ul>
 *
 * <p>Implementations must be called <strong>after</strong> the creating transaction commits. Handing
 * work over inside the transaction would let a worker read an order row that does not exist yet (or is
 * about to be rolled back), which is a classic async race.
 */
public interface OrderTransport {

    /**
     * Submits an order for processing.
     *
     * @throws com.ordermanagement.exception.PoolSaturatedException when the pipeline is saturated and
     *         the work was rejected instead of queued
     */
    void submit(Long orderId);

    /** Re-submits an order after a transient failure, once the backoff for the given attempt has elapsed. */
    void submitForRetry(Long orderId, long delayMillis);
}
