package com.ordermanagement.repository;

import com.ordermanagement.model.DeadLetterOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeadLetterOrderRepository extends JpaRepository<DeadLetterOrder, Long> {

    List<DeadLetterOrder> findAllByOrderByFailedAtDesc();
}
