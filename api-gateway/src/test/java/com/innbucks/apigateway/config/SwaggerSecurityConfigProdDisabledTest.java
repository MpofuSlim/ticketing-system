package com.innbucks.apigateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Pins the fail-closed behaviour {@link SwaggerSecurityConfig} applies under the
 * {@code prod} profile when {@code swagger.auth.password} is NOT set: the
 * aggregated Swagger UI and the per-service OpenAPI JSON are disabled and answer
 * {@code 404}, so a prod deploy that forgot the secret hides the docs rather
 * than leaking them. (With a password set, prod gets the HTTP Basic gate — see
 * {@link SwaggerSecurityConfigTest}; in a non-prod profile a blank password
 * leaves Swagger open — see {@link SwaggerSecurityConfigOpenTest}.)
 *
 * <p>{@code test} stays active so discovery is disabled and the gateway context
 * loads without Eureka; {@code prod} is added so the config's profile check
 * fires.
 */
@SpringBootTest
@ActiveProfiles({"test", "prod"})
class SwaggerSecurityConfigProdDisabledTest {

    @Autowired
    private ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).configureClient().build();
    }

    @Test
    void swaggerUiConfig_inProdWithoutPassword_isNotFound() {
        client.get().uri("/v3/api-docs/swagger-config")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void perServiceDocs_inProdWithoutPassword_isNotFound() {
        // The OpenAPI JSON the UI fetches through the gateway is disabled too,
        // not just the UI shell.
        client.get().uri("/user-service/v3/api-docs")
                .exchange()
                .expectStatus().isNotFound();
    }
}
