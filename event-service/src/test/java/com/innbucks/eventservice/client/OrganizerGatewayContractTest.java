package com.innbucks.eventservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.eventservice.dto.OrganizerDTO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for {@link OrganizerGateway} — the S2S client that resolves
 * organizer business details by {@code user_uuid} for event-response
 * enrichment. Pins the wire shape of {@code POST
 * /users/internal/tenants/lookup-by-uuid} so a change in user-service's
 * envelope or the outbound payload shape fails the build instead of silently
 * stripping organizer details from every listing.
 *
 * <p>Pure JUnit + WireMock, no Spring context (per the CLAUDE.md convention).
 * The breaker is stubbed inline so the test asserts the wire contract, not
 * the breaker library — Resilience4j's behaviour is tested separately.
 */
class OrganizerGatewayContractTest {

    private static WireMockServer wireMock;
    private static OrganizerGateway gateway;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        gateway = new OrganizerGateway(
                restTemplate(),
                passThroughBreakerFactory(),
                new ObjectMapper(),
                "http://localhost:" + wireMock.port(),
                "test-token");
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private static RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(2000));
        return new RestTemplate(factory);
    }

    @Test
    @DisplayName("lookup-by-uuid 200: parses rows, sends X-Internal-Token + JSON, posts userUuids on the body")
    void happyPath_parsesAndVerifiesOutboundContract() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo("/users/internal/tenants/lookup-by-uuid"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"200\",\"message\":\"ok\",\"data\":["
                                + "{\"userUuid\":\"" + a + "\",\"businessName\":\"Alice Co\","
                                + "\"businessAddress\":\"1 A St\",\"businessEmail\":\"alice@biz.co\"},"
                                + "{\"userUuid\":\"" + b + "\",\"businessName\":\"Bob LLC\","
                                + "\"businessAddress\":\"2 B St\",\"businessEmail\":\"bob@biz.co\"}"
                                + "]}")));

        Map<UUID, OrganizerDTO> out = gateway.organizersByUserUuids(List.of(a, b));

        assertEquals(2, out.size());
        assertEquals("Alice Co", out.get(a).getBusinessName());
        assertEquals("1 A St", out.get(a).getBusinessAddress());
        assertEquals("alice@biz.co", out.get(a).getBusinessEmail());
        assertEquals("Bob LLC", out.get(b).getBusinessName());

        wireMock.verify(postRequestedFor(urlEqualTo("/users/internal/tenants/lookup-by-uuid"))
                .withHeader("X-Internal-Token", equalTo("test-token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.userUuids[0]", equalTo(a.toString())))
                .withRequestBody(matchingJsonPath("$.userUuids[1]", equalTo(b.toString()))));
    }

    @Test
    @DisplayName("guard rail: empty/null input makes NO HTTP call")
    void blankInput_noNetworkCall() {
        assertTrue(gateway.organizersByUserUuids(List.of()).isEmpty());
        assertTrue(gateway.organizersByUserUuids(null).isEmpty());

        wireMock.verify(0, postRequestedFor(urlEqualTo("/users/internal/tenants/lookup-by-uuid")));
    }

    @Test
    @DisplayName("de-dupes the userUuids list so a page of 50 events from the same organizer is one bind")
    void dedupes_repeatedUuid() {
        UUID a = UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo("/users/internal/tenants/lookup-by-uuid"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":[{\"userUuid\":\"" + a + "\",\"businessName\":\"A\"}]}")));

        gateway.organizersByUserUuids(List.of(a, a, a, a));

        wireMock.verify(postRequestedFor(urlEqualTo("/users/internal/tenants/lookup-by-uuid"))
                .withRequestBody(matchingJsonPath("$.userUuids[0]", equalTo(a.toString())))
                // index 1 must NOT exist: only one bind sent on the wire.
                .withRequestBody(notMatching(".*\\[\"" + a + "\",\"" + a + "\".*")));
    }

    @Test
    @DisplayName("lookup-by-uuid 5xx → empty map (breaker fallback; listings still serve without organizer details)")
    void serverError_returnsEmpty() {
        wireMock.stubFor(post(urlEqualTo("/users/internal/tenants/lookup-by-uuid"))
                .willReturn(aResponse().withStatus(503)));

        assertTrue(gateway.organizersByUserUuids(List.of(UUID.randomUUID())).isEmpty());
    }

    @Test
    @DisplayName("lookup-by-uuid skips a row whose userUuid is malformed and keeps the valid ones")
    void malformedUuid_isSkipped() {
        UUID good = UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo("/users/internal/tenants/lookup-by-uuid"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":["
                                + "{\"userUuid\":\"not-a-uuid\",\"businessName\":\"junk\"},"
                                + "{\"userUuid\":\"" + good + "\",\"businessName\":\"valid\"}"
                                + "]}")));

        Map<UUID, OrganizerDTO> out = gateway.organizersByUserUuids(List.of(good));

        assertEquals(1, out.size());
        assertNotNull(out.get(good));
        assertNull(out.get(null));
    }

    @Test
    @DisplayName("connection refused → empty map (fallback) so a user-service outage doesn't crash listings")
    void connectionRefused_returnsEmpty() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        OrganizerGateway dead = new OrganizerGateway(
                restTemplate(),
                passThroughBreakerFactory(),
                new ObjectMapper(),
                "http://localhost:" + closedPort,
                "test-token");

        assertTrue(dead.organizersByUserUuids(List.of(UUID.randomUUID())).isEmpty());
    }

    /**
     * A Mockito-stubbed {@link CircuitBreakerFactory} that runs the supplied
     * supplier inline and invokes the fallback on any throwable. Keeps the
     * test surgical to the WIRE contract, not the breaker library — the real
     * Resilience4j integration is exercised separately.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CircuitBreakerFactory passThroughBreakerFactory() {
        CircuitBreakerFactory factory = mock(CircuitBreakerFactory.class);
        CircuitBreaker breaker = mock(CircuitBreaker.class);
        when(factory.create(any())).thenReturn(breaker);
        when(breaker.run(any(Supplier.class), any(Function.class))).thenAnswer(inv -> {
            Supplier<?> toRun = inv.getArgument(0);
            Function<Throwable, ?> fallback = inv.getArgument(1);
            try {
                return toRun.get();
            } catch (Throwable t) {
                return fallback.apply(t);
            }
        });
        return factory;
    }
}
