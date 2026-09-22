package com.ordermanagement.entity;

import com.ordermanagement.exception.InvalidOrderStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

import java.time.Instant;

/**
 * A single customer order for one product. Status changes flow exclusively through the guarded
 * mutators below, so the lifecycle documented in {@link OrderStatus} cannot be bypassed.
 *
 * <p>Idempotency: {@code idempotencyKey} is optional and unique when supplied, so a retried HTTP
 * client (or a redelivered message) can re-submit the same logical order without reserving stock
 * twice. See the order service for the replay strategy.
 *
 * <p>The identifier is a database sequence value rather than a random UUID: monotonic ids keep the
 * index compact and inserts local, which matters for the order table under concurrent load.
 */
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "orders",
        uniqueConstraints = @UniqueConstraint(name = "uk_orders_idempotency_key", columnNames = "idempotency_key"),
        indexes = {
                @Index(name = "idx_orders_status", columnList = "status"),
                @Index(name = "idx_orders_product", columnList = "product_id"),
                @Index(name = "idx_orders_created_at", columnList = "created_at")
        }
)
@Check(constraints = "quantity > 0 and retry_count >= 0")
public class Order {

    private static final int FAILURE_REASON_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, foreignKey = @ForeignKey(name = "fk_orders_product"))
    private Product product;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "failure_reason", length = FAILURE_REASON_MAX_LENGTH)
    private String failureReason;

    @Column(name = "idempotency_key", length = 120)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Order(Product product, int quantity, String idempotencyKey) {
        if (product == null) {
            throw new IllegalArgumentException("order requires a product");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("order quantity must be greater than zero");
        }
        this.product = product;
        this.quantity = quantity;
        this.idempotencyKey = idempotencyKey;
        this.status = OrderStatus.PENDING;
        this.retryCount = 0;
    }

    /** A worker has picked the order up and is about to enter the reservation transaction. */
    public void markProcessing() {
        transitionTo(OrderStatus.PROCESSING);
    }

    /** Stock was reserved and decremented. Terminal success. */
    public void markCompleted() {
        transitionTo(OrderStatus.COMPLETED);
        this.failureReason = null;
    }

    /**
     * Terminal business rejection: not enough stock. Deliberately not retryable - retrying cannot
     * create inventory, and treating it as retryable would flood the dead-letter queue with
     * non-actionable work.
     */
    public void markOutOfStock(String reason) {
        transitionTo(OrderStatus.OUT_OF_STOCK);
        this.failureReason = truncate(reason);
    }

    /**
     * Records a transient technical failure and consumes one unit of retry budget.
     *
     * @return the updated retry count so the caller can compare it with the configured budget
     */
    public int registerTechnicalFailure(String reason) {
        transitionTo(OrderStatus.FAILED);
        this.retryCount++;
        this.failureReason = truncate(reason);
        return this.retryCount;
    }

    /** Retry budget exhausted: park the order for human inspection and manual replay. */
    public void moveToDeadLetter(String reason) {
        transitionTo(OrderStatus.DLQ);
        this.failureReason = truncate(reason);
    }

    /** Puts a replayed order (from FAILED or DLQ) back into the processing pipeline. */
    public void markRetrying() {
        transitionTo(OrderStatus.PROCESSING);
    }

    public boolean hasRetryBudgetLeft(int maxRetryAttempts) {
        return this.retryCount < maxRetryAttempts;
    }

    /** True while a worker is allowed to pick this order up. */
    public boolean isProcessable() {
        return this.status.isActionable();
    }

    /**
     * The single gate for status changes. Anything not listed in
     * {@link OrderStatus#canTransitionTo(OrderStatus)} is rejected loudly, which is what stops an
     * at-least-once delivery from silently re-running a business side effect such as decrementing
     * inventory for an order that is already {@code COMPLETED}.
     */
    private void transitionTo(OrderStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new InvalidOrderStateTransitionException(this.id, this.status, target);
        }
        this.status = target;
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= FAILURE_REASON_MAX_LENGTH
                ? reason
                : reason.substring(0, FAILURE_REASON_MAX_LENGTH);
    }

    @PrePersist
    void applyCreationTimestamps() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = OrderStatus.PENDING;
        }
    }

    @PreUpdate
    void applyUpdateTimestamp() {
        this.updatedAt = Instant.now();
    }
}
