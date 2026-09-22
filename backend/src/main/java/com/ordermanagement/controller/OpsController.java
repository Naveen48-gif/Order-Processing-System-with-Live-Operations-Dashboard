package com.ordermanagement.controller;

import com.ordermanagement.dto.ArmFaultRequest;
import com.ordermanagement.dto.FaultInjectionStatus;
import com.ordermanagement.service.FaultInjector;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational failure drill: arm a bounded number of injected transient faults for one product.
 *
 * <p>The whole controller is conditional on {@code order.ops.fault-injection.enabled=true}, so when the
 * switch is off the routes do not exist and the dashboard's capability probe receives a 404 and hides
 * the controls. That is the difference between a feature that is <em>off</em> and a feature that is
 * <em>half-present</em>: nothing here can be reached by accident.
 */
@RestController
@RequestMapping("/api/ops/fault-injection")
@ConditionalOnProperty(prefix = "order.ops.fault-injection", name = "enabled", havingValue = "true")
public class OpsController {

    private final FaultInjector faultInjector;

    public OpsController(FaultInjector faultInjector) {
        this.faultInjector = faultInjector;
    }

    /** Capability probe plus the currently armed faults. */
    @GetMapping
    public FaultInjectionStatus status() {
        return currentStatus();
    }

    /** Arms faults. {@code 202 Accepted}: the effect is asynchronous - it lands on the next orders. */
    @PostMapping
    public ResponseEntity<FaultInjectionStatus> arm(@Valid @RequestBody ArmFaultRequest request) {
        faultInjector.arm(request.productId(), request.occurrences());
        return ResponseEntity.accepted().body(currentStatus());
    }

    /** Disarms a product, returning the pipeline to normal behaviour immediately. */
    @DeleteMapping("/{productId}")
    public FaultInjectionStatus clear(@PathVariable Long productId) {
        faultInjector.clear(productId);
        return currentStatus();
    }

    private FaultInjectionStatus currentStatus() {
        return new FaultInjectionStatus(true, faultInjector.armedFaults());
    }
}