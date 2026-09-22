package com.ordermanagement.config;

import com.ordermanagement.entity.Inventory;
import com.ordermanagement.entity.Product;
import com.ordermanagement.repository.InventoryRepository;
import com.ordermanagement.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds a small demo catalogue so the operations dashboard has something to show on a fresh database.
 *
 * <p>Design notes:
 * <ul>
 *   <li><strong>Idempotent.</strong> Existing products are never re-created and stock is never reset,
 *       so restarting the application cannot resurrect inventory that orders already consumed.</li>
 *   <li><strong>Opt-out.</strong> Disabled with {@code order.seed.enabled=false} (the {@code test}
 *       profile does this so the suite owns its own fixtures).</li>
 *   <li><strong>Portable.</strong> Uses repositories rather than vendor SQL, so it behaves the same on
 *       PostgreSQL, MySQL and H2.</li>
 * </ul>
 *
 * <p>The three stock levels are deliberate: available, low and sold out, which is exactly what the
 * dashboard's stock badges need to be verified by eye.
 */
@Component
@ConditionalOnProperty(name = "order.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    public DemoDataSeeder(ProductRepository productRepository, InventoryRepository inventoryRepository) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("Laptop", new BigDecimal("1299.99"), 10);
        seed("Keyboard", new BigDecimal("49.50"), 2);
        seed("Monitor", new BigDecimal("219.00"), 0);
    }

    private void seed(String name, BigDecimal price, int quantity) {
        Product product = productRepository.findByNameIgnoreCase(name).orElseGet(() -> {
            Product created = productRepository.save(new Product(name, price));
            log.info("SEED_PRODUCT_CREATED productId={} name={}", created.getId(), name);
            return created;
        });

        if (inventoryRepository.existsByProductId(product.getId())) {
            return;
        }
        inventoryRepository.save(new Inventory(product, quantity));
        log.info("SEED_INVENTORY_CREATED productId={} name={} quantity={}", product.getId(), name, quantity);
    }

    /** Convenience for tests and tooling that want the demo catalogue names. */
    public static List<String> demoProductNames() {
        return List.of("Laptop", "Keyboard", "Monitor");
    }
}
