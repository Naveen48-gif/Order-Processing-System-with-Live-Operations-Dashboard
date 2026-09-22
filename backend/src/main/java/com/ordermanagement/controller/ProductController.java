package com.ordermanagement.controller;

import com.ordermanagement.dto.CreateProductRequest;
import com.ordermanagement.dto.InventoryResponse;
import com.ordermanagement.dto.ProductResponse;
import com.ordermanagement.dto.RestockRequest;
import com.ordermanagement.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Catalogue API: products and their live stock levels. */
@RestController
@RequestMapping("/api")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping("/products")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(request));
    }

    @GetMapping("/products")
    public List<ProductResponse> listProducts() {
        return productService.listProducts();
    }

    @GetMapping("/inventory")
    public List<InventoryResponse> listInventory() {
        return productService.listInventory();
    }

    @GetMapping("/inventory/{productId}")
    public InventoryResponse getInventory(@PathVariable Long productId) {
        return productService.getInventory(productId);
    }

    /** Adds stock to an existing product (e.g. topping up after a "duplicate name" 409 on create). */
    @PostMapping("/inventory/{productId}/restock")
    public InventoryResponse restock(@PathVariable Long productId, @Valid @RequestBody RestockRequest request) {
        return productService.restock(productId, request.quantity());
    }
}
