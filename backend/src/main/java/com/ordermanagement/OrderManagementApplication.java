package com.ordermanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Entry point of the order processing service.
 *
 * <p>{@literal @EnableRetry} activates the bounded, declarative retry advice used for transient
 * (technical) failures only. Business failures such as insufficient stock are never retried.
 *
 * <p>{@literal @ConfigurationPropertiesScan} keeps every tuning knob (thread pool sizing, queue
 * capacity, retry budget, broker names) in typed configuration classes instead of scattered
 * {@code @Value} annotations.
 */
@EnableRetry
@ConfigurationPropertiesScan
@SpringBootApplication
public class OrderManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderManagementApplication.class, args);
    }
}

