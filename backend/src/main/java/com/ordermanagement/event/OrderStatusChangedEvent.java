package com.ordermanagement.event;

import com.ordermanagement.dto.OrderResponse;

/** Published whenever an order's persisted status changes, for the after-commit STOMP broadcast. */
public record OrderStatusChangedEvent(OrderResponse order) {
}
