package com.ordermanagement.concurrency;

import com.ordermanagement.config.OrderProcessingProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * The bounded worker pool that gives order processing its concurrency.
 *
 * <p>Why bounded on both axes:
 * <ul>
 *   <li><strong>Fixed thread count</strong> (core = max = 10 by default). Each worker holds a database
 *       connection while it owns an inventory row lock, so "unlimited threads" would translate into
 *       database connection starvation and lock convoying, not throughput.</li>
 *   <li><strong>Bounded queue</strong> (100 slots). A queue that grows without limit converts overload
 *       into ever-increasing latency and eventually an out-of-memory error; a bounded queue converts it
 *       into an immediate, visible rejection the API reports as {@code 503 POOL_SATURATED}.</li>
 *   <li><strong>AbortPolicy.</strong> Rejection is signalled to the caller instead of being executed in
 *       the caller's thread, so an HTTP request thread never silently turns into a worker (that would
 *       hide the overload and blow up request latency).</li>
 * </ul>
 *
 * <p>Shutdown is graceful: in-flight orders finish their transaction before the JVM exits, so a deploy
 * cannot leave an order half-processed with its inventory row still locked.
 */
@Configuration
public class OrderWorkerPoolConfig {

    @Bean(name = "orderExecutor")
    public ThreadPoolTaskExecutor orderExecutor(OrderProcessingProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
        executor.setThreadNamePrefix("order-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());
        executor.initialize();
        return executor;
    }

    /**
     * Scheduler for delayed retries. Kept separate from the worker pool so a sleeping retry never
     * occupies one of the ten worker threads - with a shared pool, backoff would silently reduce
     * processing capacity.
     */
    @Bean(name = "orderRetryScheduler")
    public ThreadPoolTaskScheduler orderRetryScheduler(OrderProcessingProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("order-retry-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());
        scheduler.initialize();
        return scheduler;
    }
}
