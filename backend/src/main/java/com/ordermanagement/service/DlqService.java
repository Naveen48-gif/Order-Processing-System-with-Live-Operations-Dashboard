package com.ordermanagement.service;

import com.ordermanagement.dto.DlqOrderResponse;
import com.ordermanagement.dto.OrderResponse;
import com.ordermanagement.entity.Order;
import com.ordermanagement.entity.OrderStatus;
import com.ordermanagement.exception.OrderNotFoundException;
import com.ordermanagement.exception.OrderNotInDeadLetterQueueException;
import com.ordermanagement.messaging.OrderTransport;
import com.ordermanagement.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Dead-letter queue read model and the one admin action available on it: manual replay.
 *
 * <p>DLQ is a status on the order, not a second table, so nothing is lost while an order sits there and
 * replay is simply re-entering the same guarded state machine used for every other delivery.
 */
@Service
public class DlqService {

    private static final Logger log = LoggerFactory.getLogger(DlqService.class);

    private final OrderRepository orderRepository;
    private final OrderLifecycleService orderLifecycleService;
    private final OrderTransport orderTransport;

    public DlqService(OrderRepository orderRepository,
                      OrderLifecycleService orderLifecycleService,
                      OrderTransport orderTransport) {
        this.orderRepository = orderRepository;
        this.orderLifecycleService = orderLifecycleService;
        this.orderTransport = orderTransport;
    }

    @Transactional(readOnly = true)
    public List<DlqOrderResponse> listDeadLettered() {
        return orderRepository.findByStatusWithProduct(OrderStatus.DLQ).stream()
                .map(OrderResponse::from)
                .map(DlqOrderResponse::from)
                .toList();
    }

    /**
     * Re-enters a dead-lettered order into the pipeline.
     *
     * <p>{@link OrderLifecycleService#beginProcessing} re-checks the order's status under its own row
     * lock before flipping it to {@code PROCESSING}, so two concurrent replay clicks on the same order
     * cannot both re-submit it.
     */
    @Transactional
    public OrderResponse replay(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.DLQ) {
            throw new OrderNotInDeadLetterQueueException(orderId, order.getStatus());
        }

        if (!orderLifecycleService.beginProcessing(orderId)) {
            throw new OrderNotInDeadLetterQueueException(orderId, order.getStatus());
        }

        log.info("ORDER_DLQ_REPLAY orderId={}", orderId);
        dispatchAfterCommit(orderId);

        return orderRepository.findByIdWithProduct(orderId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private void dispatchAfterCommit(Long orderId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    orderTransport.submit(orderId);
                }
            });
        } else {
            orderTransport.submit(orderId);
        }
    }
}
