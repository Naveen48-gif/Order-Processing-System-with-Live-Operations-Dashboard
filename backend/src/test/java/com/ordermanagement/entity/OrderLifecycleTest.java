package com.ordermanagement.entity;

import com.ordermanagement.exception.InvalidOrderStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the order lifecycle. These assertions are the reason a redelivered message can never re-run a
 * business side effect: status can only change through the guarded mutators exercised here.
 */
class OrderLifecycleTest {

    private static final int RETRY_BUDGET = 3;

    private final Product product = new Product("Laptop", new BigDecimal("1299.99"));

    private Order newOrder(int quantity) {
        return new Order(product, quantity, null);
    }

    @Test
    @DisplayName("a new order starts as PENDING with an untouched retry budget")
    void newOrderStartsPending() {
        Order order = newOrder(2);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getRetryCount()).isZero();
        assertThat(order.getFailureReason()).isNull();
        assertThat(order.isProcessable()).isTrue();
    }

    @Test
    @DisplayName("PENDING -> PROCESSING -> COMPLETED is the happy path and COMPLETED is terminal")
    void completedOrderIsTerminal() {
        Order order = newOrder(1);

        order.markProcessing();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PROCESSING);

        order.markCompleted();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getStatus().isTerminal()).isTrue();

        assertThatThrownBy(order::markProcessing)
                .isInstanceOf(InvalidOrderStateTransitionException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    @DisplayName("an order cannot skip PROCESSING")
    void cannotSkipProcessing() {
        Order order = newOrder(1);

        assertThatThrownBy(order::markCompleted)
                .isInstanceOf(InvalidOrderStateTransitionException.class);
    }

    @Test
    @DisplayName("OUT_OF_STOCK is terminal and may never be retried")
    void outOfStockIsTerminalAndNotRetryable() {
        Order order = newOrder(5);
        order.markProcessing();
        order.markOutOfStock("requested 5, available 2");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.OUT_OF_STOCK);
        assertThat(order.getFailureReason()).isEqualTo("requested 5, available 2");
        assertThat(order.getRetryCount()).isZero();

        assertThatThrownBy(order::markProcessing)
                .isInstanceOf(InvalidOrderStateTransitionException.class);
        assertThatThrownBy(() -> order.registerTechnicalFailure("nope"))
                .isInstanceOf(InvalidOrderStateTransitionException.class);
    }

    @Test
    @DisplayName("technical failures consume the retry budget and can be retried")
    void technicalFailuresConsumeRetryBudget() {
        Order order = newOrder(1);
        order.markProcessing();

        assertThat(order.hasRetryBudgetLeft(RETRY_BUDGET)).isTrue();

        for (int attempt = 1; attempt <= RETRY_BUDGET; attempt++) {
            assertThat(order.registerTechnicalFailure("connection reset")).isEqualTo(attempt);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
            order.markRetrying();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PROCESSING);
        }

        assertThat(order.getRetryCount()).isEqualTo(RETRY_BUDGET);
        assertThat(order.hasRetryBudgetLeft(RETRY_BUDGET)).isFalse();
    }

    @Test
    @DisplayName("a FAILED order can be dead-lettered, and an admin can replay it from the DLQ")
    void deadLetterAndAdminReplay() {
        Order order = newOrder(1);
        order.markProcessing();
        order.registerTechnicalFailure("broker unavailable");
        order.moveToDeadLetter("retry budget exhausted");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DLQ);
        assertThat(order.getStatus().isDeadLettered()).isTrue();

        order.markRetrying();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PROCESSING);
    }

    @Test
    @DisplayName("an out-of-stock order can never reach the dead-letter queue")
    void outOfStockCannotBeDeadLettered() {
        Order order = newOrder(1);
        order.markProcessing();
        order.markOutOfStock("no stock");

        assertThatThrownBy(() -> order.moveToDeadLetter("should not happen"))
                .isInstanceOf(InvalidOrderStateTransitionException.class);
    }

    @Test
    @DisplayName("non-positive quantities are rejected at construction")
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> newOrder(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newOrder(-3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a long failure reason is truncated to the column width instead of failing the insert")
    void truncatesLongFailureReason() {
        Order order = newOrder(1);
        order.markProcessing();
        order.markOutOfStock("x".repeat(900));

        assertThat(order.getFailureReason()).hasSize(500);
    }

    @Test
    @DisplayName("status semantics line up with the frozen transition table")
    void transitionTableMatchesContract() {
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.PROCESSING)).isTrue();
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.COMPLETED)).isFalse();
        assertThat(OrderStatus.PROCESSING.canTransitionTo(OrderStatus.OUT_OF_STOCK)).isTrue();
        assertThat(OrderStatus.PROCESSING.canTransitionTo(OrderStatus.DLQ)).isFalse();
        assertThat(OrderStatus.FAILED.canTransitionTo(OrderStatus.DLQ)).isTrue();
        assertThat(OrderStatus.DLQ.canTransitionTo(OrderStatus.PROCESSING)).isTrue();
        assertThat(OrderStatus.COMPLETED.canTransitionTo(OrderStatus.PROCESSING)).isFalse();
        assertThat(OrderStatus.OUT_OF_STOCK.isTerminal()).isTrue();
        assertThat(OrderStatus.DLQ.isActionable()).isFalse();
    }
}
