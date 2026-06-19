package com.innbucks.apigateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the HTTP Basic gate {@link SwaggerSecurityConfig} puts in front of the
 * aggregated Swagger UI when {@code swagger.auth.password} is set:
 *
 * <ul>
 *   <li>a Swagger path with no / wrong credentials is 401,</li>
 *   <li>the configured credentials let it through,</li>
 *   <li>a non-Swagger (routed) path is NOT challenged here — it stays open at
 *       the gateway and is JWT-checked downstream.</li>
 * </ul>
 *
 * <p>{@link WebTestClient} is bound to the application context so the reactive
 * {@code SecurityWebFilterChain} runs without needing a live server or Redis.
 */
@SpringBootTest(properties = {
        "swagger.auth.username=swaggeruser",
        "swagger.auth.password=s3cret-pw"
})
@ActiveProfiles("test")
class SwaggerSecurityConfigTest {

    @Autowired
    private ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).configureClient().build();
    }

    @Test
    void swaggerDocs_withoutCredentials_areUnauthorized() {
        client.get().uri("/v3/api-docs/swagger-config")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void swaggerDocs_withWrongPassword_areUnauthorized() {
        client.get().uri("/v3/api-docs/swagger-config")
                .headers(h -> h.setBasicAuth("swaggeruser", "not-the-password"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void perServiceDocs_withoutCredentials_areUnauthorized() {
        // The OpenAPI JSON the UI fetches through the gateway is covered too,
        // not just the UI shell.
        client.get().uri("/user-service/v3/api-docs")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void swaggerDocs_withCorrectCredentials_passSecurity() {
        // Correct Basic creds clear the security filter; springdoc serves the
        // swagger-config in-process, so this is a 200 (not a 401).
        client.get().uri("/v3/api-docs/swagger-config")
                .headers(h -> h.setBasicAuth("swaggeruser", "s3cret-pw"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void nonSwaggerPath_isNotChallengedBySwaggerAuth() {
        // A routed API path is permitAll at the gateway (downstream JWT does the
        // real auth). With discovery disabled in tests it won't reach a live
        // service, but the key assertion is that Swagger's Basic gate does NOT
        // turn it into a 401.
        client.get().uri("/auth/login")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401));
    }
}
