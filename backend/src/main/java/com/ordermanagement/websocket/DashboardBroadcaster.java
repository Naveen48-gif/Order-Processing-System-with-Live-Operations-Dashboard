package com.ordermanagement.websocket;

import com.ordermanagement.dto.DlqOrderResponse;
import com.ordermanagement.entity.OrderStatus;
import com.ordermanagement.event.InventoryChangedEvent;
import com.ordermanagement.event.OrderStatusChangedEvent;
import com.ordermanagement.service.DashboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges committed domain events onto the four STOMP topics in AGENT.md §5.
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} is what gives the ordering guarantee in
 * the contract: a listener only runs once the publishing transaction has actually committed, so the
 * dashboard can never be shown a state that was subsequently rolled back. Every push is a full
 * snapshot (the current {@code OrderResponse}/{@code InventoryResponse}/summary), never a delta.
 */
@Component
public class DashboardBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(DashboardBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final DashboardService dashboardService;

    public DashboardBroadcaster(SimpMessagingTemplate messagingTemplate, DashboardService dashboardService) {
        this.messagingTemplate = messagingTemplate;
        this.dashboardService = dashboardService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderChanged(OrderStatusChangedEvent event) {
        messagingTemplate.convertAndSend("/topic/orders", event.order());
        if (event.order().status() == OrderStatus.DLQ) {
            messagingTemplate.convertAndSend("/topic/dlq", DlqOrderResponse.from(event.order()));
        }
        broadcastSummary();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInventoryChanged(InventoryChangedEvent event) {
        messagingTemplate.convertAndSend("/topic/inventory", event.inventory());
        broadcastSummary();
    }

    private void broadcastSummary() {
        try {
            messagingTemplate.convertAndSend("/topic/dashboard", dashboardService.buildSummary());
        } catch (RuntimeException ex) {
            // Best-effort push: a STOMP send failure must never affect the transaction that triggered it.
            log.warn("DASHBOARD_BROADCAST_FAILED reason={}", ex.getMessage());
        }
    }
}
