package com.ordermanagement.messaging;

import com.ordermanagement.config.MessagingProperties;
import com.ordermanagement.config.OrderProcessingProperties;
import com.ordermanagement.exception.OrderProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Hands orders to the real broker (the canonical transport).
 *
 * <p>Delivery guarantees: queues and exchanges are declared durable, messages are published as JSON, and
 * correlated publisher confirms are enabled in configuration - so the transport learns from the broker
 * itself whether a message was actually taken, instead of assuming that handing bytes to the socket was
 * enough.
 *
 * <p>Failure semantics mirror the in-process transport exactly, because both drive the same processor
 * bean: a publish failure during intake surfaces as {@code 503 TRANSIENT_FAILURE} (the order is already
 * persisted as PENDING, so replaying the request with the same idempotency key picks it up), and a
 * transient processing failure is re-published into the delay tier that matches its configured backoff.
 */
@Component
@ConditionalOnProperty(prefix = "order.messaging", name = "transport", havingValue = "rabbitmq", matchIfMissing = true)
public class RabbitOrderTransport implements OrderTransport {

    private static final Logger log = LoggerFactory.getLogger(RabbitOrderTransport.class);

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties messaging;
    private final OrderProcessingProperties processing;

    public RabbitOrderTransport(RabbitTemplate rabbitTemplate,
                                MessagingProperties messaging,
                                OrderProcessingProperties processing) {
        this.rabbitTemplate = rabbitTemplate;
        this.messaging = messaging;
        this.processing = processing;

        // A nack from the broker (queue missing, disk alarm, quota) must not pass silently: without this
        // callback a confirmed publish failure would look exactly like success in the logs.
        rabbitTemplate.setConfirmCallback((correlationData, acknowledged, cause) -> {
            if (!acknowledged) {
                log.error("ORDER_PUBLISH_NACK correlationId={} cause={}",
                        correlationData == null ? "unknown" : correlationData.getId(), cause);
            }
        });
    }

    @Override
    public void submit(Long orderId) {
        publish(messaging.getExchange(), messaging.getRoutingKey(), OrderWorkMessage.forFirstAttempt(orderId));
        log.info("ORDER_ENQUEUED orderId={} transport=rabbitmq exchange={} routingKey={}",
                orderId, messaging.getExchange(), messaging.getRoutingKey());
    }

    @Override
    public void submitForRetry(Long orderId, long delayMillis) {
        int attempt = attemptForDelay(delayMillis);
        String retryQueue = RabbitTopology.retryQueueName(messaging, attempt);
        publish(RabbitTopology.retryExchangeName(messaging), retryQueue, OrderWorkMessage.forAttempt(orderId, attempt));
        log.info("ORDER_RETRY_SCHEDULED orderId={} attempt={} delayMs={} retryQueue={}",
                orderId, attempt, delayMillis, retryQueue);
    }

    /**
     * Maps a backoff in milliseconds onto the delay tier with that TTL.
     *
     * <p>The processor computes its backoff as {@code retryBackoffMs x attempt}, so the tier index is
     * simply that ratio. A delay beyond the configured ladder is clamped to the longest tier rather than
     * silently dropped: the order still gets its retry, just at the maximum configured delay.
     */
    int attemptForDelay(long delayMillis) {
        long base = Math.max(1L, processing.getRetryBackoffMs());
        int attempt = (int) Math.max(1L, Math.round((double) delayMillis / base));
        if (attempt > processing.getMaxRetryAttempts()) {
            log.warn("ORDER_RETRY_DELAY_CLAMPED delayMs={} requestedAttempt={} maxAttempts={}",
                    delayMillis, attempt, processing.getMaxRetryAttempts());
            return Math.max(1, processing.getMaxRetryAttempts());
        }
        return attempt;
    }

    private void publish(String exchange, String routingKey, OrderWorkMessage message) {
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, message);
        } catch (AmqpException brokerFailure) {
            // The order row is already persisted, so nothing is lost: the caller is told to retry and the
            // dashboard shows the order as PENDING rather than pretending the hand-off succeeded.
            throw new OrderProcessingException(message.orderId(),
                    "order could not be handed to the broker (exchange=%s, routingKey=%s)"
                            .formatted(exchange, routingKey), brokerFailure);
        }
    }
}