package com.ordermanagement.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

@Configuration
public class ThreadPoolConfig {

    @Value("${order.processing.core-pool-size:5}")
    private int corePoolSize;

    @Value("${order.processing.max-pool-size:10}")
    private int maxPoolSize;

    @Value("${order.processing.queue-capacity:200}")
    private int queueCapacity;

    // Bounded thread pool for concurrent order processing. The bounded queue plus
    // CallerRunsPolicy gives natural backpressure instead of unbounded memory growth
    // when order intake outpaces processing capacity.
    @Bean(name = "orderExecutor", destroyMethod = "shutdown")
    public ExecutorService orderExecutor() {
        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new ThreadFactory() {
                    private int count = 0;

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "order-worker-" + (++count));
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
