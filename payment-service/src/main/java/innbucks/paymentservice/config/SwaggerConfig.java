package innbucks.paymentservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Pins the OpenAPI {@code servers} entry the aggregated Swagger UI uses for
 * "Try it out" against this service's docs. Without it springdoc derives an
 * absolute server URL from the internal request (the gateway-to-service hop),
 * which is unreachable from a browser. Mirrors the SwaggerConfig the other
 * services already carry.
 */
@Configuration
public class SwaggerConfig {

    /**
     * Path prefix the public edge mounts the system under (e.g. {@code /foundry}
     * on dtx.innbucks.co.zw). Swagger UI resolves this server URL in the BROWSER
     * against the public origin, so it must carry the prefix even though nginx
     * strips it before the request reaches any service. Blank (the default)
     * falls back to "/" — the domain-root behavior for local dev.
     */
    @Value("${PUBLIC_API_PREFIX:}")
    private String publicApiPrefix;

    @Bean
    public OpenAPI paymentOpenApiServers() {
        return new OpenAPI().servers(List.of(
                new Server().url(publicApiPrefix.isBlank() ? "/" : publicApiPrefix)
                        .description("Gateway relative server")
        ));
    }
}
