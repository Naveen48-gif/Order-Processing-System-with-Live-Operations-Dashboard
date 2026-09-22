package com.ordermanagement.service;

import com.ordermanagement.dto.OrderResponse;
import com.ordermanagement.exception.InventoryLockException;
import com.ordermanagement.exception.OutOfStockException;
import com.ordermanagement.exception.ProductNotFoundException;
import com.ordermanagement.model.DeadLetterOrder;
import com.ordermanagement.model.Order;
import com.ordermanagement.model.OrderStatus;
import com.ordermanagement.repository.DeadLetterOrderRepository;
import com.ordermanagement.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;

// Consumes orders concurrently off a bounded thread pool, enforces inventory safety via
// InventoryService, and routes unrecoverable orders to the dead-letter queue.
@Service
public class OrderProcessingService {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessingService.class);

    private final ExecutorService orderExecutor;
    private final OrderRepository orderRepository;
    private final DeadLetterOrderRepository deadLetterOrderRepository;
    private final InventoryService inventoryService;
    private final NotificationService notificationService;

    @Value("${order.processing.max-retry-attempts:3}")
    private int maxRetryAttempts;

    public OrderProcessingService(@Qualifier("orderExecutor") ExecutorService orderExecutor,
                                   OrderRepository orderRepository,
                                   DeadLetterOrderRepository deadLetterOrderRepository,
                                   InventoryService inventoryService,
                                   NotificationService notificationService) {
        this.orderExecutor = orderExecutor;
        this.orderRepository = orderRepository;
        this.deadLetterOrderRepository = deadLetterOrderRepository;
        this.inventoryService = inventoryService;
        this.notificationService = notificationService;
    }

    public void submitOrder(Long orderId) {
        orderExecutor.submit(() -> processOrder(orderId));
    }

    private void processOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("Order {} disappeared before processing", orderId);
            return;
        }

        updateStatus(order, OrderStatus.PROCESSING, null, 0);

        try {
            inventoryService.reserveStock(order.getProductId(), order.getQuantity());
            updateStatus(order, OrderStatus.COMPLETED, null, order.getRetryCount());
        } catch (OutOfStockException ex) {
            updateStatus(order, OrderStatus.OUT_OF_STOCK, ex.getMessage(), 0);
            deadLetter(order, "OUT_OF_STOCK: " + ex.getMessage(), 0);
        } catch (InventoryLockException ex) {
            updateStatus(order, OrderStatus.FAILED, ex.getMessage(), maxRetryAttempts);
            deadLetter(order, "TRANSIENT_FAILURE_AFTER_RETRIES: " + ex.getMessage(), maxRetryAttempts);
        } catch (ProductNotFoundException ex) {
            updateStatus(order, OrderStatus.FAILED, ex.getMessage(), 0);
            deadLetter(order, "PRODUCT_NOT_FOUND: " + ex.getMessage(), 0);
        } catch (Exception ex) {
            log.error("Unexpected error processing order {}", orderId, ex);
            updateStatus(order, OrderStatus.FAILED, ex.getMessage(), 0);
            deadLetter(order, "UNEXPECTED_ERROR: " + ex.getMessage(), 0);
        }
    }

    // Note: not @Transactional here — these are self-invoked from processOrder() so a proxy-based
    // transaction wouldn't apply anyway. SimpleJpaRepository#save is transactional on its own.
    private void updateStatus(Order order, OrderStatus status, String failureReason, int retryCount) {
        order.setStatus(status);
        order.setFailureReason(failureReason);
        order.setRetryCount(retryCount);
        Order saved = orderRepository.save(order);
        notificationService.broadcastOrderUpdate(OrderResponse.from(saved));
    }

    private void deadLetter(Order order, String reason, int retryCount) {
        deadLetterOrderRepository.save(new DeadLetterOrder(
                order.getId(), order.getProductId(), order.getQuantity(), reason, retryCount));
    }
}
