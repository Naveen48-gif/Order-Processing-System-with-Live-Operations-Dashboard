package com.ordermanagement.repository;

import com.ordermanagement.entity.OrderStatus;

/**
 * Read-model projection for the dashboard summary: one row per status with its order count.
 *
 * <p>Alias-backed interface projection keeps the aggregate query cheap (a single grouped
 * {@code SELECT}) instead of loading every order into memory to count it in Java.
 */
public interface StatusCountView {

    OrderStatus getStatus();

    long getTotal();
}
