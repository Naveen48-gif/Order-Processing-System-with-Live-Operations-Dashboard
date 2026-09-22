package com.ordermanagement.dto;

import com.ordermanagement.entity.OrderStatus;

import java.util.List;
import java.util.Map;

/** Single read model behind the dashboard's summary cards (frozen in AGENT.md §4/§5). */
public record DashboardSummaryResponse(
        long totalOrders,
        long pending,
        long processing,
        long completed,
        long outOfStock,
        long failed,
        long dlq,
        List<InventoryResponse> inventory
) {

    public static DashboardSummaryResponse of(Map<OrderStatus, Long> countsByStatus, List<InventoryResponse> inventory) {
        long pending = countsByStatus.getOrDefault(OrderStatus.PENDING, 0L);
        long processing = countsByStatus.getOrDefault(OrderStatus.PROCESSING, 0L);
        long completed = countsByStatus.getOrDefault(OrderStatus.COMPLETED, 0L);
        long outOfStock = countsByStatus.getOrDefault(OrderStatus.OUT_OF_STOCK, 0L);
        long failed = countsByStatus.getOrDefault(OrderStatus.FAILED, 0L);
        long dlq = countsByStatus.getOrDefault(OrderStatus.DLQ, 0L);
        long total = pending + processing + completed + outOfStock + failed + dlq;
        return new DashboardSummaryResponse(total, pending, processing, completed, outOfStock, failed, dlq, inventory);
    }
}
