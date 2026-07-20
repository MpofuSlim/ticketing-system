package com.innbucks.userservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

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
    public OpenAPI customOpenAPI() {
        Server server = new Server();
        server.setUrl(publicApiPrefix.isBlank() ? "/" : publicApiPrefix);
        server.setDescription("Gateway relative server");

        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .servers(List.of(server))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                        ));
    }
}
