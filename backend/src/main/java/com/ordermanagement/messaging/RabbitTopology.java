package com.ordermanagement.messaging;

import com.ordermanagement.config.MessagingProperties;
import com.ordermanagement.config.OrderProcessingProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Declares the broker topology that carries orders through the pipeline.
 *
 * <pre>
 *   publisher ──► order.exchange (topic)
 *                      │ routing key: order.routing-key
 *                      ▼
 *                 order.queue ──────────► worker (manual ack)
 *                      │ x-dead-letter-exchange (nack, overflow, expiry)
 *                      ▼
 *                 order.dlx ──► order.dlq           (parked for a human)
 *
 *   retry:  order.exchange.retry ──► order.queue.retry.{1..N}
 *                                        │ x-message-ttl = backoff x attempt
 *                                        │ x-dead-letter-exchange = order.exchange
 *                                        ▼
 *                                   order.queue      (redelivered to a worker)
 * </pre>
 *
 * <h2>Why one delay queue per attempt instead of a delayed-message exchange</h2>
 * A delayed exchange needs the {@code rabbitmq_delayed_message_exchange} plugin, which is not available on
 * every managed broker - so a topology that depends on it simply does not deploy on those platforms. The
 * alternative used here is the classic <em>tiered delay queues</em> pattern: one durable queue per retry
 * attempt with a fixed {@code x-message-ttl} and a dead-letter route back to the work exchange. It needs
 * nothing but stock RabbitMQ, it keeps the backoff ladder (backoff x attempt) exactly as the in-process
 * transport applies it, and each tier's queue depth is itself an operational signal - "everything is stuck
 * on retry 3" is visible in the management UI at a glance.
 *
 * <p>A fixed TTL inside a tier also avoids the head-of-line problem that per-message TTLs have: every
 * message in a queue expires in the same order it arrived, so a long-TTL message can never hold back a
 * short-TTL one.
 *
 * <p>Every name comes from {@link MessagingProperties}; nothing is hardcoded here.
 */
@Configuration
@ConditionalOnProperty(prefix = "order.messaging", name = "transport", havingValue = "rabbitmq", matchIfMissing = true)
public class RabbitTopology {

    /** Suffix of the exchange the retry tiers are bound to (derived from the configured exchange name). */
    public static final String RETRY_EXCHANGE_SUFFIX = ".retry";

    /** Infix of a retry tier queue name (derived from the configured queue name). */
    public static final String RETRY_QUEUE_INFIX = ".retry.";

    @Bean
    MessageConverter orderMessageConverter() {
        // JSON on the wire rather than Java serialization: it is inspectable in the management UI and it
        // cannot execute code on deserialization.
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    Declarables orderMessagingTopology(MessagingProperties messaging, OrderProcessingProperties processing) {
        List<Declarable> declarations = new ArrayList<>();

        // ---- Work path ----
        TopicExchange workExchange = new TopicExchange(messaging.getExchange(), true, false);
        Queue workQueue = QueueBuilder.durable(messaging.getQueue())
                // A message that is rejected without requeue, or left unacked when a channel dies, is
                // moved here instead of being lost or redelivered in a hot loop.
                .deadLetterExchange(messaging.getDlx())
                .deadLetterRoutingKey(messaging.getDlqRoutingKey())
                .build();

        // ---- Failure path ----
        DirectExchange deadLetterExchange = new DirectExchange(messaging.getDlx(), true, false);
        // No TTL and no x-expires on the DLQ: a dead letter must survive until a human has looked at it,
        // and x-expires would delete the queue (and its evidence) as soon as it went quiet.
        Queue deadLetterQueue = QueueBuilder.durable(messaging.getDlq()).build();

        declarations.add(workExchange);
        declarations.add(workQueue);
        declarations.add(BindingBuilder.bind(workQueue).to(workExchange).with(messaging.getRoutingKey()));
        declarations.add(deadLetterExchange);
        declarations.add(deadLetterQueue);
        declarations.add(BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(messaging.getDlqRoutingKey()));

        // ---- Bounded retry ladder: one delay tier per attempt ----
        DirectExchange retryExchange = new DirectExchange(retryExchangeName(messaging), true, false);
        declarations.add(retryExchange);

        for (int attempt = 1; attempt <= processing.getMaxRetryAttempts(); attempt++) {
            String retryQueueName = retryQueueName(messaging, attempt);
            Queue retryTier = QueueBuilder.durable(retryQueueName)
                    .ttl((int) delayForAttempt(processing, attempt))
                    .deadLetterExchange(messaging.getExchange())
                    .deadLetterRoutingKey(messaging.getRoutingKey())
                    .build();
            declarations.add(retryTier);
            declarations.add(BindingBuilder.bind(retryTier).to(retryExchange).with(retryQueueName));
        }

        return new Declarables(declarations);
    }

    /** Exchange the bounded-retry tiers are published to. */
    public static String retryExchangeName(MessagingProperties messaging) {
        return messaging.getExchange() + RETRY_EXCHANGE_SUFFIX;
    }

    /** Queue name of the delay tier for a given attempt (1-based). */
    public static String retryQueueName(MessagingProperties messaging, int attempt) {
        return messaging.getQueue() + RETRY_QUEUE_INFIX + attempt;
    }

    /** Delay before the given attempt is retried - identical to the in-process backoff formula. */
    public static long delayForAttempt(OrderProcessingProperties processing, int attempt) {
        return processing.getRetryBackoffMs() * Math.max(1, attempt);
    }
}