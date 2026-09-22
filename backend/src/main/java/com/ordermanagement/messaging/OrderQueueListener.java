package com.ordermanagement.messaging;

import com.ordermanagement.config.MessagingProperties;
import com.ordermanagement.entity.Order;
import com.ordermanagement.exception.OrderNotFoundException;
import com.ordermanagement.repository.OrderRepository;
import com.ordermanagement.service.OrderProcessingResult;
import com.ordermanagement.service.OrderProcessingService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * The broker-side worker: consumes one work item and drives the very same processor the in-process
 * transport uses, so business behaviour cannot diverge between the two transports.
 *
 * <h2>Concurrency bound</h2>
 * The container is configured (in {@code application.yml}) with {@code concurrency: 5},
 * {@code max-concurrency: 10} and {@code prefetch: 20}. Those three numbers are this transport's bounded
 * thread pool: the broker will never hand out more than ten concurrent deliveries, so a burst of orders
 * cannot spawn threads faster than the database can absorb them. The queue, not the JVM, is what buffers
 * the excess - which is exactly what makes overload survivable instead of fatal.
 *
 * <h2>Acknowledgement policy</h2>
 * Manual ack, deliberately:
 * <ul>
 *   <li><strong>Ack after processing.</strong> The message is only removed once the order has reached a
 *       persisted state, so a crash mid-processing redelivers the work rather than losing it.</li>
 *   <li><strong>Terminal failure is published, then acked.</strong> When the order's retry budget is
 *       exhausted the full reason is published to {@code order.dlx} as an
 *       {@link OrderDeadLetterMessage} - the envelope a human reads later - and the work item is acked,
 *       because keeping it would add nothing: the order row already says {@code DLQ}.</li>
 *   <li><strong>Unexpected failure is nacked without requeue.</strong> A poison message (bad payload, or
 *       the worker itself unable to run) follows the queue's {@code x-dead-letter-exchange} into the DLQ
 *       instead of being redelivered in a hot loop for ever.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "order.messaging", name = "transport", havingValue = "rabbitmq", matchIfMissing = true)
public class OrderQueueListener {

    private static final Logger log = LoggerFactory.getLogger(OrderQueueListener.class);

    private final OrderProcessingService orderProcessingService;
    private final RabbitTemplate rabbitTemplate;
    private final OrderRepository orderRepository;
    private final MessagingProperties messaging;

    public OrderQueueListener(OrderProcessingService orderProcessingService,
                              RabbitTemplate rabbitTemplate,
                              OrderRepository orderRepository,
                              MessagingProperties messaging) {
        this.orderProcessingService = orderProcessingService;
        this.rabbitTemplate = rabbitTemplate;
        this.orderRepository = orderRepository;
        this.messaging = messaging;
    }

    @RabbitListener(queues = "${order.messaging.queue}")
    public void onWorkMessage(OrderWorkMessage message,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            OrderProcessingResult result = orderProcessingService.processOrder(message.orderId());
            if (result.outcome() == OrderProcessingResult.Outcome.DEAD_LETTERED) {
                publishDeadLetterEnvelope(message.orderId(), result);
            }
            channel.basicAck(deliveryTag, false);

        } catch (RuntimeException unexpected) {
            log.error("ORDER_LISTENER_FAILURE orderId={} attempt={}", message.orderId(), message.attempt(), unexpected);
            // requeue=false -> the work queue's dead-letter route parks the envelope in order.dlq.
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * Publishes the readable dead-letter record. Best effort by design: the order row is already
     * {@code DLQ}, which is what the dashboard and the replay API work from, so an unavailable broker here
     * downgrades the evidence but never the state.
     */
    private void publishDeadLetterEnvelope(Long orderId, OrderProcessingResult result) {
        try {
            Order order = orderRepository.findByIdWithProduct(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
            rabbitTemplate.convertAndSend(
                    messaging.getDlx(),
                    messaging.getDlqRoutingKey(),
                    OrderDeadLetterMessage.of(orderId, order.getProduct().getId(), order.getQuantity(),
                            result.retryCount(), result.message()));
        } catch (RuntimeException ex) {
            log.warn("ORDER_DLQ_ENVELOPE_FAILED orderId={} reason={}", orderId, ex.getMessage());
        }
    }
}