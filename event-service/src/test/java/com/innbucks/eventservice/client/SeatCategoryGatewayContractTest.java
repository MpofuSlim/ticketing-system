package com.innbucks.eventservice.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.eventservice.dto.EventSeatCategoryResponseDTO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract test for {@link SeatCategoryGateway} — the client that pulls
 * seat-category metadata from seat-service to enrich event responses.
 *
 * <p>This test pins the wire shape we consume: seat-service wraps every
 * payload in its {@code ApiResult&lt;T&gt;} envelope ({@code code/message/data}).
 * Earlier the gateway deserialised the body straight into a bare array and
 * every call fell through to the breaker fallback with an empty list — no
 * seat-category details ever reached the FE. A contract test pinning the
 * envelope shape is what surfaces that kind of regression at PR time.
 */
class SeatCategoryGatewayContractTest {

    private static WireMockServer wireMock;
    private static SeatCategoryGateway gateway;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        gateway = new SeatCategoryGateway(
                restTemplate(),
                passThroughBreakerFactory(),
                "http://localhost:" + wireMock.port());
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
    @DisplayName("200 ApiResult envelope: unwraps .data into categories, copies prices into sections")
    void happyPath_unwrapsEnvelopeAndMapsSections() {
        UUID eventId = UUID.randomUUID();
        wireMock.stubFor(get(urlPathEqualTo("/seat-categories"))
                .withQueryParam("eventId", equalTo(eventId.toString()))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "code": "200 OK",
                                  "message": "Categories retrieved successfully",
                                  "data": [
                                    {
                                      "seatCategoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                      "eventId": "%s",
                                      "name": "VIP",
                                      "description": "Front rows",
                                      "price": 100.00,
                                      "availableSeats": 50,
                                      "sections": [
                                        { "section": "A", "seatCount": 25 },
                                        { "section": "B", "seatCount": 25 }
                                      ]
                                    }
                                  ]
                                }
                                """.formatted(eventId))));

        List<EventSeatCategoryResponseDTO> out = gateway.fetchForEvent(eventId);

        assertEquals(1, out.size());
        EventSeatCategoryResponseDTO vip = out.get(0);
        assertEquals("VIP", vip.getName());
        assertEquals("Front rows", vip.getDescription());
        assertEquals(new BigDecimal("100.00"), vip.getCategoryPrice());
        assertEquals(2, vip.getSections().size());
        assertEquals("A", vip.getSections().get(0).getSection());
        assertEquals(25, vip.getSections().get(0).getSeatCount());
        assertEquals(new BigDecimal("100.00"), vip.getSections().get(0).getPrice());

        wireMock.verify(getRequestedFor(urlPathEqualTo("/seat-categories"))
                .withQueryParam("eventId", equalTo(eventId.toString())));
    }

    @Test
    @DisplayName("200 with data:[] → empty list (event still serves, just no categories)")
    void emptyData_returnsEmpty() {
        UUID eventId = UUID.randomUUID();
        wireMock.stubFor(get(urlPathEqualTo("/seat-categories"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"200 OK\",\"message\":\"ok\",\"data\":[]}")));

        assertTrue(gateway.fetchForEvent(eventId).isEmpty());
    }

    @Test
    @DisplayName("200 with data:null → empty list (defensive against an envelope with no payload)")
    void nullData_returnsEmpty() {
        UUID eventId = UUID.randomUUID();
        wireMock.stubFor(get(urlPathEqualTo("/seat-categories"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"200 OK\",\"message\":\"ok\",\"data\":null}")));

        assertTrue(gateway.fetchForEvent(eventId).isEmpty());
    }

    @Test
    @DisplayName("seat-service 5xx → breaker fallback → empty list (event still serves)")
    void serverError_returnsEmpty() {
        wireMock.stubFor(get(urlPathEqualTo("/seat-categories"))
                .willReturn(aResponse().withStatus(503)));

        assertTrue(gateway.fetchForEvent(UUID.randomUUID()).isEmpty());
    }

    @Test
    @DisplayName("connect refused → breaker fallback → empty list (seat-service outage doesn't break listings)")
    void connectionRefused_returnsEmpty() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        SeatCategoryGateway dead = new SeatCategoryGateway(
                restTemplate(),
                passThroughBreakerFactory(),
                "http://localhost:" + closedPort);

        assertTrue(dead.fetchForEvent(UUID.randomUUID()).isEmpty());
    }

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
