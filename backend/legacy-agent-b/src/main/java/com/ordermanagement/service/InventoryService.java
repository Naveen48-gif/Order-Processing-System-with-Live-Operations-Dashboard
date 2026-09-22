package com.ordermanagement.service;

import com.ordermanagement.dto.ProductResponse;
import com.ordermanagement.exception.InventoryLockException;
import com.ordermanagement.exception.OutOfStockException;
import com.ordermanagement.exception.ProductNotFoundException;
import com.ordermanagement.model.Product;
import com.ordermanagement.repository.ProductRepository;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private final ProductRepository productRepository;
    private final NotificationService notificationService;

    public InventoryService(ProductRepository productRepository, NotificationService notificationService) {
        this.productRepository = productRepository;
        this.notificationService = notificationService;
    }

    /**
     * Reserves (decrements) stock for a product inside a pessimistic row lock so concurrent
     * workers on the same product serialize at the database and quantity can never go negative.
     * Transient lock contention is retried a bounded number of times; out-of-stock is a business
     * failure and is never retried.
     */
    @Retryable(
            retryFor = TransientDataAccessException.class,
            maxAttemptsExpression = "${order.processing.max-retry-attempts:3}",
            backoff = @Backoff(delayExpression = "${order.processing.retry-backoff-ms:300}", multiplier = 2)
    )
    @Transactional
    public Product reserveStock(Long productId, int quantity) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        if (product.getQuantity() < quantity) {
            throw new OutOfStockException(
                    "Insufficient stock for product " + productId + ": requested " + quantity
                            + ", available " + product.getQuantity());
        }

        product.setQuantity(product.getQuantity() - quantity);
        Product saved = productRepository.save(product);
        notificationService.broadcastInventoryUpdate(ProductResponse.from(saved));
        return saved;
    }

    @Recover
    public Product recover(TransientDataAccessException ex, Long productId, int quantity) {
        throw new InventoryLockException(
                "Stock reservation for product " + productId + " failed after bounded retries", ex);
    }
}
