package com.ordermanagement.service;

import com.ordermanagement.dto.OrderResponse;
import com.ordermanagement.dto.SubmitOrderRequest;
import com.ordermanagement.entity.Order;
import com.ordermanagement.entity.OrderStatus;
import com.ordermanagement.entity.Product;
import com.ordermanagement.event.OrderStatusChangedEvent;
import com.ordermanagement.exception.OrderNotFoundException;
import com.ordermanagement.exception.ProductNotFoundException;
import com.ordermanagement.messaging.OrderTransport;
import com.ordermanagement.repository.OrderRepository;
import com.ordermanagement.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

/**
 * Order intake and read model.
 *
 * <h2>Hand-off after commit</h2>
 * Work is handed to the pipeline from an {@code afterCommit} callback rather than inline. Handing it
 * over inside the transaction is a classic async race: a worker can start immediately, read the order
 * row before the insert is visible to other transactions, and fail with "order not found" - or worse,
 * process an order that is then rolled back.
 *
 * <h2>Idempotency</h2>
 * When a client supplies an {@code idempotencyKey} the order is looked up first, so a retried request
 * finds the original order instead of reserving stock twice. A key that is still {@code PENDING} is
 * re-enqueued, which doubles as the recovery path when a saturated pool refused the first hand-off
 * (the client saw {@code 503} while the order was already safely persisted).
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final int MAX_RECENT_ORDERS = 200;

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final OrderTransport orderTransport;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        OrderTransport orderTransport,
                        ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.orderTransport = orderTransport;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public OrderSubmissionResult submitOrder(SubmitOrderRequest request) {
        String idempotencyKey = normalise(request.idempotencyKey());

        if (idempotencyKey != null) {
            Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return replayExistingOrder(existing.get());
            }
        }

        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ProductNotFoundException(request.productId()));

        Order order = orderRepository.saveAndFlush(new Order(product, request.quantity(), idempotencyKey));
        log.info("ORDER_RECEIVED orderId={} productId={} quantity={} idempotencyKey={}",
                order.getId(), product.getId(), order.getQuantity(), idempotencyKey);

        eventPublisher.publishEvent(new OrderStatusChangedEvent(OrderResponse.from(order)));
        enqueueAfterCommit(order.getId());
        return OrderSubmissionResult.created(OrderResponse.from(order));
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        return orderRepository.findByIdWithProduct(orderId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listRecentOrders(OrderStatus status, int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_RECENT_ORDERS);
        if (status != null) {
            return orderRepository.findByStatusWithProduct(status).stream()
                    .limit(effectiveLimit)
                    .map(OrderResponse::from)
                    .toList();
        }
        return orderRepository.findRecentWithProduct(PageRequest.of(0, effectiveLimit)).stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listByStatus(OrderStatus status) {
        return orderRepository.findByStatusWithProduct(status).stream()
                .map(OrderResponse::from)
                .toList();
    }

    /**
     * Returns the order that the idempotency key already produced.
     *
     * <p>An order that is still {@code PENDING} is handed to the pipeline again: that is the recovery
     * path for a submission whose first hand-off was rejected because the worker pool was saturated,
     * and it is safe because the pipeline re-checks the persisted status before doing anything.
     */
    private OrderSubmissionResult replayExistingOrder(Order existing) {
        log.info("ORDER_REPLAY orderId={} status={} idempotencyKey={}",
                existing.getId(), existing.getStatus(), existing.getIdempotencyKey());

        if (existing.getStatus() == OrderStatus.PENDING) {
            enqueueAfterCommit(existing.getId());
        }
        return OrderSubmissionResult.replayed(OrderResponse.from(existing));
    }

    private void enqueueAfterCommit(Long orderId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(orderId);
                }
            });
        } else {
            dispatch(orderId);
        }
    }

    private void dispatch(Long orderId) {
        // A PoolSaturatedException deliberately propagates: the order stays persisted as PENDING and the
        // caller learns to retry later with the same idempotency key.
        orderTransport.submit(orderId);
    }

    private static String normalise(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return idempotencyKey.trim();
    }
}
