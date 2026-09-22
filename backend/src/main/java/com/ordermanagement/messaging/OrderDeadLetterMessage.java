package com.ordermanagement.messaging;

import java.time.Instant;

/**
 * The envelope parked on the broker's dead-letter queue.
 *
 * <p>Why a second, richer payload next to {@link OrderWorkMessage}: a message on the DLQ is read by a
 * human in the RabbitMQ console months later, after the application logs have rotated away. It therefore
 * carries the reason and the consumed retry budget itself, and the timestamp is an ISO-8601 string rather
 * than a Java {@code Instant} so the payload stays readable by non-Java tooling.
 *
 * <p>This is an operational record, not the work ledger: the order row in the database remains
 * authoritative, and replay is driven from there ( {@code POST /api/dlq/{orderId}/retry} ) so it works even
 * if the broker's DLQ was purged.
 */
public record OrderDeadLetterMessage(
        Long orderId,
        Long productId,
        int quantity,
        int retryCount,
        String failureReason,
        String deadLetteredAt
) {

    public static OrderDeadLetterMessage of(Long orderId, Long productId, int quantity, int retryCount,
                                            String failureReason) {
        return new OrderDeadLetterMessage(orderId, productId, quantity, retryCount, failureReason,
                Instant.now().toString());
    }
}