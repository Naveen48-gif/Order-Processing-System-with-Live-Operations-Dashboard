package com.ordermanagement.service;

import com.ordermanagement.dto.SubmitOrderRequest;
import com.ordermanagement.entity.Inventory;
import com.ordermanagement.entity.OrderStatus;
import com.ordermanagement.entity.Product;
import com.ordermanagement.repository.InventoryRepository;
import com.ordermanagement.repository.OrderRepository;
import com.ordermanagement.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The test this whole system exists to satisfy: <strong>inventory is never oversold under
 * concurrency</strong>.
 *
 * <p>Scenario exactly as specified in the brief:
 * <pre>
 *   initial stock                = 10
 *   simultaneous orders          = 100 (quantity 1 each)
 *   expected afterwards          : 10 COMPLETED, 90 OUT_OF_STOCK, stock 0
 * </pre>
 *
 * <p>Full stack, nothing mocked: real target-bound transactions, the real bounded worker pool and real
 * {@code SELECT ... FOR UPDATE} locking on a real SQL engine.
 *
 * <p>The class is intentionally <em>not</em> {@code @Transactional}: each submission must commit so the
 * worker threads - which use their own connections - can see the orders.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentOrderProcessingTest {

    private static final int INITIAL_STOCK = 10;
    private static final int CONCURRENT_ORDERS = 100;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("100 concurrent orders against 10 units: 10 completed, 90 out of stock, stock never negative")
    void neverOversellsInventoryUnderConcurrentLoad() throws Exception {
        Product product = productRepository.save(new Product("Concurrency Laptop", new BigDecimal("1299.99")));
        inventoryRepository.save(new Inventory(product, INITIAL_STOCK));

        submitConcurrently(product.getId(), CONCURRENT_ORDERS);
        awaitTerminalSettlement(product.getId());

        long completed = orderRepository.countByProduct_IdAndStatus(product.getId(), OrderStatus.COMPLETED);
        long outOfStock = orderRepository.countByProduct_IdAndStatus(product.getId(), OrderStatus.OUT_OF_STOCK);
        long failed = orderRepository.countByProduct_IdAndStatus(product.getId(), OrderStatus.FAILED);
        long deadLettered = orderRepository.countByProduct_IdAndStatus(product.getId(), OrderStatus.DLQ);
        int remainingStock = inventoryRepository.findByProductId(product.getId()).orElseThrow().getQuantity();

        assertThat(completed).as("exactly the available stock may be sold").isEqualTo(INITIAL_STOCK);
        assertThat(outOfStock)
                .as("every order beyond the stock must be rejected as OUT_OF_STOCK")
                .isEqualTo(CONCURRENT_ORDERS - INITIAL_STOCK);
        assertThat(failed).as("a business rejection is not a technical failure").isZero();
        assertThat(deadLettered).as("out-of-stock orders must never reach the DLQ").isZero();

        assertThat(remainingStock).as("stock is fully consumed").isZero();
        assertThat(remainingStock).as("inventory must never become negative").isGreaterThanOrEqualTo(0);
        assertThat(orderRepository.countByProduct_Id(product.getId())).isEqualTo(CONCURRENT_ORDERS);
    }

    @Test
    @DisplayName("the same order delivered twice reserves stock only once")
    void repeatedDeliveryOfTheSameOrderReservesStockOnlyOnce() {
        Product product = productRepository.save(new Product("Idempotency Keyboard", new BigDecimal("49.50")));
        inventoryRepository.save(new Inventory(product, 1));

        // Same idempotency key twice: the second call must return the original order, not create another.
        OrderSubmissionResult first = orderService.submitOrder(
                new SubmitOrderRequest(product.getId(), 1, "checkout-idempotency-1"));
        OrderSubmissionResult second = orderService.submitOrder(
                new SubmitOrderRequest(product.getId(), 1, "checkout-idempotency-1"));

        assertThat(second.replayed()).isTrue();
        assertThat(second.order().id()).isEqualTo(first.order().id());

        awaitTerminalSettlement(product.getId());

        assertThat(orderRepository.countByProduct_Id(product.getId())).isEqualTo(1);
        assertThat(orderRepository.countByProduct_IdAndStatus(product.getId(), OrderStatus.COMPLETED)).isEqualTo(1);
        assertThat(inventoryRepository.findByProductId(product.getId()).orElseThrow().getQuantity()).isZero();
    }

    /**
     * Fires the orders from many client threads released by a single gate, which is what makes them truly
     * simultaneous: without the gate the submissions would be spread out and the race would not be
     * exercised at all.
     */
    private void submitConcurrently(Long productId, int numberOfOrders) throws Exception {
        ExecutorService submitters = Executors.newFixedThreadPool(16);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Long>> submissions = new ArrayList<>();

        try {
            for (int i = 0; i < numberOfOrders; i++) {
                submissions.add(submitters.submit(() -> {
                    startGate.await();
                    return orderService.submitOrder(new SubmitOrderRequest(productId, 1, null))
                            .order()
                            .id();
                }));
            }
            startGate.countDown();

            for (Future<Long> submission : submissions) {
                assertThat(submission.get(30, TimeUnit.SECONDS)).isNotNull();
            }
        } finally {
            submitters.shutdownNow();
            assertThat(submitters.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** Waits until nothing for this product is still PENDING or PROCESSING, i.e. the burst has drained. */
    private void awaitTerminalSettlement(Long productId) {
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> orderRepository.countByProduct_IdAndStatus(productId, OrderStatus.PENDING) == 0
                        && orderRepository.countByProduct_IdAndStatus(productId, OrderStatus.PROCESSING) == 0);
    }
}
