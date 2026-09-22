package com.ordermanagement.controller;

import com.ordermanagement.dto.OrderResponse;
import com.ordermanagement.dto.SubmitOrderRequest;
import com.ordermanagement.entity.OrderStatus;
import com.ordermanagement.service.OrderService;
import com.ordermanagement.service.OrderSubmissionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Orders API.
 *
 * <p>{@code POST} answers {@code 201 Created} for a new order and {@code 200 OK} when an idempotency key
 * was recognised, so a client can tell whether its retry created anything.
 */
@Validated
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> submitOrder(@Valid @RequestBody SubmitOrderRequest request) {
        OrderSubmissionResult result = orderService.submitOrder(request);
        return ResponseEntity
                .status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.order());
    }

    @GetMapping
    public List<OrderResponse> listOrders(
            @RequestParam(name = "status", required = false) OrderStatus status,
            @RequestParam(name = "limit", defaultValue = "200") @Min(1) @Max(200) int limit) {
        return orderService.listRecentOrders(status, limit);
    }

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(@PathVariable Long orderId) {
        return orderService.getOrder(orderId);
    }

    @GetMapping("/status/{status}")
    public List<OrderResponse> listOrdersByStatus(@PathVariable OrderStatus status) {
        return orderService.listByStatus(status);
    }
}
