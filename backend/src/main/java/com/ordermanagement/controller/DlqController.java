package com.ordermanagement.controller;

import com.ordermanagement.dto.DlqOrderResponse;
import com.ordermanagement.dto.OrderResponse;
import com.ordermanagement.service.DlqService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Dead-letter queue: what needs a human, and the one action they can take (replay). */
@RestController
@RequestMapping("/api/dlq")
public class DlqController {

    private final DlqService dlqService;

    public DlqController(DlqService dlqService) {
        this.dlqService = dlqService;
    }

    @GetMapping
    public List<DlqOrderResponse> listDeadLettered() {
        return dlqService.listDeadLettered();
    }

    @PostMapping("/{orderId}/retry")
    public ResponseEntity<OrderResponse> retry(@PathVariable Long orderId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(dlqService.replay(orderId));
    }
}
