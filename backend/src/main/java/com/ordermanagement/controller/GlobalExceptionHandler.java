package com.ordermanagement.controller;

import com.ordermanagement.dto.ErrorResponse;
import com.ordermanagement.exception.DuplicateProductException;
import com.ordermanagement.exception.InventoryNotFoundException;
import com.ordermanagement.exception.InvalidOrderStateTransitionException;
import com.ordermanagement.exception.OrderNotFoundException;
import com.ordermanagement.exception.OrderNotInDeadLetterQueueException;
import com.ordermanagement.exception.OutOfStockException;
import com.ordermanagement.exception.PoolSaturatedException;
import com.ordermanagement.exception.ProductNotFoundException;
import com.ordermanagement.exception.TransientProcessingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Translates every failure into the single error shape frozen in AGENT.md §6.
 *
 * <p>Status-code policy:
 * <ul>
 *   <li>400 - malformed request or bean-validation failure (the client must change the request)</li>
 *   <li>404 - the referenced resource does not exist</li>
 *   <li>409 - the request conflicts with current state (duplicate product, illegal status move)</li>
 *   <li>422 - the request is well formed but the business said no (not enough stock)</li>
 *   <li>503 - the system temporarily cannot take the work (pool saturated, transient fault)</li>
 *   <li>500 - a bug; logged with a stack trace, never leaked to the client</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProductNotFound(ProductNotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(InventoryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleInventoryNotFound(InventoryNotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "INVENTORY_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFound(OrderNotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(OutOfStockException.class)
    public ResponseEntity<ErrorResponse> handleOutOfStock(OutOfStockException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, "OUT_OF_STOCK", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateProductException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateProduct(DuplicateProductException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "DUPLICATE_PRODUCT", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidOrderStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransition(InvalidOrderStateTransitionException ex,
                                                                HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", ex.getMessage(), request);
    }

    @ExceptionHandler(OrderNotInDeadLetterQueueException.class)
    public ResponseEntity<ErrorResponse> handleNotInDlq(OrderNotInDeadLetterQueueException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "ORDER_NOT_IN_DLQ", ex.getMessage(), request);
    }

    @ExceptionHandler(PoolSaturatedException.class)
    public ResponseEntity<ErrorResponse> handlePoolSaturated(PoolSaturatedException ex, HttpServletRequest request) {
        log.warn("POOL_SATURATED path={} message={}", request.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "POOL_SATURATED", ex.getMessage(), request);
    }

    /**
     * A URL that no controller maps must answer 404, not 500.
     *
     * <p>Without this handler Spring's {@code NoResourceFoundException} falls through to the catch-all
     * {@code Exception} handler below and an unknown path looks like a server bug - misleading for API
     * clients and it pollutes error logs with warnings.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoHandlerFound(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "No endpoint matches %s".formatted(request.getRequestURI()), request);
    }

    @ExceptionHandler(TransientProcessingException.class)
    public ResponseEntity<ErrorResponse> handleTransient(TransientProcessingException ex, HttpServletRequest request) {
        log.warn("TRANSIENT_FAILURE path={} message={}", request.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "TRANSIENT_FAILURE", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> "%s: %s".formatted(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(
                ErrorResponse.validation(HttpStatus.BAD_REQUEST.value(), "Request validation failed",
                        request.getRequestURI(), details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(violation -> "%s: %s".formatted(violation.getPropertyPath(), violation.getMessage()))
                .toList();
        return ResponseEntity.badRequest().body(
                ErrorResponse.validation(HttpStatus.BAD_REQUEST.value(), "Request validation failed",
                        request.getRequestURI(), details));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleUnreadableRequest(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(
                ErrorResponse.validation(HttpStatus.BAD_REQUEST.value(), "Malformed or unconvertible request",
                        request.getRequestURI(), List.of(String.valueOf(ex.getMessage()))));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        // A constraint such as inventory.quantity >= 0 fired: the request conflicts with stored state.
        log.warn("DATA_INTEGRITY_VIOLATION path={} cause={}", request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());
        return respond(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION",
                "The request conflicts with the current stored state", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("INTERNAL_ERROR path={}", request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Unexpected error, please check the server logs", request);
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String code, String message,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), code, message, request.getRequestURI()));
    }
}
