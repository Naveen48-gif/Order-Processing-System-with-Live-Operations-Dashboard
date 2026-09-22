package com.ordermanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Check;

import java.time.Instant;

/**
 * Stock level for exactly one product (enforced by a unique constraint on {@code product_id}).
 *
 * <p>Two independent safety nets guard the stock number:
 * <ol>
 *   <li>The {@code quantity >= 0} CHECK constraint below - the database refuses to store a negative
 *       stock level even if a future code path forgets to validate.</li>
 *   <li>{@link #decrement(int)} - the in-memory guard that makes the illegal state unreachable from
 *       Java as well.</li>
 * </ol>
 *
 * <p>{@code @Version} adds an optimistic conflict detector on top of the pessimistic row lock used by
 * the reservation transaction: if two transactions somehow interleave, the second commit fails loudly
 * instead of silently overwriting the first.
 */
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "inventory",
        uniqueConstraints = @UniqueConstraint(name = "uk_inventory_product", columnNames = "product_id")
)
@Check(constraints = "quantity >= 0")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, foreignKey = @ForeignKey(name = "fk_inventory_product"))
    private Product product;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Inventory(Product product, int quantity) {
        if (product == null) {
            throw new IllegalArgumentException("inventory requires a product");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("initial inventory quantity must not be negative");
        }
        this.product = product;
        this.quantity = quantity;
        this.updatedAt = Instant.now();
    }

    /** True when the requested amount can be reserved right now. */
    public boolean hasStockFor(int requestedQuantity) {
        return requestedQuantity > 0 && this.quantity >= requestedQuantity;
    }

    /**
     * Reserves stock inside the caller's transaction.
     *
     * <p>Callers must have taken the row lock first (see
     * {@code InventoryRepository#findForUpdateByProductId}) - that lock is what makes the
     * "read, decide, write" sequence in this method safe against concurrent workers.
     *
     * @throws IllegalStateException if the reservation would push stock below zero. Reaching this
     *         branch means a lock was not taken correctly, so it is deliberately fatal.
     */
    public void decrement(int requestedQuantity) {
        if (!hasStockFor(requestedQuantity)) {
            throw new IllegalStateException(
                    "refusing to decrement inventory %d by %d: the row lock contract was violated"
                            .formatted(this.id, requestedQuantity));
        }
        this.quantity -= requestedQuantity;
        this.updatedAt = Instant.now();
    }

    /** Administrative adjustment used by seeding and restocking (never by the order path). */
    public void restock(int quantityToAdd) {
        if (quantityToAdd < 0) {
            throw new IllegalArgumentException("restock quantity must not be negative");
        }
        this.quantity += quantityToAdd;
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void applyUpdateTimestamp() {
        this.updatedAt = Instant.now();
    }
}
