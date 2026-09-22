package com.ordermanagement.service;

import com.ordermanagement.dto.OrderRequest;
import com.ordermanagement.dto.OrderResponse;
import com.ordermanagement.exception.ProductNotFoundException;
import com.ordermanagement.model.Order;
import com.ordermanagement.model.OrderStatus;
import com.ordermanagement.model.Product;
import com.ordermanagement.repository.OrderRepository;
import com.ordermanagement.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final OrderProcessingService orderProcessingService;

    public OrderService(OrderRepository orderRepository,
                         ProductRepository productRepository,
                         OrderProcessingService orderProcessingService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.orderProcessingService = orderProcessingService;
    }

    @Transactional
    public OrderResponse submitOrder(OrderRequest request) {
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ProductNotFoundException(request.getProductId()));

        Order order = new Order();
        order.setProductId(request.getProductId());
        order.setProductName(product.getName());
        order.setQuantity(request.getQuantity());
        order.setStatus(OrderStatus.PENDING);
        order.setRetryCount(0);

        Order saved = orderRepository.save(order);

        // Hand off to the bounded thread pool for concurrent async processing.
        orderProcessingService.submitOrder(saved.getId());

        return OrderResponse.from(saved);
    }

    public List<OrderResponse> listRecentOrders() {
        return orderRepository.findTop200ByOrderByCreatedAtDesc()
                .stream().map(OrderResponse::from).toList();
    }

    public OrderResponse getOrder(Long id) {
        return orderRepository.findById(id)
                .map(OrderResponse::from)
                .orElse(null);
    }
}
