package com.innbucks.apigateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Swagger-through-the-edge contract added for the /foundry public
 * prefix (the edge nginx mounts this system under a path prefix and STRIPS it
 * before proxying).
 *
 * The browser-side Swagger UI fetches the swagger-config and each service's
 * api-docs from the PUBLIC origin, so those URLs must carry the prefix even
 * though no request that reaches the gateway ever contains it. That is done
 * with plain configuration (${PUBLIC_API_PREFIX:} interpolation in
 * springdoc.swagger-ui.*) — deliberately NOT with X-Forwarded-Prefix, which
 * the gateway applies to route matching and 404s every route (verified in
 * prod during the /foundry rollout).
 *
 * Two contexts pin both halves of the contract:
 *  - {@link WithPrefix}: PUBLIC_API_PREFIX=/foundry → every URL in the
 *    swagger-config is prefixed.
 *  - {@link WithoutPrefix}: unset → URLs are identical to the historical
 *    root-relative values, so the empty default is a true no-op for local
 *    dev and direct NodePort access.
 *
 * The swagger endpoints are open here because the test profile runs with a
 * blank SWAGGER_PASSWORD (non-prod ⇒ Swagger open, per SwaggerSecurityConfig).
 */
class SwaggerPublicPrefixTest {

    private static final String CONFIG_PATH = "/v3/api-docs/swagger-config";

    private static final String[] SERVICES = {
            "user-service", "event-service", "seat-service",
            "booking-service", "payment-service", "loyalty-service"};

    @Nested
    @SpringBootTest(properties = "PUBLIC_API_PREFIX=/foundry")
    @ActiveProfiles("test")
    class WithPrefix {

        @Autowired
        private ApplicationContext context;

        private WebTestClient client;

        @BeforeEach
        void setUp() {
            client = WebTestClient.bindToApplicationContext(context).configureClient().build();
        }

        @Test
        void swaggerConfigCarriesPublicPrefixOnEveryUrl() {
            String json = new String(client.get().uri(CONFIG_PATH)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .returnResult().getResponseBody());

            assertThat(json).contains("\"/foundry/v3/api-docs/swagger-config\"");
            for (String service : SERVICES) {
                assertThat(json)
                        .as("swagger-config url for %s must carry the public prefix", service)
                        .contains("\"/foundry/" + service + "/v3/api-docs\"");
            }
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class WithoutPrefix {

        @Autowired
        private ApplicationContext context;

        private WebTestClient client;

        @BeforeEach
        void setUp() {
            client = WebTestClient.bindToApplicationContext(context).configureClient().build();
        }

        @Test
        void emptyPrefixKeepsHistoricalRootRelativeUrls() {
            String json = new String(client.get().uri(CONFIG_PATH)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .returnResult().getResponseBody());

            assertThat(json).contains("\"/v3/api-docs/swagger-config\"");
            for (String service : SERVICES) {
                assertThat(json).contains("\"/" + service + "/v3/api-docs\"");
            }
            assertThat(json).doesNotContain("/foundry");
        }
    }
}
