package com.ordermanagement.websocket;

import com.ordermanagement.config.WebProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket wiring for the live dashboard (AGENT.md §5): endpoint {@code /ws}, broker
 * prefix {@code /topic}, application prefix {@code /app}.
 *
 * <p>No SockJS fallback is registered: the dashboard is a modern browser client talking native
 * WebSocket, so the extra negotiation layer would only add latency for no benefit.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebProperties webProperties;

    public WebSocketConfig(WebProperties webProperties) {
        this.webProperties = webProperties;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(webProperties.allowedOrigins().toArray(String[]::new));
    }
}
