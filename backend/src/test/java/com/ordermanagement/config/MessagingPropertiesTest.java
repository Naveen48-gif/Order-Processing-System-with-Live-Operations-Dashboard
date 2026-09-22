package com.ordermanagement.config;

import com.ordermanagement.config.MessagingProperties.Transport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the broker topology contract of {@link MessagingProperties}.
 *
 * <p>The dashboard and the operations runbook depend on these names matching what is declared in the
 * broker, so the defaults are pinned here: moving to another broker namespace is then a pure
 * configuration change, verified by a test rather than by tribal knowledge.
 */
class MessagingPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesTestConfiguration.class);

    @Test
    @DisplayName("canonical topology names and the RabbitMQ transport are the defaults")
    void bindsCanonicalDefaults() {
        contextRunner.run(context -> {
            MessagingProperties properties = context.getBean(MessagingProperties.class);

            assertThat(properties.getTransport()).isEqualTo(Transport.RABBITMQ);
            assertThat(properties.getExchange()).isEqualTo("order.exchange");
            assertThat(properties.getQueue()).isEqualTo("order.queue");
            assertThat(properties.getRoutingKey()).isEqualTo("order.routing-key");
            assertThat(properties.getDlx()).isEqualTo("order.dlx");
            assertThat(properties.getDlq()).isEqualTo("order.dlq");
            assertThat(properties.getDlqRoutingKey()).isEqualTo("order.dlq.routing-key");
        });
    }

    @Test
    @DisplayName("kebab-case 'in-process' binds to the IN_PROCESS transport")
    void bindsInProcessTransport() {
        contextRunner
                .withPropertyValues("order.messaging.transport=in-process")
                .run(context -> assertThat(context.getBean(MessagingProperties.class).getTransport())
                        .isEqualTo(Transport.IN_PROCESS));
    }

    @Test
    @DisplayName("topology names are overridable per environment")
    void bindsTopicNameOverrides() {
        contextRunner
                .withPropertyValues(
                        "order.messaging.exchange=orders.work.exchange",
                        "order.messaging.queue=orders.work.queue",
                        "order.messaging.dlq=orders.work.dlq")
                .run(context -> {
                    MessagingProperties properties = context.getBean(MessagingProperties.class);

                    assertThat(properties.getExchange()).isEqualTo("orders.work.exchange");
                    assertThat(properties.getQueue()).isEqualTo("orders.work.queue");
                    assertThat(properties.getDlq()).isEqualTo("orders.work.dlq");
                });
    }

    @Test
    @DisplayName("an unknown transport value fails fast instead of silently degrading")
    void rejectsUnknownTransport() {
        contextRunner
                .withPropertyValues("order.messaging.transport=carrier-pigeon")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MessagingProperties.class)
    static class PropertiesTestConfiguration {
    }
}
