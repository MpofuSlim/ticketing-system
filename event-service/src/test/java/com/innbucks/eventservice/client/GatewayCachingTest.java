package com.innbucks.eventservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.eventservice.cache.ReadCacheConfig;
import com.innbucks.eventservice.cache.ReadCacheProperties;
import com.innbucks.eventservice.dto.EventSeatCategoryResponseDTO;
import com.innbucks.eventservice.dto.OrganizerDTO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two gateway-level caches — the public seat-category layout and the
 * organizer profiles — against a real HTTP stub: a hit makes no call, a
 * failure is never remembered, and the capacity guard's allocation read is
 * never served from the layout cache.
 */
class GatewayCachingTest {

    private static WireMockServer wireMock;

    private SeatCategoryGateway seats;
    private OrganizerGateway organizers;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        // A fresh manager per test: no entry survives from one case to the next.
        CacheManager manager = new ReadCacheConfig().readCacheManager(new ReadCacheProperties());
        String base = "http://localhost:" + wireMock.port();
        seats = new SeatCategoryGateway(restTemplate(), passThroughBreakerFactory(), base, manager);
        organizers = new OrganizerGateway(restTemplate(), passThroughBreakerFactory(), new ObjectMapper(),
                base, "test-token", manager);
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    // ---- seat categories -------------------------------------------------

    private static void stubCategories(UUID eventId, String price) {
        wireMock.stubFor(get(urlPathEqualTo("/seat-categories"))
                .withQueryParam("eventId", equalTo(eventId.toString()))
                .willReturn(okJson("""
                        {"code":"200 OK","message":"ok","data":[
                          {"name":"VIP","description":"Front rows","price":%s,"availableSeats":50,
                           "sections":[{"section":"A","seatCount":25},{"section":"B","seatCount":25}]}
                        ]}""".formatted(price))));
    }

    @Test
    void publicLayout_isFetchedOncePerEvent() {
        UUID eventId = UUID.randomUUID();
        stubCategories(eventId, "100.00");

        List<EventSeatCategoryResponseDTO> first = seats.fetchForEvent(eventId);
        List<EventSeatCategoryResponseDTO> second = seats.fetchForEvent(eventId);

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/seat-categories")));
        assertEquals(1, second.size());
        assertEquals("VIP", second.get(0).getName());
        assertEquals(new BigDecimal("100.00"), second.get(0).getSections().get(1).getPrice());
        assertEquals(25, second.get(0).getSections().get(1).getSeatCount());
        // Independent copies: one caller's edit never reaches the next.
        first.get(0).setName("defaced");
        first.get(0).getSections().get(0).setSeatCount(1);
        assertEquals("VIP", seats.fetchForEvent(eventId).get(0).getName());
        assertEquals(25, seats.fetchForEvent(eventId).get(0).getSections().get(0).getSeatCount());
    }

    @Test
    void eventsAreKeptApart() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        stubCategories(a, "10.00");
        stubCategories(b, "20.00");

        assertEquals(new BigDecimal("10.00"), seats.fetchForEvent(a).get(0).getCategoryPrice());
        assertEquals(new BigDecimal("20.00"), seats.fetchForEvent(b).get(0).getCategoryPrice());
        assertEquals(new BigDecimal("10.00"), seats.fetchForEvent(a).get(0).getCategoryPrice());
        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/seat-categories")));
    }

    @Test
    void aFailedLayoutFetch_isNotCached() {
        UUID eventId = UUID.randomUUID();
        wireMock.stubFor(get(urlPathEqualTo("/seat-categories")).willReturn(aResponse().withStatus(503)));
        assertTrue(seats.fetchForEvent(eventId).isEmpty());

        wireMock.resetAll();
        stubCategories(eventId, "100.00");
        assertEquals(1, seats.fetchForEvent(eventId).size());
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/seat-categories")));
    }

    @Test
    void theOwnersRead_alwaysGoesToSeatService() {
        UUID eventId = UUID.randomUUID();
        stubCategories(eventId, "100.00");
        seats.fetchForEvent(eventId);

        seats.fetchForEventUncached(eventId);
        seats.fetchForEventUncached(eventId);
        wireMock.verify(3, getRequestedFor(urlPathEqualTo("/seat-categories")));
    }

    @Test
    void theAllocationGuard_isNeverServedFromTheLayoutCache() {
        UUID eventId = UUID.randomUUID();
        stubCategories(eventId, "100.00");
        seats.fetchForEvent(eventId); // layout now cached

        assertEquals(50L, seats.fetchAllocatedSeats(eventId).orElseThrow());
        assertEquals(50L, seats.fetchAllocatedSeats(eventId).orElseThrow());
        wireMock.verify(3, getRequestedFor(urlPathEqualTo("/seat-categories")));

        // ...and it still fails CLOSED during an outage, cache or no cache.
        wireMock.resetAll();
        wireMock.stubFor(get(urlPathEqualTo("/seat-categories")).willReturn(aResponse().withStatus(503)));
        assertTrue(seats.fetchAllocatedSeats(eventId).isEmpty());
    }

    // ---- organizers --------------------------------------------------------

    private static void stubOrganizers(String dataJson) {
        wireMock.stubFor(post(urlPathEqualTo("/users/internal/tenants/lookup-by-uuid"))
                .willReturn(okJson("{\"code\":\"200 OK\",\"message\":\"ok\",\"data\":" + dataJson + "}")));
    }

    @Test
    void organizers_areCachedPerUuid_andOnlyMissesAreAskedFor() {
        UUID known = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        stubOrganizers("[{\"userUuid\":\"" + known + "\",\"businessName\":\"Showtime\"}]");
        assertEquals("Showtime", organizers.organizersByUserUuids(List.of(known)).get(known).getBusinessName());

        wireMock.resetAll();
        stubOrganizers("[{\"userUuid\":\"" + other + "\",\"businessName\":\"Other\"}]");
        Map<UUID, OrganizerDTO> both = organizers.organizersByUserUuids(List.of(known, other));

        assertEquals("Showtime", both.get(known).getBusinessName());
        assertEquals("Other", both.get(other).getBusinessName());
        // Only the miss went over the wire.
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/users/internal/tenants/lookup-by-uuid"))
                .withRequestBody(matchingJsonPath("$.userUuids[0]", equalTo(other.toString())))
                .withRequestBody(matchingJsonPath("$.userUuids.length()", equalTo("1"))));

        organizers.organizersByUserUuids(List.of(known, other));
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/users/internal/tenants/lookup-by-uuid")));
    }

    @Test
    void anOrganizerWithNoProfile_isRememberedAsAbsent() {
        UUID noProfile = UUID.randomUUID();
        stubOrganizers("[]");

        assertTrue(organizers.organizersByUserUuids(List.of(noProfile)).isEmpty());
        assertTrue(organizers.organizersByUserUuids(List.of(noProfile)).isEmpty());
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/users/internal/tenants/lookup-by-uuid")));
    }

    @Test
    void aFailedOrganizerLookup_isNotCached() {
        UUID id = UUID.randomUUID();
        wireMock.stubFor(post(urlPathEqualTo("/users/internal/tenants/lookup-by-uuid"))
                .willReturn(aResponse().withStatus(500)));
        assertTrue(organizers.organizersByUserUuids(List.of(id)).isEmpty());

        wireMock.resetAll();
        stubOrganizers("[{\"userUuid\":\"" + id + "\",\"businessName\":\"Showtime\"}]");
        assertEquals("Showtime", organizers.organizersByUserUuids(List.of(id)).get(id).getBusinessName());
    }

    @Test
    void aMalformedOrganizerAnswer_isNotCachedAsAbsent() {
        UUID id = UUID.randomUUID();
        wireMock.stubFor(post(urlPathEqualTo("/users/internal/tenants/lookup-by-uuid"))
                .willReturn(okJson("{\"code\":\"200 OK\",\"message\":\"ok\"}")));
        assertTrue(organizers.organizersByUserUuids(List.of(id)).isEmpty());

        wireMock.resetAll();
        stubOrganizers("[{\"userUuid\":\"" + id + "\",\"businessName\":\"Showtime\"}]");
        assertEquals("Showtime", organizers.organizersByUserUuids(List.of(id)).get(id).getBusinessName());
    }

    @Test
    void cachedOrganizers_areIndependentCopies() {
        UUID id = UUID.randomUUID();
        stubOrganizers("[{\"userUuid\":\"" + id + "\",\"businessName\":\"Showtime\"}]");
        organizers.organizersByUserUuids(List.of(id)).get(id).setBusinessName("defaced");
        assertEquals("Showtime", organizers.organizersByUserUuids(List.of(id)).get(id).getBusinessName());
    }

    // ---- plumbing ----------------------------------------------------------

    private static RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofSeconds(15)); // generous: a cold WireMock is slow on the first call
        return new RestTemplate(factory);
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
