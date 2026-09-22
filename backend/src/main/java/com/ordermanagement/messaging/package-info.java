/**
 * Broker integration: topology declarations (exchange, queue, dead-letter exchange/queue), the
 * message payload, the publisher and the listener that drives the worker pipeline.
 *
 * <p>Topology rule: the work queue is bound to the dead-letter exchange, and the dead-letter queue is
 * bound to that exchange. A rejected or expired message therefore lands in the DLQ instead of being
 * lost, which is what makes the bounded-retry story observable and recoverable.
 *
 * <p>All names come from {@link com.ordermanagement.config.MessagingProperties} - no literals here.
 */
package com.ordermanagement.messaging;
