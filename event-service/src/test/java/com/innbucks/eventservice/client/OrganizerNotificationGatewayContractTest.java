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
                restTemplate(), "http://localhost:" + wireMock.port(), "test-token");
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
    @DisplayName("guard rail: null organizer uuid makes NO HTTP call")
    void nullUuid_noNetworkCall() {
        gateway.notifyEventApproved(null, "Summer Concert");

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
                restTemplate(), "http://localhost:" + closedPort, "test-token");

        assertDoesNotThrow(() -> dead.notifyEventApproved(UUID.randomUUID(), "Summer Concert"));
    }
}
