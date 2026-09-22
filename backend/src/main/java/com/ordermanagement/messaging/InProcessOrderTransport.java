package com.ordermanagement.messaging;

import com.ordermanagement.exception.PoolSaturatedException;
import com.ordermanagement.service.OrderProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Drives order processing with the local bounded worker pool.
 *
 * <p>This is the fallback transport for machines where no broker binary can run (the canonical
 * {@code rabbitmq} transport is used everywhere else). It is not a stub: it uses the very same bounded
 * pool, the same retry scheduler and the same {@link OrderProcessingService} pipeline, so behaviour
 * differs only in where the work is handed over, never in how it is processed.
 *
 * <p>Saturation is signalled, not hidden: when all workers are busy and the bounded queue is full the
 * submission is rejected with {@link PoolSaturatedException}, which the API surfaces as
 * {@code 503 POOL_SATURATED}. The order itself is already persisted as {@code PENDING}, so resubmitting
 * the request with the same idempotency key re-drives it later.
 */
@Component
@ConditionalOnProperty(prefix = "order.messaging", name = "transport", havingValue = "in-process")
public class InProcessOrderTransport implements OrderTransport {

    private static final Logger log = LoggerFactory.getLogger(InProcessOrderTransport.class);

    private final ThreadPoolTaskExecutor orderExecutor;
    private final ThreadPoolTaskScheduler orderRetryScheduler;
    private final OrderProcessingService orderProcessingService;

    public InProcessOrderTransport(ThreadPoolTaskExecutor orderExecutor,
                                   ThreadPoolTaskScheduler orderRetryScheduler,
                                   OrderProcessingService orderProcessingService) {
        this.orderExecutor = orderExecutor;
        this.orderRetryScheduler = orderRetryScheduler;
        this.orderProcessingService = orderProcessingService;
    }

    @Override
    public void submit(Long orderId) {
        try {
            orderExecutor.execute(() -> runGuarded(orderId));
            log.info("ORDER_ENQUEUED orderId={} transport=in-process activeWorkers={} queueRemaining={}",
                    orderId, orderExecutor.getActiveCount(), orderExecutor.getThreadPoolExecutor().getQueue().remainingCapacity());
        } catch (TaskRejectedException rejected) {
            throw new PoolSaturatedException(
                    "Order pipeline is saturated (workers=%d, queue=%d); order %d stays PENDING"
                            .formatted(orderExecutor.getMaxPoolSize(), orderExecutor.getQueueCapacity(), orderId),
                    rejected);
        }
    }

    @Override
    public void submitForRetry(Long orderId, long delayMillis) {
        // Scheduled on the dedicated retry scheduler so a backoff wait never occupies a worker thread.
        orderRetryScheduler.schedule(() -> runGuarded(orderId), Instant.now().plusMillis(Math.max(0, delayMillis)));
        log.info("ORDER_RETRY_SCHEDULED orderId={} delayMs={}", orderId, delayMillis);
    }

    /** A worker must never die with an uncaught exception: the order status is the source of truth. */
    private void runGuarded(Long orderId) {
        try {
            orderProcessingService.processOrder(orderId);
        } catch (RuntimeException unexpected) {
            log.error("ORDER_WORKER_FAILURE orderId={}", orderId, unexpected);
        }
    }
}
