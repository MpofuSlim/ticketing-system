package com.innbucks.eventservice.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Contract test for {@link OrganizerNotificationGateway} — the S2S client that
 * asks user-service to notify an organizer on event approval. Pins the wire
 * shape of {@code POST /users/internal/{uuid}/notify} (X-Internal-Token, JSON
 * body with subject + message) and the best-effort no-throw contract.
 *
 * <p>Pure JUnit + WireMock, no Spring context (per the CLAUDE.md convention).
 */
class OrganizerNotificationGatewayContractTest {

    private static WireMockServer wireMock;
    private static OrganizerNotificationGateway gateway;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        gateway = new OrganizerNotificationGateway(
                restTemplate(), "http://localhost:" + wireMock.port(), "test-token",
                "https://ticketize.example.test");
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
    @DisplayName("approve notify 202: POSTs to /notify with X-Internal-Token, JSON subject + message carrying the title")
    void happyPath_verifiesOutboundContract() {
        UUID organizer = UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo("/users/internal/" + organizer + "/notify"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"202\",\"message\":\"Notification queued\",\"data\":null}")));

        gateway.notifyEventApproved(organizer, "Summer Concert");

        wireMock.verify(postRequestedFor(urlEqualTo("/users/internal/" + organizer + "/notify"))
                .withHeader("X-Internal-Token", equalTo("test-token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Your event has been approved")))
                .withRequestBody(matchingJsonPath("$.message", containing("Summer Concert"))));
    }

    @Test
    @DisplayName("activated notify: Ticketize subject, title, and the shareable event link")
    void activated_verifiesOutboundContract() {
        UUID organizer = UUID.randomUUID();
        UUID eventId = UUID.fromString("20c96393-8ac8-480a-93d0-ef89981c53e0");
        wireMock.stubFor(post(urlEqualTo("/users/internal/" + organizer + "/notify"))
                .willReturn(aResponse().withStatus(202)));

        gateway.notifyEventActivated(organizer, eventId, "Pink Fun Run");

        wireMock.verify(postRequestedFor(urlEqualTo("/users/internal/" + organizer + "/notify"))
                .withHeader("X-Internal-Token", equalTo("test-token"))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Your event is now live on Ticketize")))
                .withRequestBody(matchingJsonPath("$.message", containing("Pink Fun Run")))
                // The shareable public event link, built from the configured base URL.
                .withRequestBody(matchingJsonPath("$.message",
                        containing("https://ticketize.example.test/events/" + eventId))));
    }

    @Test
    @DisplayName("deactivated notify: subject says deactivated, message carries the title")
    void deactivated_verifiesOutboundContract() {
        UUID organizer = UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo("/users/internal/" + organizer + "/notify"))
                .willReturn(aResponse().withStatus(202)));

        gateway.notifyEventDeactivated(organizer, "Pink Fun Run");

        wireMock.verify(postRequestedFor(urlEqualTo("/users/internal/" + organizer + "/notify"))
                .withHeader("X-Internal-Token", equalTo("test-token"))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Your event has been deactivated")))
                .withRequestBody(matchingJsonPath("$.message", containing("Pink Fun Run"))));
    }

    @Test
    @DisplayName("rejected notify: subject says not approved, message carries the title")
    void rejected_verifiesOutboundContract() {
        UUID organizer = UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo("/users/internal/" + organizer + "/notify"))
                .willReturn(aResponse().withStatus(202)));

        gateway.notifyEventRejected(organizer, "Pink Fun Run");

        wireMock.verify(postRequestedFor(urlEqualTo("/users/internal/" + organizer + "/notify"))
                .withHeader("X-Internal-Token", equalTo("test-token"))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Your event was not approved")))
                .withRequestBody(matchingJsonPath("$.message", containing("Pink Fun Run"))));
    }

    @Test
    @DisplayName("guard rail: null organizer uuid makes NO HTTP call (all four methods)")
    void nullUuid_noNetworkCall() {
        gateway.notifyEventApproved(null, "Summer Concert");
        gateway.notifyEventActivated(null, UUID.randomUUID(), "Summer Concert");
        gateway.notifyEventDeactivated(null, "Summer Concert");
        gateway.notifyEventRejected(null, "Summer Concert");

        wireMock.verify(0, postRequestedFor(urlPathMatching("/users/internal/.*/notify")));
    }

    @Test
    @DisplayName("blank title falls back to a safe default in the message")
    void blankTitle_defaultsMessage() {
        UUID organizer = UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo("/users/internal/" + organizer + "/notify"))
                .willReturn(aResponse().withStatus(202)));

        gateway.notifyEventApproved(organizer, "  ");

        wireMock.verify(postRequestedFor(urlEqualTo("/users/internal/" + organizer + "/notify"))
                .withRequestBody(matchingJsonPath("$.message", containing("Your event"))));
    }

    @Test
    @DisplayName("best-effort: user-service 5xx does not throw (approval must not fail)")
    void serverError_swallowed() {
        UUID organizer = UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo("/users/internal/" + organizer + "/notify"))
                .willReturn(aResponse().withStatus(503)));

        assertDoesNotThrow(() -> gateway.notifyEventApproved(organizer, "Summer Concert"));
    }

    @Test
    @DisplayName("best-effort: connection refused does not throw")
    void connectionRefused_swallowed() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        OrganizerNotificationGateway dead = new OrganizerNotificationGateway(
                restTemplate(), "http://localhost:" + closedPort, "test-token",
                "https://ticketize.example.test");

        assertDoesNotThrow(() -> dead.notifyEventApproved(UUID.randomUUID(), "Summer Concert"));
    }
}
