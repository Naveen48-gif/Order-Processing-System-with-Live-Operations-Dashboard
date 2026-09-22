package com.ordermanagement.messaging;

import com.ordermanagement.config.MessagingProperties;
import com.ordermanagement.config.OrderProcessingProperties;
import com.ordermanagement.exception.TransientProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Publisher behaviour with the broker mocked out: which exchange and routing key each hand-off uses, and
 * what the API caller sees when the broker refuses the message.
 */
class RabbitOrderTransportTest {

    private static final long ORDER_ID = 42L;
    private static final long BACKOFF_MS = 300L;

    private final MessagingProperties messaging = new MessagingProperties();
    private final OrderProcessingProperties processing = new OrderProcessingProperties();

    private RabbitTemplate rabbitTemplate;
    private RabbitOrderTransport transport;

    @BeforeEach
    void setUp() {
        processing.setMaxRetryAttempts(3);
        processing.setRetryBackoffMs(BACKOFF_MS);
        rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        transport = new RabbitOrderTransport(rabbitTemplate, messaging, processing);
    }

    @Test
    @DisplayName("a new order is published to the work exchange with the configured routing key")
    void publishesNewOrdersToTheWorkQueue() {
        transport.submit(ORDER_ID);

        assertThat(publishedPayload())
                .as("attempt 0 = first delivery")
                .isEqualTo(new OrderWorkMessage(ORDER_ID, 0));
        verify(rabbitTemplate).convertAndSend(messaging.getExchange(), messaging.getRoutingKey(),
                new OrderWorkMessage(ORDER_ID, 0));
    }

    @Test
    @DisplayName("each retry lands in the delay tier whose TTL matches its backoff")
    void routesRetriesToTheMatchingDelayTier() {
        transport.submitForRetry(ORDER_ID, BACKOFF_MS * 2);

        verify(rabbitTemplate).convertAndSend(
                RabbitTopology.retryExchangeName(messaging),
                RabbitTopology.retryQueueName(messaging, 2),
                OrderWorkMessage.forAttempt(ORDER_ID, 2));
    }

    @Test
    @DisplayName("a delay beyond the configured ladder is clamped to the longest tier instead of being dropped")
    void clampsDelaysBeyondTheLadder() {
        transport.submitForRetry(ORDER_ID, BACKOFF_MS * 99);

        verify(rabbitTemplate).convertAndSend(
                RabbitTopology.retryExchangeName(messaging),
                RabbitTopology.retryQueueName(messaging, processing.getMaxRetryAttempts()),
                OrderWorkMessage.forAttempt(ORDER_ID, processing.getMaxRetryAttempts()));
    }

    @Test
    @DisplayName("a broker outage is reported as a transient failure (503), not as a bug (500)")
    void brokerFailureSurfacesAsTransient() {
        // convertAndSend returns void, so the failure is stubbed with doThrow rather than when(...).
        // any(Object.class) (not a bare any()) picks the (String, String, Object) overload explicitly.
        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        assertThatThrownBy(() -> transport.submit(ORDER_ID))
                .isInstanceOf(TransientProcessingException.class)
                .hasMessageContaining("broker");
    }

    private Object publishedPayload() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(messaging.getExchange()), eq(messaging.getRoutingKey()), payload.capture());
        return payload.getValue();
    }
}