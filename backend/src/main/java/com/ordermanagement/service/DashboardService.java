package com.ordermanagement.service;

import com.ordermanagement.dto.DashboardSummaryResponse;
import com.ordermanagement.entity.OrderStatus;
import com.ordermanagement.repository.OrderRepository;
import com.ordermanagement.repository.StatusCountView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;

/**
 * Aggregates order-status counters and live inventory into the single dashboard read model.
 *
 * <p>One grouped query drives every counter instead of six separate {@code countByStatus} calls, and
 * the same builder is reused by the REST endpoint and the after-commit STOMP broadcast, so the two can
 * never drift apart.
 */
@Service
public class DashboardService {

    private final OrderRepository orderRepository;
    private final ProductService productService;

    public DashboardService(OrderRepository orderRepository, ProductService productService) {
        this.orderRepository = orderRepository;
        this.productService = productService;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse buildSummary() {
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        for (StatusCountView row : orderRepository.countGroupedByStatus()) {
            counts.put(row.getStatus(), row.getTotal());
        }
        return DashboardSummaryResponse.of(counts, productService.listInventory());
    }
}
