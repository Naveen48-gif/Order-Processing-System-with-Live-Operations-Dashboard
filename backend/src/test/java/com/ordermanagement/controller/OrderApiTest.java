package com.ordermanagement.controller;

import com.ordermanagement.dto.SubmitOrderRequest;
import com.ordermanagement.entity.Inventory;
import com.ordermanagement.entity.OrderStatus;
import com.ordermanagement.entity.Product;
import com.ordermanagement.repository.InventoryRepository;
import com.ordermanagement.repository.OrderRepository;
import com.ordermanagement.repository.ProductRepository;
import com.ordermanagement.service.OrderService;
import com.ordermanagement.service.OrderSubmissionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST contract tests for the frozen API surface (AGENT.md §4) and the error shape (§6).
 *
 * <p>Runs against the real application context (validation, transaction management, the bounded worker
 * pool and the exception handler are all exercised), with MockMvc instead of an HTTP port.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

    private Product givenProduct(String name, int stock) {
        Product product = productRepository.save(new Product(name, new BigDecimal("149.99")));
        inventoryRepository.save(new Inventory(product, stock));
        return product;
    }

    @Test
    @DisplayName("POST /api/products creates a product with its stock and rejects a duplicate name")
    void createsAndProtectsProducts() throws Exception {
        String body = """
                {"name":"API Test Cable","price":12.50,"initialQuantity":7}
                """;

        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("API Test Cable"));

        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_PRODUCT"));

        assertThat(productRepository.findByNameIgnoreCase("API Test Cable")).isPresent();
    }

    @Test
    @DisplayName("POST /api/products validates the payload and reports field-level details")
    void validatesProductPayload() throws Exception {
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","price":-1.00}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    @DisplayName("GET /api/inventory exposes stock plus the operational stock signal")
    void exposesInventory() throws Exception {
        Product product = givenProduct("API Test Monitor", 2);

        mockMvc.perform(get("/api/inventory/{productId}", product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(product.getId()))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.stockStatus").value("LOW_STOCK"));

        mockMvc.perform(get("/api/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /api/inventory/{productId} answers 404 with a stable error code for an unknown product")
    void reportsUnknownInventoryProduct() throws Exception {
        mockMvc.perform(get("/api/inventory/{productId}", 987654L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/inventory/987654"));
    }

    @Test
    @DisplayName("an unmapped path answers 404 with a stable code instead of a misleading 500")
    void answersNotFoundForUnknownEndpoints() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("POST /api/orders accepts an order, and GET /api/orders/{id} shows it settle asynchronously")
    void acceptsOrderAndShowsAsyncSettlement() throws Exception {
        Product product = givenProduct("API Test Laptop", 5);

        String response = mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"quantity":2}
                                """.formatted(product.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.productId").value(product.getId()))
                .andExpect(jsonPath("$.quantity").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long orderId = extractId(response);
        awaitOrderStatus(orderId, OrderStatus.COMPLETED);

        mockMvc.perform(get("/api/orders/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(inventoryRepository.findByProductId(product.getId()).orElseThrow().getQuantity())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("an order with no stock settles as OUT_OF_STOCK over the API, not as a failed request")
    void reportsOutOfStockAsOrderStatus() throws Exception {
        Product product = givenProduct("API Test Sold Out", 0);

        String response = mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"quantity":1}
                                """.formatted(product.getId())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long orderId = extractId(response);
        awaitOrderStatus(orderId, OrderStatus.OUT_OF_STOCK);

        mockMvc.perform(get("/api/orders/status/{status}", "OUT_OF_STOCK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        assertThat(orderRepository.countByProduct_IdAndStatus(product.getId(), OrderStatus.DLQ))
                .as("a business rejection must not be dead-lettered")
                .isZero();
    }

    @Test
    @DisplayName("the idempotency key turns a retried submission into a 200 replay instead of a second order")
    void replaysIdempotentSubmissions() {
        Product product = givenProduct("API Test Idempotency", 3);
        SubmitOrderRequest request = new SubmitOrderRequest(product.getId(), 1, "api-idem-1");

        OrderSubmissionResult first = orderService.submitOrder(request);
        OrderSubmissionResult second = orderService.submitOrder(request);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.order().id()).isEqualTo(first.order().id());
        assertThat(orderRepository.countByProduct_Id(product.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("order validation and not-found cases produce meaningful status codes")
    void reportsValidationAndNotFound() throws Exception {
        Product product = givenProduct("API Test Validation", 1);

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"quantity":0}
                                """.formatted(product.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0]").value("quantity: quantity must be greater than zero"));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":987654,"quantity":1}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"));

        mockMvc.perform(get("/api/orders/{orderId}", 987654L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"));

        mockMvc.perform(get("/api/orders").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    private static long extractId(String json) {
        var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(json);
        assertThat(matcher.find()).as("response body must contain an id: %s", json).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    /** The settlement is asynchronous by design, so the test waits for the persisted status. */
    private void awaitOrderStatus(long orderId, OrderStatus expected) {
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> orderRepository.findById(orderId)
                        .map(order -> order.getStatus() == expected)
                        .orElse(false));
    }
}
