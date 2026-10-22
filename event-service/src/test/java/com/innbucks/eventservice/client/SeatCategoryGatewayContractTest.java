package com.innbucks.eventservice.client;

import com.innbucks.eventservice.cache.ReadCacheConfig;
import com.innbucks.eventservice.cache.ReadCacheProperties;
import com.innbucks.eventservice.config.PooledHttpClientProperties;
import com.innbucks.eventservice.config.PooledHttpClient;
import com.innbucks.eventservice.config.HttpClientConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.eventservice.dto.EventPriceTierDTO;
import com.innbucks.eventservice.dto.EventSeatCategoryResponseDTO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    /** The production transport: the module's shared pool (CLAUDE.md, "Outbound HTTP clients are pooled"). */
    private static final PooledHttpClient POOL = new PooledHttpClient(new PooledHttpClientProperties());

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

    /** The production RestTemplate (minus the load balancer) on the shared pool. */
    private static RestTemplate restTemplate() {
        return new HttpClientConfig().restTemplate(500, 2000, POOL);
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

    /** VIP 150.00: Early bird 100.00 (100 tickets, sold as given) until earlyEnds, then Phase 1 120.00. */
    private static void stubVipWithTiers(UUID eventId, String earlyEnds, long earlySold) {
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
                                      "price": 150.00,
                                      "currentPrice": 100.00,
                                      "currentTierName": "Early bird",
                                      "priceTiers": [
                                        { "id": "6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", "name": "Early bird", "price": 100.00,
                                          "endsAt": "%s", "quantity": 100, "sold": %d, "remaining": 0, "status": "ACTIVE" },
                                        { "id": "7d2f3e4a-5b6c-4d7e-9f8a-0b1c2d3e4f5a", "name": "Phase 1", "price": 120.00,
                                          "endsAt": "2026-09-20T23:59:59+02:00", "quantity": null, "sold": 0, "status": "UPCOMING" }
                                      ],
                                      "availableSeats": 50,
                                      "sections": [ { "section": "A", "seatCount": 25 } ]
                                    }
                                  ]
                                }
                                """.formatted(eventId, earlyEnds, earlySold))));
    }

    private static Clock fixedAt(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static List<EventPriceTierDTO.Status> statuses(EventSeatCategoryResponseDTO category) {
        return category.getPriceTiers().stream().map(EventPriceTierDTO::getStatus).toList();
    }

    @Test
    @DisplayName("First open tier is the current price; the full price stays categoryPrice")
    void priceTiers_firstOpenTier_isTheCurrentPrice() {
        UUID eventId = UUID.randomUUID();
        stubVipWithTiers(eventId, "2026-09-08T23:59:59+02:00", 37);
        gateway.setClock(fixedAt("2026-09-01T08:00:00Z"));

        EventSeatCategoryResponseDTO vip = gateway.fetchForEvent(eventId).get(0);

        assertEquals(new BigDecimal("150.00"), vip.getCategoryPrice());
        assertEquals(new BigDecimal("100.00"), vip.getCurrentPrice());
        assertEquals("Early bird", vip.getCurrentTierName());
        assertEquals(List.of(EventPriceTierDTO.Status.ACTIVE, EventPriceTierDTO.Status.UPCOMING), statuses(vip));
        assertEquals(63L, vip.getPriceTiers().get(0).getRemaining());
        assertEquals(LocalDateTime.of(2026, 9, 8, 21, 59, 59), vip.getPriceTiers().get(0).getEndsAt());
        assertEquals(new BigDecimal("150.00"), vip.getSections().get(0).getPrice());
    }

    @Test
    @DisplayName("A tier sold to its quantity or past its end hands over to the next, whatever seat-service's status said")
    void priceTiers_soldOutOrEnded_handOverToTheNext() {
        UUID eventId = UUID.randomUUID();
        stubVipWithTiers(eventId, "2026-09-08T23:59:59+02:00", 100);
        gateway.setClock(fixedAt("2026-09-01T08:00:00Z"));
        EventSeatCategoryResponseDTO soldOut = gateway.fetchForEvent(eventId).get(0);

        UUID other = UUID.randomUUID();
        stubVipWithTiers(other, "2026-09-08T21:59:59Z", 0);
        gateway.setClock(fixedAt("2026-09-08T21:59:59Z"));
        EventSeatCategoryResponseDTO ended = gateway.fetchForEvent(other).get(0);

        assertEquals(new BigDecimal("120.00"), soldOut.getCurrentPrice());
        assertEquals(List.of(EventPriceTierDTO.Status.SOLD_OUT, EventPriceTierDTO.Status.ACTIVE), statuses(soldOut));
        assertEquals(new BigDecimal("120.00"), ended.getCurrentPrice());
        assertEquals(EventPriceTierDTO.Status.ENDED, ended.getPriceTiers().get(0).getStatus());
    }

    @Test
    @DisplayName("Unreadable tier end: that tier is dropped, the categories still render")
    void priceTiers_unreadableEnd_dropsOnlyThatTier() {
        UUID eventId = UUID.randomUUID();
        stubVipWithTiers(eventId, "next Tuesday", 0);
        gateway.setClock(fixedAt("2026-09-01T08:00:00Z"));

        EventSeatCategoryResponseDTO vip = gateway.fetchForEvent(eventId).get(0);

        assertEquals(List.of("Phase 1"),
                vip.getPriceTiers().stream().map(EventPriceTierDTO::getName).toList());
        assertEquals(new BigDecimal("120.00"), vip.getCurrentPrice());
        assertEquals("VIP", vip.getName());
    }

    @Test
    @DisplayName("A cached layout closes a tier on time without asking seat-service again")
    void cachedLayout_closesATierOnTime() {
        UUID eventId = UUID.randomUUID();
        stubVipWithTiers(eventId, "2026-09-08T23:59:59+02:00", 0);
        SeatCategoryGateway cached = new SeatCategoryGateway(restTemplate(), passThroughBreakerFactory(),
                "http://localhost:" + wireMock.port(),
                new ReadCacheConfig().readCacheManager(new ReadCacheProperties()));

        cached.setClock(fixedAt("2026-09-08T21:00:00Z"));
        BigDecimal during = cached.fetchForEvent(eventId).get(0).getCurrentPrice();
        cached.setClock(fixedAt("2026-09-08T22:00:00Z"));
        BigDecimal after = cached.fetchForEvent(eventId).get(0).getCurrentPrice();

        assertEquals(new BigDecimal("100.00"), during);
        assertEquals(new BigDecimal("120.00"), after);
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/seat-categories")));
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
