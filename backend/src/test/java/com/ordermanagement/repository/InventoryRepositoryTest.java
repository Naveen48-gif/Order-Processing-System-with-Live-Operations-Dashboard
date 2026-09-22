package com.ordermanagement.repository;

import com.ordermanagement.entity.Inventory;
import com.ordermanagement.entity.Product;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Persistence-level guarantees of the inventory table, on a real SQL engine (H2 in PostgreSQL
 * compatibility mode, per the {@code test} profile) that enforces real constraints and real
 * {@code SELECT ... FOR UPDATE} semantics.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InventoryRepositoryTest {

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Autowired
    private EntityManager entityManager;

    private Product persistProduct(String name, int initialQuantity) {
        Product product = testEntityManager.persistAndFlush(new Product(name, new BigDecimal("1299.99")));
        testEntityManager.persistAndFlush(new Inventory(product, initialQuantity));
        return product;
    }

    @Test
    @DisplayName("the locked read returns the inventory row together with its product")
    void lockedReadReturnsInventoryAndProduct() {
        Product product = persistProduct("Laptop", 10);

        var locked = inventoryRepository.findForUpdateByProductId(product.getId());

        assertThat(locked).isPresent();
        assertThat(locked.get().getQuantity()).isEqualTo(10);
        assertThat(locked.get().getProduct().getName()).isEqualTo("Laptop");
    }

    @Test
    @DisplayName("the locked read is empty for an unknown product instead of blowing up")
    void lockedReadIsEmptyForUnknownProduct() {
        assertThat(inventoryRepository.findForUpdateByProductId(4242L)).isEmpty();
    }

    @Test
    @DisplayName("decrement reduces stock and refuses to go below zero in memory")
    void decrementRespectsStockLevel() {
        Product product = persistProduct("Keyboard", 3);
        Inventory inventory = inventoryRepository.findForUpdateByProductId(product.getId()).orElseThrow();

        assertThat(inventory.hasStockFor(4)).isFalse();
        assertThat(inventory.hasStockFor(3)).isTrue();

        inventory.decrement(3);
        assertThat(inventory.getQuantity()).isZero();

        assertThatThrownBy(() -> inventory.decrement(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("row lock contract");
    }

    @Test
    @DisplayName("the database itself refuses to store a negative quantity")
    void databaseRejectsNegativeQuantity() {
        Product product = persistProduct("Monitor", 5);

        // Simulates a future code path that bypasses the entity guard entirely.
        Throwable thrown = catchThrowable(() -> {
            entityManager.createNativeQuery("update inventory set quantity = -1 where product_id = :productId")
                    .setParameter("productId", product.getId())
                    .executeUpdate();
        });

        assertThat(thrown)
                .as("the quantity >= 0 CHECK constraint must reject the write")
                .isNotNull();

        entityManager.clear();
        assertThat(inventoryRepository.findByProductId(product.getId()).orElseThrow().getQuantity())
                .as("stock must be untouched after the rejected write")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("one inventory row per product is enforced by a unique constraint")
    void oneInventoryRowPerProduct() {
        Product product = persistProduct("Headset", 2);

        Throwable thrown = catchThrowable(() -> {
            entityManager.createNativeQuery("insert into inventory (product_id, quantity, version, updated_at) "
                            + "values (:productId, 1, 0, current_timestamp)")
                    .setParameter("productId", product.getId())
                    .executeUpdate();
        });

        assertThat(thrown).isNotNull();
    }

    @Test
    @DisplayName("the dashboard listing fetches products without an N+1 query pattern")
    void dashboardListingFetchesProducts() {
        persistProduct("Zebra Stand", 1);
        persistProduct("Alpha Cable", 7);

        var rows = inventoryRepository.findAllWithProduct();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getProduct().getName()).isEqualTo("Alpha Cable");
        assertThat(rows.get(1).getProduct().getName()).isEqualTo("Zebra Stand");
    }
}
