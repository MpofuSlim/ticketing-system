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
 * Pins the Swagger-through-the-edge contract for the /foundry public prefix
 * (the edge nginx mounts this system under a path prefix and STRIPS it before
 * proxying).
 *
 * The browser-side Swagger UI fetches each service's api-docs from the PUBLIC
 * origin, so the urls in the swagger-config must carry the prefix even though
 * no request that reaches the gateway ever contains it. That is done with
 * plain configuration (${PUBLIC_API_PREFIX:} interpolation in
 * springdoc.swagger-ui.urls) — deliberately NOT with X-Forwarded-Prefix,
 * which the gateway applies to route matching and 404s every route (verified
 * in prod during the /foundry rollout).
 *
 * Two hard requirements, both learned in prod:
 *  1. The UI entry point (/swagger-ui/index.html) must serve 200 DIRECTLY —
 *     never a redirect. A server-side redirect cannot carry the public prefix
 *     through the stripping edge, so the browser lands on the domain root and
 *     loops. Setting springdoc.swagger-ui.config-url (to ANY value) silently
 *     turns the entry point into a 302-redirecting welcome handler — that is
 *     why the property must stay absent; this test fails if it comes back.
 *  2. Every url in the swagger-config carries the prefix when
 *     PUBLIC_API_PREFIX is set, and the payload is byte-identical to the
 *     historical root-relative shape when it is not (empty default is a true
 *     no-op for local dev and direct NodePort access).
 *
 * The swagger endpoints are open here because the test profile runs with a
 * blank SWAGGER_PASSWORD (non-prod ⇒ Swagger open, per SwaggerSecurityConfig).
 */
class SwaggerPublicPrefixTest {

    private static final String CONFIG_PATH = "/v3/api-docs/swagger-config";
    private static final String UI_PATH = "/swagger-ui/index.html";

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
        void uiEntryPointServesDirectlyWithoutRedirect() {
            // 200, not 3xx: a redirect's Location cannot carry the public
            // prefix through the stripping edge — in prod it sent the browser
            // to the domain root and looped ("The page isn't redirecting
            // properly"). Regression guard for springdoc.swagger-ui.config-url
            // being (re)introduced.
            client.get().uri(UI_PATH)
                    .exchange()
                    .expectStatus().isOk();
        }

        @Test
        void swaggerConfigCarriesPublicPrefixOnEveryServiceUrl() {
            String json = new String(client.get().uri(CONFIG_PATH)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .returnResult().getResponseBody());

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
        void uiEntryPointServesDirectlyWithoutRedirect() {
            client.get().uri(UI_PATH)
                    .exchange()
                    .expectStatus().isOk();
        }

        @Test
        void emptyPrefixKeepsHistoricalRootRelativeUrls() {
            String json = new String(client.get().uri(CONFIG_PATH)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .returnResult().getResponseBody());

            for (String service : SERVICES) {
                assertThat(json).contains("\"/" + service + "/v3/api-docs\"");
            }
            assertThat(json).doesNotContain("/foundry");
        }
    }
}
