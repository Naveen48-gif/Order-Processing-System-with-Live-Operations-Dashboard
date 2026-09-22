package com.ordermanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Web/dashboard integration settings.
 *
 * <p>CORS origins are configuration rather than a hardcoded wildcard: the Vite dev server, a preview
 * build and a deployed dashboard all need different origins, and shipping "*" to production is how
 * browser-based attacks against internal APIs become possible.
 */
@ConfigurationProperties(prefix = "order.web")
public record WebProperties(

        @DefaultValue({"http://localhost:5173", "http://127.0.0.1:5173", "http://localhost:4173"})
        List<String> allowedOrigins
) {
}
