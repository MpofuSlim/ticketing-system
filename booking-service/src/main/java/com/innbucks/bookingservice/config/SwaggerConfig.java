package com.innbucks.bookingservice.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Booking Service API",
                version = "1.0",
                description = "Manages bookings, confirmation numbers and QR tickets"
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
        name = "bearerAuth",
        description = "JWT Bearer token",
        scheme = "bearer",
        type = SecuritySchemeType.HTTP,
        bearerFormat = "JWT",
        in = SecuritySchemeIn.HEADER
)
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
    public OpenAPI bookingOpenApiServers() {
        return new OpenAPI().servers(List.of(
                new Server().url(publicApiPrefix.isBlank() ? "/" : publicApiPrefix).description("Gateway relative server")
        ));
    }
}
