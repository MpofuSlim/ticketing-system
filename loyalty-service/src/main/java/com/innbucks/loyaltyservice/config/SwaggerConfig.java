package com.innbucks.loyaltyservice.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Loyalty Service API",
                version = "1.0",
                description = """
                        Per-tenant points wallet. Tenants configure earn/redeem rules; customers
                        accumulate points on cash spend and redeem them at checkout.

                        - **Rules** are TENANT-only writes; reads are open to authenticated callers.
                        - **earn** is called by booking-service when a confirmation includes cash.
                        - **redeem** is called when a confirmation includes points.
                        """
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

    @Bean
    public OpenAPI loyaltyOpenApiServers() {
        return new OpenAPI().servers(List.of(
                new Server().url("/").description("Gateway relative server")
        ));
    }
}
