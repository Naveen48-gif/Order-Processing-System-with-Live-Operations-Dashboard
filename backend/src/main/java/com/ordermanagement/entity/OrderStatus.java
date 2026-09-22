package com.ordermanagement.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle state of a customer order.
 *
 * <pre>
 * PENDING ──► PROCESSING ──► COMPLETED                (terminal)
 *                          ├─► OUT_OF_STOCK           (terminal business rejection)
 *                          └─► FAILED ──► PROCESSING  (bounded technical retry)
 *                                     └─► DLQ ──► PROCESSING  (manual admin retry)
 * </pre>
 *
 * <p>The transition table is part of the domain model rather than the service layer, so an illegal
 * transition is impossible to introduce by accident: the only way to change status is through
 * {@link Order}'s guarded mutators.
 */
public enum OrderStatus {

    /** Accepted and persisted, waiting for a worker. */
    PENDING,

    /** A worker holds this order and is inside the inventory reservation transaction. */
    PROCESSING,

    /** Inventory was reserved and decremented. Terminal. */
    COMPLETED,

    /**
     * Insufficient stock. This is a <em>business</em> rejection: it is terminal and must never be
     * retried, because retrying cannot create stock and would only burn worker capacity.
     */
    OUT_OF_STOCK,

    /** A transient technical failure occurred; the order may be retried while the budget lasts. */
    FAILED,

    /** Retry budget exhausted. Parked in the dead-letter queue for human inspection/replay. */
    DLQ;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            PENDING, EnumSet.of(PROCESSING),
            PROCESSING, EnumSet.of(COMPLETED, OUT_OF_STOCK, FAILED),
            FAILED, EnumSet.of(PROCESSING, DLQ),
            DLQ, EnumSet.of(PROCESSING),
            COMPLETED, EnumSet.noneOf(OrderStatus.class),
            OUT_OF_STOCK, EnumSet.noneOf(OrderStatus.class)
    );

    /** True for states that will never change again without human intervention. */
    public boolean isTerminal() {
        return this == COMPLETED || this == OUT_OF_STOCK;
    }

    /** True when the order is sitting in the dead-letter queue. */
    public boolean isDeadLettered() {
        return this == DLQ;
    }

    /** True when a worker may still pick this order up. */
    public boolean isActionable() {
        return this == PENDING || this == FAILED;
    }

    public boolean canTransitionTo(OrderStatus target) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
