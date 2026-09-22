package com.ordermanagement.config;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Names of the broker topology used to move orders through the pipeline.
 *
 * <p>Nothing here is hardcoded in the messaging classes: exchange, queue, routing key and the
 * dead-letter exchange/queue are configuration, so the same artifact can run against a local
 * broker, a shared broker, or a managed broker with environment-specific naming.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "order.messaging")
public class MessagingProperties {

    /**
     * How orders reach the bounded worker pipeline.
     *
     * <p>{@code RABBITMQ} is the canonical production path: durable queue, dead-letter exchange and
     * dead-letter queue. {@code IN_PROCESS} is the same processor pipeline driven by the local
     * thread pool, used only on machines where no broker binary can run. Both paths call the exact
     * same transactional service, so business rules can never diverge between them.
     */
    @NotNull
    private Transport transport = Transport.RABBITMQ;

    private String exchange = "order.exchange";

    private String queue = "order.queue";

    private String routingKey = "order.routing-key";

    private String dlx = "order.dlx";

    private String dlq = "order.dlq";

    private String dlqRoutingKey = "order.dlq.routing-key";

    public enum Transport {
        RABBITMQ,
        IN_PROCESS
    }
}
