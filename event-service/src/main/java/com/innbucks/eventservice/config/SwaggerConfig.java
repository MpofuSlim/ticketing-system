package com.innbucks.eventservice.config;

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
                title = "Event Service API",
                version = "1.0",
                description = """
                        Manages event metadata, venues, and scheduling.

                        - **Public** `GET` endpoints are available without a token.
                        - **Write** operations require a `TENANT` role and a **Bearer JWT** in the `Authorization` header.
                        - The H2 console is for local development only; it is not a public API.
                        """
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
        name = "bearerAuth",
        description = """
                JWT Bearer token.

                Obtain a token from the user-service authentication endpoints, then click **Authorize** in Swagger UI and paste:
                `Bearer <token>`
                """,
        scheme = "bearer",
        type = SecuritySchemeType.HTTP,
        bearerFormat = "JWT",
        in = SecuritySchemeIn.HEADER
)
public class SwaggerConfig {

    @Bean
    public OpenAPI eventOpenApiServers() {
        return new OpenAPI().servers(List.of(
                new Server().url("/").description("Gateway relative server")
        ));
    }
}
