package com.ordermanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Operational (non-business) switches, all off by default.
 *
 * <p>Fault injection exists so the <em>failure</em> behaviour of the pipeline can be demonstrated and
 * asserted on demand instead of only after a real incident: it makes the next few reservations for one
 * product fail with a transient fault, which walks an order through the real retry ladder
 * (PROCESSING -> FAILED xN -> DLQ) and then back to COMPLETED through the DLQ replay action.
 *
 * <p>It is gated on purpose. The switch defaults to {@code false}, the endpoint is not even registered
 * while it is off, and enabling it requires an explicit configuration change - so a production
 * deployment cannot be talked into injecting failures by calling an API.
 */
@ConfigurationProperties(prefix = "order.ops.fault-injection")
public record OpsProperties(

        @DefaultValue("false")
        boolean enabled
) {
}