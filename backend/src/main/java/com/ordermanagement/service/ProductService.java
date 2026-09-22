package com.ordermanagement.service;

import com.ordermanagement.dto.CreateProductRequest;
import com.ordermanagement.dto.InventoryResponse;
import com.ordermanagement.dto.ProductResponse;
import com.ordermanagement.entity.Inventory;
import com.ordermanagement.entity.Product;
import com.ordermanagement.event.InventoryChangedEvent;
import com.ordermanagement.exception.DuplicateProductException;
import com.ordermanagement.exception.InventoryNotFoundException;
import com.ordermanagement.exception.ProductNotFoundException;
import com.ordermanagement.repository.InventoryRepository;
import com.ordermanagement.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Catalogue and stock read model.
 *
 * <p>Read paths intentionally use the unlocked queries: locking is reserved for the reservation
 * transaction, because taking a write lock just to render a dashboard would serialise the dashboard
 * against order processing for no benefit.
 */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ProductService(ProductRepository productRepository,
                          InventoryRepository inventoryRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> listProducts() {
        return productRepository.findAllByOrderByNameAsc().stream()
                .map(ProductResponse::from)
                .toList();
    }

    /**
     * Creates a product together with its inventory row.
     *
     * <p>Both rows are written in one transaction: a product without stock would make every order for
     * it fail with {@code INVENTORY_NOT_FOUND}, which is a worse operator experience than a clean
     * conflict error here.
     */
    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {
        if (productRepository.existsByNameIgnoreCase(request.name())) {
            throw new DuplicateProductException(request.name());
        }

        Product product = productRepository.save(new Product(request.name(), request.price()));
        inventoryRepository.save(new Inventory(product, request.initialQuantityOrDefault()));

        log.info("PRODUCT_CREATED productId={} name={} initialQuantity={}",
                product.getId(), product.getName(), request.initialQuantityOrDefault());
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public List<InventoryResponse> listInventory() {
        return inventoryRepository.findAllWithProduct().stream()
                .map(InventoryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public InventoryResponse getInventory(Long productId) {
        return inventoryRepository.findByProductId(productId)
                .map(InventoryResponse::from)
                .orElseThrow(() -> {
                    // Distinguish "no such product" from "product exists but has no stock row".
                    if (!productRepository.existsById(productId)) {
                        return new ProductNotFoundException(productId);
                    }
                    return new InventoryNotFoundException(productId);
                });
    }

    @Transactional(readOnly = true)
    public Product requireProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    /**
     * Adds stock to an existing product. Reuses the same pessimistic row lock as the order
     * reservation path, so a restock can never race with a concurrent decrement and lose an update.
     */
    @Transactional
    public InventoryResponse restock(Long productId, int quantityToAdd) {
        Inventory inventory = inventoryRepository.findForUpdateByProductId(productId)
                .orElseThrow(() -> {
                    if (!productRepository.existsById(productId)) {
                        return new ProductNotFoundException(productId);
                    }
                    return new InventoryNotFoundException(productId);
                });

        inventory.restock(quantityToAdd);
        log.info("PRODUCT_RESTOCKED productId={} added={} newQuantity={}",
                productId, quantityToAdd, inventory.getQuantity());

        InventoryResponse response = InventoryResponse.from(inventory);
        eventPublisher.publishEvent(new InventoryChangedEvent(response));
        return response;
    }
}
