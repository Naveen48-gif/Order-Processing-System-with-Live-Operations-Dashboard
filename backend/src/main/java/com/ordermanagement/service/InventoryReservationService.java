package com.ordermanagement.service;

import com.ordermanagement.entity.Inventory;
import com.ordermanagement.entity.Order;
import com.ordermanagement.entity.OrderStatus;
import com.ordermanagement.dto.InventoryResponse;
import com.ordermanagement.dto.OrderResponse;
import com.ordermanagement.event.InventoryChangedEvent;
import com.ordermanagement.event.OrderStatusChangedEvent;
import com.ordermanagement.exception.InventoryNotFoundException;
import com.ordermanagement.exception.OrderNotFoundException;
import com.ordermanagement.repository.InventoryRepository;
import com.ordermanagement.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stock reservation path - the only place in the system that decrements inventory.
 *
 * <h2>Why the whole method is one transaction with a pessimistic row lock</h2>
 * The sequence "read stock, decide, decrement, mark the order done" must be atomic. Without a lock two
 * workers can interleave:
 *
 * <pre>
 *   worker A: SELECT quantity -&gt; 1        worker B: SELECT quantity -&gt; 1      (both saw 1!)
 *   worker A: 1 &gt;= 1, UPDATE ... -&gt; 0
 *                                          worker B: 1 &gt;= 1, UPDATE ... -&gt; -1   (oversold)
 * </pre>
 *
 * {@code @Lock(PESSIMISTIC_WRITE)} makes the first statement take a database row lock
 * ({@code SELECT ... FOR UPDATE}), so worker B blocks until A commits and then reads {@code 0} and
 * correctly rejects its order.
 *
 * <h2>Why a JVM lock is not enough</h2>
 * {@code synchronized} or a {@code ReentrantLock} protects one JVM only. As soon as a second instance
 * runs - horizontal scale-out behind a load balancer, a rolling deploy with two versions live, or a
 * developer running the app locally against the same database - each process holds its own monitor
 * while both write the same row, and the stock is oversold by perfectly "thread-safe" code. The
 * database is the only resource shared by all instances, so the guarantee has to live there.
 *
 * <p>The lock chain is always order row first, inventory row second, so concurrent workers cannot
 * deadlock against each other.
 */
@Service
public class InventoryReservationService {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationService.class);

    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final ApplicationEventPublisher eventPublisher;
    // Optional by design: the failure drill is a gated bean, so it is resolved lazily and a missing
    // drill must never stop the reservation path from being created.
    private final ObjectProvider<FaultInjector> faultInjectorProvider;

    public InventoryReservationService(OrderRepository orderRepository,
                                       InventoryRepository inventoryRepository,
                                       ApplicationEventPublisher eventPublisher,
                                       ObjectProvider<FaultInjector> faultInjectorProvider) {
        this.orderRepository = orderRepository;
        this.inventoryRepository = inventoryRepository;
        this.eventPublisher = eventPublisher;
        this.faultInjectorProvider = faultInjectorProvider;
    }

    /**
     * Reserves stock for one order and settles its final status in the same transaction.
     *
     * <p>Idempotency: the order row is locked and re-checked first, so a duplicate delivery finds a
     * terminal status and returns {@code SKIPPED} without touching inventory.
     */
    @Transactional
    public OrderProcessingResult reserveStockForOrder(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (!order.getStatus().canTransitionTo(OrderStatus.COMPLETED)) {
            log.info("ORDER_SKIPPED orderId={} status={} reason=already_settled", orderId, order.getStatus());
            return OrderProcessingResult.skipped(orderId, order.getStatus());
        }

        Long productId = order.getProduct().getId();
        int requestedQuantity = order.getQuantity();

        // Failure drill (gated, off by default): an injected transient fault rolls this transaction back
        // exactly like a real one, so the order travels the genuine retry path. Absent bean = no-op.
        FaultInjector faultInjector = faultInjectorProvider.getIfAvailable();
        if (faultInjector != null) {
            faultInjector.failIfArmed(productId);
        }

        Inventory inventory = inventoryRepository.findForUpdateByProductId(productId)
                .orElseThrow(() -> new InventoryNotFoundException(productId));

        log.info("INVENTORY_LOCKED orderId={} productId={} available={} requested={}",
                orderId, productId, inventory.getQuantity(), requestedQuantity);

        if (!inventory.hasStockFor(requestedQuantity)) {
            int available = inventory.getQuantity();
            String reason = "insufficient stock: requested %d, available %d".formatted(requestedQuantity, available);
            order.markOutOfStock(reason);
            log.info("ORDER_OUT_OF_STOCK orderId={} productId={} requested={} available={}",
                    orderId, productId, requestedQuantity, available);
            eventPublisher.publishEvent(new OrderStatusChangedEvent(OrderResponse.from(order)));
            return OrderProcessingResult.outOfStock(order, reason);
        }

        inventory.decrement(requestedQuantity);
        order.markCompleted();

        log.info("INVENTORY_UPDATED orderId={} productId={} remaining={}", orderId, productId, inventory.getQuantity());
        log.info("ORDER_COMPLETED orderId={} productId={} quantity={}", orderId, productId, requestedQuantity);
        eventPublisher.publishEvent(new InventoryChangedEvent(InventoryResponse.from(inventory)));
        eventPublisher.publishEvent(new OrderStatusChangedEvent(OrderResponse.from(order)));
        return OrderProcessingResult.completed(order);
    }
}
