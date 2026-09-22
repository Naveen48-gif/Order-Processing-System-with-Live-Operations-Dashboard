package com.ordermanagement.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tuning for the bounded worker pool that processes orders concurrently, plus the retry budget.
 *
 * <p>Every value is bound from {@code order.processing.*} and can be overridden by an environment
 * variable, so capacity can be tuned per environment (local laptop vs. production) without a rebuild.
 * The pool is deliberately bounded on both axes: a fixed worker count and a bounded queue. An
 * unbounded queue would hide overload and an unbounded thread count would exhaust the host, so
 * saturation is instead surfaced as a rejected submission.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "order.processing")
public class OrderProcessingProperties {

    /** Worker threads kept alive in the pool. */
    @Min(1)
    private int corePoolSize = 10;

    /** Hard ceiling on worker threads; the pool never grows beyond this. */
    @Min(1)
    private int maxPoolSize = 10;

    /** Capacity of the bounded work queue; submissions beyond it are rejected instead of buffered forever. */
    @Min(1)
    private int queueCapacity = 100;

    /** Idle seconds before non-core threads are reclaimed. */
    @Min(0)
    private int keepAliveSeconds = 60;

    /** Seconds to let in-flight orders finish during a graceful shutdown. */
    @Min(0)
    private int awaitTerminationSeconds = 30;

    /** Retries allowed for transient technical failures before the order is moved to the DLQ. */
    @Min(0)
    private int maxRetryAttempts = 3;

    /** Base backoff between technical retries. */
    @Positive
    private long retryBackoffMs = 300;

    @AssertTrue(message = "order.processing.max-pool-size must be greater than or equal to core-pool-size")
    public boolean isPoolSizeConsistent() {
        return maxPoolSize >= corePoolSize;
    }
}
