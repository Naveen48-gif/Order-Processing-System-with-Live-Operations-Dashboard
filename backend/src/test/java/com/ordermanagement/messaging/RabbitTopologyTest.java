package com.ordermanagement.messaging;

import com.ordermanagement.config.MessagingProperties;
import com.ordermanagement.config.OrderProcessingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the broker topology down without needing a broker.
 *
 * <p>This is the test that makes "the DLQ actually exists" a checkable claim rather than a hope: if a
 * future refactor drops the dead-letter arguments or miscomputes a retry tier's TTL, it fails here in
 * milliseconds instead of in production at the moment an order needs recovering.
 */
class RabbitTopologyTest {

    private static final long BACKOFF_MS = 300L;
    private static final int MAX_ATTEMPTS = 3;

    private final MessagingProperties messaging = new MessagingProperties();
    private final OrderProcessingProperties processing = new OrderProcessingProperties();

    private Declarables topology;

    @BeforeEach
    void setUp() {
        processing.setMaxRetryAttempts(MAX_ATTEMPTS);
        processing.setRetryBackoffMs(BACKOFF_MS);
        topology = new RabbitTopology().orderMessagingTopology(messaging, processing);
    }

    @Test
    @DisplayName("the work queue is durable and dead-letters into the DLQ instead of losing messages")
    void workQueueRoutesFailuresToTheDeadLetterQueue() {
        Queue workQueue = queue(messaging.getQueue());

        assertThat(workQueue.isDurable()).as("a restart must not drop accepted work").isTrue();
        assertThat(workQueue.getArguments())
                .containsEntry("x-dead-letter-exchange", messaging.getDlx())
                .containsEntry("x-dead-letter-routing-key", messaging.getDlqRoutingKey());
    }

    @Test
    @DisplayName("the DLQ never expires: a dead letter is evidence until a human has looked at it")
    void deadLetterQueueKeepsItsEvidence() {
        Queue deadLetterQueue = queue(messaging.getDlq());

        assertThat(deadLetterQueue.isDurable()).isTrue();
        assertThat(deadLetterQueue.getArguments())
                .as("no x-expires/TTL, otherwise RabbitMQ would delete the queue and its evidence")
                .doesNotContainKeys("x-expires", "x-message-ttl");
    }

    @Test
    @DisplayName("work and dead-letter paths are both bound to their exchange with the configured keys")
    void bindingsUseTheConfiguredRoutingKeys() {
        List<Binding> bindings = topology.getDeclarablesByType(Binding.class);

        assertThat(bindings).anySatisfy(binding -> {
            assertThat(binding.getDestination()).isEqualTo(messaging.getQueue());
            assertThat(binding.getExchange()).isEqualTo(messaging.getExchange());
            assertThat(binding.getRoutingKey()).isEqualTo(messaging.getRoutingKey());
        });
        assertThat(bindings).anySatisfy(binding -> {
            assertThat(binding.getDestination()).isEqualTo(messaging.getDlq());
            assertThat(binding.getExchange()).isEqualTo(messaging.getDlx());
            assertThat(binding.getRoutingKey()).isEqualTo(messaging.getDlqRoutingKey());
        });
    }

    @Test
    @DisplayName("one delay tier per attempt, each with backoff x attempt as its TTL, dead-lettering back to work")
    void declaresOneRetryTierPerAttemptWithEscalatingDelay() {
        List<Queue> retryTiers = topology.getDeclarablesByType(Queue.class).stream()
                .filter(declared -> declared.getName().contains(RabbitTopology.RETRY_QUEUE_INFIX))
                .toList();

        assertThat(retryTiers).hasSize(MAX_ATTEMPTS);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Queue tier = queue(RabbitTopology.retryQueueName(messaging, attempt));
            assertThat(tier.getArguments())
                    .as("attempt %d waits backoff x attempt before being redelivered", attempt)
                    .containsEntry("x-message-ttl", (int) (BACKOFF_MS * attempt))
                    .containsEntry("x-dead-letter-exchange", messaging.getExchange())
                    .containsEntry("x-dead-letter-routing-key", messaging.getRoutingKey());
        }
    }

    @Test
    @DisplayName("retry tiers hang off their own exchange so they can never be consumed as work")
    void retryTiersAreBoundToTheRetryExchange() {
        DirectExchange retryExchange = topology.getDeclarablesByType(DirectExchange.class).stream()
                .filter(exchange -> exchange.getName().equals(RabbitTopology.retryExchangeName(messaging)))
                .findFirst()
                .orElseThrow();

        assertThat(retryExchange.isDurable()).isTrue();
        assertThat(topology.getDeclarablesByType(Binding.class))
                .filteredOn(binding -> binding.getExchange().equals(RabbitTopology.retryExchangeName(messaging)))
                .as("every tier is reachable by its own name as the routing key")
                .hasSize(MAX_ATTEMPTS)
                .allSatisfy(binding -> assertThat(binding.getDestination()).isEqualTo(binding.getRoutingKey()));
    }

    @Test
    @DisplayName("the dead-letter exchange is direct, so a dead letter cannot fan out to other queues")
    void deadLetterExchangeIsDirect() {
        assertThat(topology.getDeclarablesByType(DirectExchange.class))
                .anySatisfy(exchange -> assertThat(exchange.getName()).isEqualTo(messaging.getDlx()));
        assertThat(topology.getDeclarablesByType(TopicExchange.class))
                .as("only the work exchange is a topic exchange")
                .hasSize(1);
    }

    private Queue queue(String name) {
        return topology.getDeclarablesByType(Queue.class).stream()
                .filter(queue -> queue.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no queue declared with name " + name));
    }
}