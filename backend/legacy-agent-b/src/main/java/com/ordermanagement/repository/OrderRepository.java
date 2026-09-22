package com.ordermanagement.repository;

import com.ordermanagement.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findTop200ByOrderByCreatedAtDesc();
}
