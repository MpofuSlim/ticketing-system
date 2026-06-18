package com.innbucks.eventservice.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.ConfigBuilder;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Contract test for {@link BookingNotificationGateway}: pins the wire shape of
 * the internal call event-service makes to booking-service to trigger the
 * event-change attendee fan-out, per the CLAUDE.md cross-service-client mandate.
 *
 * <p>Uses a pass-through {@link CircuitBreakerFactory} so the breaker's
 * run/fallback contract is exercised deterministically without standing up
 * Resilience4j — happy path runs the supplier, a downstream failure routes to
 * the fallback (which the gateway uses to swallow, best-effort).
 */
class BookingNotificationGatewayTest {

    private static WireMockServer wireMock;
    private static BookingNotificationGateway gateway;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        gateway = new BookingNotificationGateway(
                new RestTemplate(),
                passthroughFactory(),
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

    @Test
    @DisplayName("UPDATED: posts to the internal path with X-Internal-Token and the change payload")
    void notifyEventChange_updated_postsTokenAndBody() {
        UUID eventId = UUID.randomUUID();
        String path = "/bookings/internal/events/" + eventId + "/change-notification";
        wireMock.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(202)));

        gateway.notifyEventChange(eventId, "UPDATED", "Jazz Night", "2026-07-01T19:00", "New Stadium");

        wireMock.verify(postRequestedFor(urlEqualTo(path))
                .withHeader("X-Internal-Token", equalTo("test-token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.changeType", equalTo("UPDATED")))
                .withRequestBody(matchingJsonPath("$.eventTitle", equalTo("Jazz Night")))
                .withRequestBody(matchingJsonPath("$.newStartDateTime", equalTo("2026-07-01T19:00")))
                .withRequestBody(matchingJsonPath("$.newVenue", equalTo("New Stadium"))));
    }

    @Test
    @DisplayName("CANCELLED: posts with null new-time/new-venue fields")
    void notifyEventChange_cancelled_postsNullChangeFields() {
        UUID eventId = UUID.randomUUID();
        String path = "/bookings/internal/events/" + eventId + "/change-notification";
        wireMock.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(202)));

        gateway.notifyEventChange(eventId, "CANCELLED", "Jazz Night", null, null);

        wireMock.verify(postRequestedFor(urlEqualTo(path))
                .withRequestBody(matchingJsonPath("$.changeType", equalTo("CANCELLED")))
                .withRequestBody(matchingJsonPath("$.eventTitle", equalTo("Jazz Night"))));
    }

    @Test
    @DisplayName("best-effort: a 5xx from booking-service is swallowed (never fails the organizer's action)")
    void notifyEventChange_downstream5xx_isSwallowed() {
        UUID eventId = UUID.randomUUID();
        String path = "/bookings/internal/events/" + eventId + "/change-notification";
        wireMock.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(503)));

        // The circuit-breaker fallback must absorb the failure — no exception escapes.
        assertDoesNotThrow(() ->
                gateway.notifyEventChange(eventId, "CANCELLED", "Jazz Night", null, null));
    }

    /**
     * Minimal pass-through CircuitBreakerFactory: run the supplier, and on any
     * throwable run the fallback — exactly the contract BookingNotificationGateway
     * relies on, with none of the Resilience4j wiring.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static CircuitBreakerFactory<?, ?> passthroughFactory() {
        return new CircuitBreakerFactory() {
            @Override
            public CircuitBreaker create(String id) {
                return new CircuitBreaker() {
                    @Override
                    public <T> T run(Supplier<T> toRun, Function<Throwable, T> fallback) {
                        try {
                            return toRun.get();
                        } catch (Throwable t) {
                            return fallback.apply(t);
                        }
                    }
                };
            }

            @Override
            protected ConfigBuilder configBuilder(String id) {
                return () -> null;
            }

            @Override
            public void configureDefault(Function defaultConfiguration) {
            }
        };
    }
}
