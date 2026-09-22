package com.ordermanagement.messaging;

/**
 * The work item that travels over the broker: which order to process, and which attempt this is.
 *
 * <p>Only the order identifier is sent, never the order itself. The database is the single source of
 * truth, so a message is a <em>pointer</em> to work rather than a snapshot of state: a redelivery of the
 * same message can never resurrect a stale quantity or re-apply an old status, and the payload stays
 * small enough to sit in a queue for a long time without becoming a consistency hazard.
 *
 * <p>{@code attempt} is carried for observability (the management UI shows which retry tier a message came
 * from) and is deliberately <em>not</em> trusted as the retry budget: the authoritative count lives on the
 * order row, so a lost or duplicated message cannot extend an order's retries.
 */
public record OrderWorkMessage(Long orderId, int attempt) {

    public static OrderWorkMessage forFirstAttempt(Long orderId) {
        return new OrderWorkMessage(orderId, 0);
    }

    public static OrderWorkMessage forAttempt(Long orderId, int attempt) {
        return new OrderWorkMessage(orderId, attempt);
    }
}