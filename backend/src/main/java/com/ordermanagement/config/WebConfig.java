package com.ordermanagement.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web layer configuration, currently CORS for the dashboard.
 *
 * <p>Origins come from {@link WebProperties} so the deployed dashboard origin can be configured per
 * environment instead of allowing every origin. Only the methods and headers the dashboard actually
 * uses are exposed, which keeps the browser's preflight surface small.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final WebProperties webProperties;

    public WebConfig(WebProperties webProperties) {
        this.webProperties = webProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(webProperties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders(HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT, "Idempotency-Key")
                .maxAge(3600);
    }
}
