package com.ordermanagement.service;

import com.ordermanagement.dto.OrderResponse;
import com.ordermanagement.dto.ProductResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// Broadcasts order/inventory changes to all connected dashboard clients over SSE.
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final long EMITTER_TIMEOUT = 30L * 60 * 1000; // 30 minutes

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void broadcastOrderUpdate(OrderResponse order) {
        broadcast("order-update", order);
    }

    public void broadcastInventoryUpdate(ProductResponse product) {
        broadcast("inventory-update", product);
    }

    private void broadcast(String eventName, Object payload) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException e) {
                log.debug("Removing dead SSE emitter: {}", e.getMessage());
                emitters.remove(emitter);
            }
        }
    }
}
