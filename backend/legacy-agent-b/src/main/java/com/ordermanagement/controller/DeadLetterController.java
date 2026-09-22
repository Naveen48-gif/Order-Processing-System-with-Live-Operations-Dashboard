package com.ordermanagement.controller;

import com.ordermanagement.model.DeadLetterOrder;
import com.ordermanagement.repository.DeadLetterOrderRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dead-letter")
public class DeadLetterController {

    private final DeadLetterOrderRepository deadLetterOrderRepository;

    public DeadLetterController(DeadLetterOrderRepository deadLetterOrderRepository) {
        this.deadLetterOrderRepository = deadLetterOrderRepository;
    }

    @GetMapping
    public List<DeadLetterOrder> listDeadLetters() {
        return deadLetterOrderRepository.findAllByOrderByFailedAtDesc();
    }
}
