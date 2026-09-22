package com.ordermanagement.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String sku;

    // Available stock. Never allowed to go negative — enforced in InventoryService
    // under a pessimistic row lock.
    @Column(nullable = false)
    private int quantity;

    // Optimistic version, kept as a defense-in-depth check alongside the pessimistic lock.
    @Version
    private long version;

    public Product(String name, String sku, int quantity) {
        this.name = name;
        this.sku = sku;
        this.quantity = quantity;
    }
}
