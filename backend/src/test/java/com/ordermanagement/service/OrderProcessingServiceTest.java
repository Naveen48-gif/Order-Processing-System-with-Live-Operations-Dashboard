package com.ordermanagement.service;

import com.ordermanagement.config.OrderProcessingProperties;
import com.ordermanagement.entity.Order;
import com.ordermanagement.entity.Product;
import com.ordermanagement.exception.InventoryNotFoundException;
import com.ordermanagement.exception.OutOfStockException;
import com.ordermanagement.messaging.OrderTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Worker-level routing rules, with collaborators mocked so each branch is asserted in isolation.
 *
 * <p>Pins down the most important distinction in the system: <em>business</em> failures are terminal and
 * silent, <em>technical</em> failures are retried a bounded number of times and then dead-lettered.
 */
@ExtendWith(MockitoExtension.class)
class OrderProcessingServiceTest {

    private static final long ORDER_ID = 7L;

    @Mock
    private OrderLifecycleService orderLifecycleService;

    @Mock
    private InventoryReservationService inventoryReservationService;

    @Mock
    private ObjectProvider<OrderTransport> orderTransportProvider;

    @Mock
    private OrderTransport orderTransport;

    private OrderProcessingProperties properties;
    private OrderProcessingService orderProcessingService;

    @BeforeEach
    void setUp() {
        properties = new OrderProcessingProperties();
        orderProcessingService = new OrderProcessingService(
                orderLifecycleService, inventoryReservationService, properties, orderTransportProvider);
    }

    @Test
    @DisplayName("an order that is already settled is skipped without touching inventory")
    void skipsOrdersThatAreNotActionable() {
        when(orderLifecycleService.beginProcessing(ORDER_ID)).thenReturn(false);

        OrderProcessingResult result = orderProcessingService.processOrder(ORDER_ID);

        assertThat(result.outcome()).isEqualTo(OrderProcessingResult.Outcome.SKIPPED);
        verifyNoInteractions(inventoryReservationService);
        verifyNoInteractions(orderTransportProvider);
    }

    @Test
    @DisplayName("a successful reservation is reported as COMPLETED and never retried")
    void reportsCompletedReservation() {
        Order order = newOrder();
        when(orderLifecycleService.beginProcessing(ORDER_ID)).thenReturn(true);
        when(inventoryReservationService.reserveStockForOrder(ORDER_ID))
                .thenReturn(OrderProcessingResult.completed(order));

        OrderProcessingResult result = orderProcessingService.processOrder(ORDER_ID);

        assertThat(result.outcome()).isEqualTo(OrderProcessingResult.Outcome.COMPLETED);
        verify(orderLifecycleService, never()).registerTransientFailure(anyLong(), anyString());
        verifyNoInteractions(orderTransportProvider);
    }

    @Test
    @DisplayName("an out-of-stock rejection is settled as OUT_OF_STOCK: never retried, never dead-lettered")
    void outOfStockIsNeverRetried() {
        Order order = newOrder();
        when(orderLifecycleService.beginProcessing(ORDER_ID)).thenReturn(true);
        when(inventoryReservationService.reserveStockForOrder(ORDER_ID))
                .thenThrow(new OutOfStockException(1L, 5, 2));
        when(orderLifecycleService.settleOutOfStock(eq(ORDER_ID), anyString()))
                .thenReturn(OrderProcessingResult.outOfStock(order, "Insufficient inventory"));

        OrderProcessingResult result = orderProcessingService.processOrder(ORDER_ID);

        // The failure reason must still be recorded: an order left in PROCESSING would be stranded,
        // because PROCESSING is not a state any worker is allowed to pick up again.
        assertThat(result.outcome()).isEqualTo(OrderProcessingResult.Outcome.OUT_OF_STOCK);
        verify(orderLifecycleService).settleOutOfStock(eq(ORDER_ID), anyString());
        verify(orderLifecycleService, never()).registerTransientFailure(anyLong(), anyString());
        verify(orderLifecycleService, never()).rejectToDeadLetter(anyLong(), anyString());
        verifyNoInteractions(orderTransport);
    }

    @Test
    @DisplayName("a business failure a human must fix (product with no inventory row) is dead-lettered, not retried")
    void businessFailureNeedingAHumanIsDeadLettered() {
        Order order = newOrder();
        when(orderLifecycleService.beginProcessing(ORDER_ID)).thenReturn(true);
        when(inventoryReservationService.reserveStockForOrder(ORDER_ID))
                .thenThrow(new InventoryNotFoundException(1L));
        when(orderLifecycleService.rejectToDeadLetter(eq(ORDER_ID), anyString()))
                .thenReturn(OrderProcessingResult.deadLettered(order, "no inventory record"));

        OrderProcessingResult result = orderProcessingService.processOrder(ORDER_ID);

        assertThat(result.outcome()).isEqualTo(OrderProcessingResult.Outcome.DEAD_LETTERED);
        // Retrying cannot create the missing inventory row, so no retry budget may be spent on it.
        verify(orderLifecycleService, never()).registerTransientFailure(anyLong(), anyString());
        verify(orderTransport, never()).submitForRetry(anyLong(), anyLong());
    }

    @Test
    @DisplayName("a transient fault consumes retry budget and is re-submitted with an increasing backoff")
    void retriesTransientFailuresWithBackoff() {
        Order order = failedOrderWithRetries(2);
        when(orderLifecycleService.beginProcessing(ORDER_ID)).thenReturn(true);
        when(inventoryReservationService.reserveStockForOrder(ORDER_ID))
                .thenThrow(new CannotAcquireLockException("lock acquisition timed out"));
        when(orderLifecycleService.registerTransientFailure(eq(ORDER_ID), anyString()))
                .thenReturn(OrderProcessingResult.retryableFailure(order, "lock acquisition timed out"));
        when(orderTransportProvider.getIfAvailable()).thenReturn(orderTransport);

        OrderProcessingResult result = orderProcessingService.processOrder(ORDER_ID);

        assertThat(result.outcome()).isEqualTo(OrderProcessingResult.Outcome.RETRYABLE_FAILURE);
        assertThat(result.retryCount()).isEqualTo(2);
        // Backoff scales with the attempt number: retryBackoffMs * retryCount.
        verify(orderTransport).submitForRetry(ORDER_ID, properties.getRetryBackoffMs() * 2);
    }

    @Test
    @DisplayName("a fault that exhausts the budget goes to the DLQ and is not re-submitted")
    void exhaustedBudgetIsDeadLettered() {
        Order order = failedOrderWithRetries(properties.getMaxRetryAttempts());
        when(orderLifecycleService.beginProcessing(ORDER_ID)).thenReturn(true);
        when(inventoryReservationService.reserveStockForOrder(ORDER_ID))
                .thenThrow(new CannotAcquireLockException("connection reset"));
        when(orderLifecycleService.registerTransientFailure(eq(ORDER_ID), anyString()))
                .thenReturn(OrderProcessingResult.deadLettered(order, "budget exhausted"));

        OrderProcessingResult result = orderProcessingService.processOrder(ORDER_ID);

        assertThat(result.outcome()).isEqualTo(OrderProcessingResult.Outcome.DEAD_LETTERED);
        verify(orderTransport, never()).submitForRetry(anyLong(), anyLong());
    }

    @Test
    @DisplayName("a technical fault is recorded with its cause so the DLQ entry is actionable")
    void recordsTheTechnicalCause() {
        Order order = failedOrderWithRetries(1);
        when(orderLifecycleService.beginProcessing(ORDER_ID)).thenReturn(true);
        when(inventoryReservationService.reserveStockForOrder(ORDER_ID))
                .thenThrow(new CannotAcquireLockException("deadlock victim"));
        when(orderLifecycleService.registerTransientFailure(eq(ORDER_ID),
                eq("CannotAcquireLockException: deadlock victim")))
                .thenReturn(OrderProcessingResult.retryableFailure(order, "deadlock victim"));
        when(orderTransportProvider.getIfAvailable()).thenReturn(orderTransport);

        orderProcessingService.processOrder(ORDER_ID);

        verify(orderLifecycleService).registerTransientFailure(
                ORDER_ID, "CannotAcquireLockException: deadlock victim");
    }

    private static Order newOrder() {
        return new Order(new Product("Laptop", new BigDecimal("1299.99")), 1, null);
    }

    /** Builds an order sitting in FAILED with the given number of consumed retry attempts. */
    private static Order failedOrderWithRetries(int attempts) {
        Order order = newOrder();
        order.markProcessing();
        for (int attempt = 0; attempt < attempts; attempt++) {
            order.registerTechnicalFailure("earlier transient fault");
            if (attempt < attempts - 1) {
                order.markRetrying();
            }
        }
        return order;
    }
}
