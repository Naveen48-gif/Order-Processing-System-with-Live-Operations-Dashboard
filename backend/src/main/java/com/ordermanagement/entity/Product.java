package com.ordermanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A sellable item. Stock is not stored here: it lives in {@link Inventory} so that the hot,
 * contended column sits in its own narrow row with its own lock, keeping lock footprint and
 * row size small under heavy concurrent ordering.
 */
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "product",
        uniqueConstraints = @UniqueConstraint(name = "uk_product_name", columnNames = "name")
)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    /** Never use double for money: exact decimal arithmetic on the database side too. */
    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Product(String name, BigDecimal price) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("product name must not be blank");
        }
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("product price must not be negative");
        }
        this.name = name;
        this.price = price;
    }

    @PrePersist
    void applyCreationTimestamp() {
        Instant now = Instant.now();
        this.createdAt = now;
    }
}
