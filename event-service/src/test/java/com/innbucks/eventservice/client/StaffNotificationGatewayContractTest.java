package com.innbucks.eventservice.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.eventservice.config.HttpClientConfig;
import com.innbucks.eventservice.config.PooledHttpClient;
import com.innbucks.eventservice.config.PooledHttpClientProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.net.ServerSocket;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The wire contract with user-service's {@code POST /users/internal/staff-notifications}
 * (V17): one call per notice, the type that decides the audience there, and
 * nothing that names an audience here. Best-effort on every failure shape.
 */
class StaffNotificationGatewayContractTest {

    private static final PooledHttpClient POOL = new PooledHttpClient(new PooledHttpClientProperties());
    private static final String PATH = "/users/internal/staff-notifications";

    private static WireMockServer wireMock;
    private static StaffNotificationGateway gateway;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        gateway = new StaffNotificationGateway(restTemplate(), "http://localhost:" + wireMock.port(), "test-token");
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
        return new HttpClientConfig().restTemplate(500, 2000, POOL);
    }

    @Test
    @DisplayName("submitted: type, subject, message, severity WARNING, the event and its console link")
    void submitted_verifiesOutboundContract() {
        UUID eventId = UUID.fromString("4b6d1f0e-2c8a-4e7b-9a31-7f5e2d9c0a11");
        UUID organizer = UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"202 ACCEPTED\",\"message\":\"Staff notified\",\"data\":{\"recipients\":3}}")));

        gateway.notifyReviewStaff(StaffNotificationGateway.EVENT_SUBMITTED, eventId,
                "Event awaiting approval: Pink Fun Run", "Harare Runners Club submitted \"Pink Fun Run\" for review.",
                organizer, "Harare Runners Club");

        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withHeader("X-Internal-Token", equalTo("test-token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.type", equalTo("EVENT_SUBMITTED")))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Event awaiting approval: Pink Fun Run")))
                .withRequestBody(matchingJsonPath("$.message", containing("submitted \"Pink Fun Run\"")))
                .withRequestBody(matchingJsonPath("$.severity", equalTo("WARNING")))
                .withRequestBody(matchingJsonPath("$.actorId", equalTo(organizer.toString())))
                .withRequestBody(matchingJsonPath("$.actorName", equalTo("Harare Runners Club")))
                .withRequestBody(matchingJsonPath("$.subjectKind", equalTo("EVENT")))
                .withRequestBody(matchingJsonPath("$.subjectId", equalTo(eventId.toString())))
                .withRequestBody(matchingJsonPath("$.deepLink", equalTo("/ticketing/events/" + eventId)))
                // The audience is user-service's to decide, never named here.
                .withRequestBody(notContaining("roles")));
    }

    @Test
    @DisplayName("published: type EVENT_PUBLISHED, severity INFO")
    void published_isInfo() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202)));

        gateway.notifyReviewStaff(StaffNotificationGateway.EVENT_PUBLISHED, UUID.randomUUID(),
                "New event published: Pink Fun Run", "It is live.", UUID.randomUUID(), null);

        wireMock.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.type", equalTo("EVENT_PUBLISHED")))
                .withRequestBody(matchingJsonPath("$.severity", equalTo("INFO")))
                .withRequestBody(notContaining("actorName")));
    }

    @Test
    @DisplayName("guard rail: no event or no type makes no call")
    void guardRails_noNetworkCall() {
        gateway.notifyReviewStaff(StaffNotificationGateway.EVENT_SUBMITTED, null, "s", "m", null, null);
        gateway.notifyReviewStaff(null, UUID.randomUUID(), "s", "m", null, null);

        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("best-effort: a 400 (unknown type), a 401 and a 5xx never throw")
    void refusals_swallowed() {
        for (int status : new int[] {400, 401, 503}) {
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)));
            assertDoesNotThrow(() -> gateway.notifyReviewStaff(StaffNotificationGateway.EVENT_SUBMITTED,
                    UUID.randomUUID(), "s", "m", null, null));
        }
    }

    @Test
    @DisplayName("best-effort: connection refused does not throw")
    void connectionRefused_swallowed() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        StaffNotificationGateway dead = new StaffNotificationGateway(
                restTemplate(), "http://localhost:" + closedPort, "test-token");

        assertDoesNotThrow(() -> dead.notifyReviewStaff(StaffNotificationGateway.EVENT_SUBMITTED,
                UUID.randomUUID(), "s", "m", null, null));
    }
}
