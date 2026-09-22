package com.ordermanagement.service;

import com.ordermanagement.config.OrderProcessingProperties;
import com.ordermanagement.dto.OrderResponse;
import com.ordermanagement.entity.Order;
import com.ordermanagement.entity.OrderStatus;
import com.ordermanagement.event.OrderStatusChangedEvent;
import com.ordermanagement.exception.InvalidOrderStateTransitionException;
import com.ordermanagement.exception.OrderNotFoundException;
import com.ordermanagement.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short, focused state transitions on an order.
 *
 * <p>Why these are separate transactions from the reservation itself:
 * <ul>
 *   <li><strong>Observability.</strong> {@code PROCESSING} is committed before the reservation starts, so
 *       the operations dashboard actually sees work in flight. Folding it into the reservation
 *       transaction would mean the intermediate state never exists in the database.</li>
 *   <li><strong>Shorter lock window.</strong> Bookkeeping calls do not hold the inventory row lock. The
 *       lock is held only for the few statements that must be atomic with the stock change, which is
 *       what keeps throughput up when many orders target the same product.</li>
 * </ul>
 */
@Service
public class OrderLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleService.class);

    private final OrderRepository orderRepository;
    private final OrderProcessingProperties processingProperties;
    private final ApplicationEventPublisher eventPublisher;

    public OrderLifecycleService(OrderRepository orderRepository,
                                 OrderProcessingProperties processingProperties,
                                 ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.processingProperties = processingProperties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Moves an order into {@code PROCESSING}.
     *
     * @return {@code false} when there is nothing to do (already terminal, or the order vanished), which
     *         is how a duplicate delivery becomes a no-op instead of double work
     */
    @Transactional
    public boolean beginProcessing(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("ORDER_NOT_FOUND orderId={} stage=beginProcessing", orderId);
            return false;
        }

        OrderStatus status = order.getStatus();
        if (status == OrderStatus.PENDING) {
            order.markProcessing();
        } else if (status == OrderStatus.FAILED || status == OrderStatus.DLQ) {
            // Re-entry after a retry (or an operator replay): FAILED/DLQ -> PROCESSING is legal.
            order.markRetrying();
        } else {
            log.info("ORDER_SKIPPED orderId={} status={} reason=not_actionable", orderId, status);
            return false;
        }

        orderRepository.save(order);
        log.info("ORDER_PROCESSING orderId={} productId={} quantity={} retryCount={}",
                orderId, order.getProduct().getId(), order.getQuantity(), order.getRetryCount());
        eventPublisher.publishEvent(new OrderStatusChangedEvent(OrderResponse.from(order)));
        return true;
    }

    /**
     * Records a transient failure and decides between "retry later" and "hand over to a human".
     *
     * <p>The retry count is incremented for every technical failure, which is what makes the bound real:
     * once {@code order.processing.max-retry-attempts} is reached the order moves to {@code DLQ} and stops
     * consuming worker capacity.
     */
    @Transactional
    public OrderProcessingResult registerTransientFailure(Long orderId, String reason) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            log.warn("ORDER_NOT_FOUND orderId={} stage=registerTransientFailure", orderId);
            return OrderProcessingResult.skipped(orderId, null);
        }
        if (!order.getStatus().canTransitionTo(OrderStatus.FAILED)) {
            // Another worker already finished or rejected this order: leave the retry budget alone.
            return OrderProcessingResult.skipped(orderId, order.getStatus());
        }

        int maxAttempts = processingProperties.getMaxRetryAttempts();
        int attempt = order.registerTechnicalFailure(reason);

        if (order.hasRetryBudgetLeft(maxAttempts)) {
            log.warn("ORDER_RETRY orderId={} attempt={}/{} reason={}", orderId, attempt, maxAttempts, reason);
            eventPublisher.publishEvent(new OrderStatusChangedEvent(OrderResponse.from(order)));
            return OrderProcessingResult.retryableFailure(order, reason);
        }

        order.moveToDeadLetter("retry budget exhausted after %d attempts: %s".formatted(attempt, reason));
        log.error("ORDER_DLQ orderId={} attempts={} reason={}", orderId, attempt, reason);
        eventPublisher.publishEvent(new OrderStatusChangedEvent(OrderResponse.from(order)));
        return OrderProcessingResult.deadLettered(order, reason);
    }

    /**
     * Settles an order as a business rejection ({@code OUT_OF_STOCK}).
     *
     * <p>This is the safety net behind {@link InventoryReservationService}, which normally records the
     * rejection itself: if the reservation transaction rolls back and the caller only sees an exception,
     * the order still has to end up in a definitive, non-retryable state rather than stuck in
     * {@code PROCESSING}.
     */
    @Transactional
    public OrderProcessingResult settleOutOfStock(Long orderId, String reason) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return OrderProcessingResult.skipped(orderId, null);
        }
        if (!order.getStatus().canTransitionTo(OrderStatus.OUT_OF_STOCK)) {
            // Already settled by another worker, or not in a state that can be rejected.
            return OrderProcessingResult.skipped(orderId, order.getStatus());
        }
        order.markOutOfStock(reason);
        log.info("ORDER_OUT_OF_STOCK orderId={} reason={}", orderId, reason);
        eventPublisher.publishEvent(new OrderStatusChangedEvent(OrderResponse.from(order)));
        return OrderProcessingResult.outOfStock(order, reason);
    }

    /**
     * Places an order that needs human action (for example a product with no inventory row) into the
     * dead-letter queue without consuming retry budget: retrying cannot create the missing data.
     */
    @Transactional
    public OrderProcessingResult rejectToDeadLetter(Long orderId, String reason) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return OrderProcessingResult.skipped(orderId, null);
        }
        if (!order.getStatus().canTransitionTo(OrderStatus.FAILED)) {
            return OrderProcessingResult.skipped(orderId, order.getStatus());
        }
        order.registerTechnicalFailure(reason);
        order.moveToDeadLetter(reason);
        log.error("ORDER_DLQ orderId={} reason={}", orderId, reason);
        eventPublisher.publishEvent(new OrderStatusChangedEvent(OrderResponse.from(order)));
        return OrderProcessingResult.deadLettered(order, reason);
    }

    /** Puts a dead-lettered order back into the pipeline (admin replay; exposed by the DLQ API). */
    @Transactional
    public OrderProcessingResult replayDeadLetteredOrder(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (!order.getStatus().isDeadLettered()) {
            throw new InvalidOrderStateTransitionException(orderId, order.getStatus(), OrderStatus.PROCESSING);
        }
        order.markRetrying();
        log.info("ORDER_REPLAYED orderId={} previousStatus=DLQ", orderId);
        eventPublisher.publishEvent(new OrderStatusChangedEvent(OrderResponse.from(order)));
        return OrderProcessingResult.retryableFailure(order, "manual replay");
    }
}
