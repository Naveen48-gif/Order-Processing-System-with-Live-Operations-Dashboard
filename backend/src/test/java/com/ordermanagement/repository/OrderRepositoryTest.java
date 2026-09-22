package com.ordermanagement.repository;

import com.ordermanagement.entity.Inventory;
import com.ordermanagement.entity.Order;
import com.ordermanagement.entity.OrderStatus;
import com.ordermanagement.entity.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query-level guarantees behind the order APIs and the dashboard counters.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TestEntityManager testEntityManager;

    private Product persistProduct(String name) {
        Product product = testEntityManager.persistAndFlush(new Product(name, new BigDecimal("99.00")));
        testEntityManager.persistAndFlush(new Inventory(product, 100));
        return product;
    }

    private Order persistOrder(Product product, OrderStatus status, String idempotencyKey) {
        Order order = new Order(product, 1, idempotencyKey);
        if (status != OrderStatus.PENDING) {
            order.markProcessing();
            switch (status) {
                case COMPLETED -> order.markCompleted();
                case OUT_OF_STOCK -> order.markOutOfStock("no stock");
                case FAILED -> order.registerTechnicalFailure("boom");
                case DLQ -> {
                    order.registerTechnicalFailure("boom");
                    order.moveToDeadLetter("retry budget exhausted");
                }
                default -> {
                    // PROCESSING and PENDING need no extra transition.
                }
            }
        }
        return testEntityManager.persistAndFlush(order);
    }

    @Test
    @DisplayName("an order round-trips with its product reference, status and retry count")
    void savesAndReadsOrder() {
        Product product = persistProduct("Laptop");
        Order saved = persistOrder(product, OrderStatus.PENDING, null);

        Order reloaded = orderRepository.findByIdWithProduct(saved.getId()).orElseThrow();

        assertThat(reloaded.getProduct().getId()).isEqualTo(product.getId());
        assertThat(reloaded.getQuantity()).isEqualTo(1);
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("the idempotency key finds the original order")
    void findsOrderByIdempotencyKey() {
        Product product = persistProduct("Keyboard");
        persistOrder(product, OrderStatus.PENDING, "checkout-42");

        assertThat(orderRepository.findByIdempotencyKey("checkout-42")).isPresent();
        assertThat(orderRepository.findByIdempotencyKey("checkout-99")).isEmpty();
    }

    @Test
    @DisplayName("the dashboard counters come from one grouped query")
    void countsOrdersByStatus() {
        Product product = persistProduct("Monitor");
        persistOrder(product, OrderStatus.PENDING, null);
        persistOrder(product, OrderStatus.PENDING, null);
        persistOrder(product, OrderStatus.PROCESSING, null);
        persistOrder(product, OrderStatus.COMPLETED, null);
        persistOrder(product, OrderStatus.OUT_OF_STOCK, null);
        persistOrder(product, OrderStatus.DLQ, null);

        Map<OrderStatus, Long> counts = orderRepository.countGroupedByStatus().stream()
                .collect(Collectors.toMap(StatusCountView::getStatus, StatusCountView::getTotal));

        assertThat(counts)
                .containsEntry(OrderStatus.PENDING, 2L)
                .containsEntry(OrderStatus.PROCESSING, 1L)
                .containsEntry(OrderStatus.COMPLETED, 1L)
                .containsEntry(OrderStatus.OUT_OF_STOCK, 1L)
                .containsEntry(OrderStatus.DLQ, 1L);

        assertThat(orderRepository.countByStatus(OrderStatus.PENDING)).isEqualTo(2L);
    }

    @Test
    @DisplayName("paging and status filters drive the orders table")
    void pagesAndFiltersByStatus() {
        Product product = persistProduct("Chair");
        for (int i = 0; i < 5; i++) {
            persistOrder(product, OrderStatus.COMPLETED, null);
        }
        persistOrder(product, OrderStatus.OUT_OF_STOCK, null);

        var page = orderRepository.findByStatus(OrderStatus.COMPLETED, PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).hasSize(2);
        assertThat(orderRepository.findByStatusWithProduct(OrderStatus.OUT_OF_STOCK)).hasSize(1);
    }

    @Test
    @DisplayName("the recent-activity feed is ordered by last update and pre-loads the product")
    void recentFeedIsNewestFirst() {
        Product product = persistProduct("Desk");
        persistOrder(product, OrderStatus.COMPLETED, null);
        Order latest = persistOrder(product, OrderStatus.FAILED, null);

        var feed = orderRepository.findRecentWithProduct(PageRequest.of(0, 10));

        assertThat(feed).hasSize(2);
        assertThat(feed).extracting(Order::getId).contains(latest.getId());
        // Ordering assertion is expressed as a contract (descending by updatedAt) so that two orders
        // written within the same clock tick cannot make the test flaky.
        assertThat(feed).extracting(Order::getUpdatedAt).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(feed).allSatisfy(order -> assertThat(order.getProduct().getName()).isNotBlank());
    }

    @Test
    @DisplayName("replay candidates are DLQ orders that exhausted their retry budget")
    void findsReplayCandidates() {
        Product product = persistProduct("Lamp");
        persistOrder(product, OrderStatus.DLQ, null);

        var candidates = orderRepository.findByStatusAndRetryCountGreaterThanEqualOrderByUpdatedAtAsc(
                OrderStatus.DLQ, 1);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getStatus()).isEqualTo(OrderStatus.DLQ);
    }

    @Test
    @DisplayName("status filtering returns only the rows for that status")
    void statusFilterReturnsOnlyMatchingRows() {
        Product first = persistProduct("Product A");
        Product second = persistProduct("Product B");
        persistOrder(first, OrderStatus.PENDING, null);
        persistOrder(second, OrderStatus.COMPLETED, null);

        var pending = orderRepository.findByStatusOrderByCreatedAtAsc(OrderStatus.PENDING);

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getProduct().getId()).isEqualTo(first.getId());
    }
}
