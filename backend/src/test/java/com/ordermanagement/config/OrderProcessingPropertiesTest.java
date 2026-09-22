package com.ordermanagement.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the pool/retry tuning contract of {@link OrderProcessingProperties}.
 *
 * <p>These assertions are deliberately independent of any database or broker: they protect the
 * configuration surface the concurrency tests rely on (a bounded 10/10 pool with a 100-slot queue
 * and a retry budget of 3), so a misconfiguration is caught long before an order is ever processed.
 */
class OrderProcessingPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesTestConfiguration.class);

    @Test
    @DisplayName("defaults match the documented bounded-pool contract")
    void bindsDocumentedDefaults() {
        contextRunner.run(context -> {
            OrderProcessingProperties properties = context.getBean(OrderProcessingProperties.class);

            assertThat(properties.getCorePoolSize()).isEqualTo(10);
            assertThat(properties.getMaxPoolSize()).isEqualTo(10);
            assertThat(properties.getQueueCapacity()).isEqualTo(100);
            assertThat(properties.getMaxRetryAttempts()).isEqualTo(3);
            assertThat(properties.getRetryBackoffMs()).isPositive();
        });
    }

    @Test
    @DisplayName("environment overrides are bound with relaxed naming")
    void bindsOverridesFromProperties() {
        contextRunner
                .withPropertyValues(
                        "order.processing.core-pool-size=4",
                        "order.processing.max-pool-size=8",
                        "order.processing.queue-capacity=250",
                        "order.processing.max-retry-attempts=5",
                        "order.processing.retry-backoff-ms=750")
                .run(context -> {
                    OrderProcessingProperties properties = context.getBean(OrderProcessingProperties.class);

                    assertThat(properties.getCorePoolSize()).isEqualTo(4);
                    assertThat(properties.getMaxPoolSize()).isEqualTo(8);
                    assertThat(properties.getQueueCapacity()).isEqualTo(250);
                    assertThat(properties.getMaxRetryAttempts()).isEqualTo(5);
                    assertThat(properties.getRetryBackoffMs()).isEqualTo(750);
                });
    }

    @Test
    @DisplayName("a max pool size below the core pool size is rejected at startup")
    void rejectsInconsistentPoolSizing() {
        contextRunner
                .withPropertyValues(
                        "order.processing.core-pool-size=12",
                        "order.processing.max-pool-size=10")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(causeChainMessages(context.getStartupFailure()))
                            .anyMatch(message -> message.contains("max-pool-size"));
                });
    }

    @Test
    @DisplayName("a non-positive queue capacity is rejected at startup")
    void rejectsZeroQueueCapacity() {
        contextRunner
                .withPropertyValues("order.processing.queue-capacity=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrderProcessingProperties.class)
    static class PropertiesTestConfiguration {
    }

    /**
     * A binding failure is nested (bean creation → property binding → bean validation), so the rejected
     * property name is only visible deeper in the cause chain.
     */
    private static List<String> causeChainMessages(Throwable throwable) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.add(current.getMessage());
            }
        }
        return messages;
    }
}
