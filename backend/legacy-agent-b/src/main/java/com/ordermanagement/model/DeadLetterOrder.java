package com.ordermanagement.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

// Dead-letter queue entry: persisted so ops can see and audit failed orders on the dashboard.
@Entity
@Table(name = "dead_letter_orders")
@Getter
@Setter
@NoArgsConstructor
public class DeadLetterOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private Instant failedAt;

    public DeadLetterOrder(Long orderId, Long productId, int quantity, String reason, int retryCount) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.reason = reason;
        this.retryCount = retryCount;
        this.failedAt = Instant.now();
    }
}
