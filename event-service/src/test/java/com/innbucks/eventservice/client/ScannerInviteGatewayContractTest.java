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
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The wire contract with user-service's {@code POST /users/internal/scanner-invites}
 * (V20): the number, the organizer, the event and the scanner id, and never a
 * message of our own (user-service owns the wording). Best-effort on every
 * failure shape.
 */
class ScannerInviteGatewayContractTest {

    private static final PooledHttpClient POOL = new PooledHttpClient(new PooledHttpClientProperties());
    private static final String PATH = "/users/internal/scanner-invites";
    private static final UUID SCANNER = UUID.fromString("4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e");

    private static WireMockServer wireMock;
    private static ScannerInviteGateway gateway;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        gateway = new ScannerInviteGateway(restTemplate(), "http://localhost:" + wireMock.port(), "test-token");
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
    @DisplayName("an invite: the number, the organizer, the event and the scanner, with the internal token")
    void invite_verifiesOutboundContract() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"202 ACCEPTED\",\"message\":\"Invite message queued\",\"data\":null}")));

        gateway.sendInvite(SCANNER, "+263771234567", "Harare Arena Events", "Jazz on the Lawn", List.of("SCAN"));

        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withHeader("X-Internal-Token", equalTo("test-token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.phoneNumber", equalTo("+263771234567")))
                .withRequestBody(matchingJsonPath("$.organizerName", equalTo("Harare Arena Events")))
                .withRequestBody(matchingJsonPath("$.eventTitle", equalTo("Jazz on the Lawn")))
                .withRequestBody(matchingJsonPath("$.scannerId", equalTo(SCANNER.toString())))
                .withRequestBody(matchingJsonPath("$.roles.length()", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.roles[0]", equalTo("SCAN")))
                // The wording is user-service's, never sent from here.
                .withRequestBody(notContaining("message")));
    }

    @Test
    @DisplayName("V21: a sales agent's invite says SELL, and a member who does both says both, in order")
    void roles_rideTheBody_asTheRowHasThem() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202)));

        gateway.sendInvite(SCANNER, "+263771234567", "Harare Arena Events", "Jazz on the Lawn", List.of("SELL"));
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.roles.length()", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.roles[0]", equalTo("SELL"))));

        wireMock.resetRequests();
        gateway.sendInvite(SCANNER, "+263771234567", "Harare Arena Events", "Jazz on the Lawn",
                List.of("SCAN", "SELL"));
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.roles.length()", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.roles[0]", equalTo("SCAN")))
                .withRequestBody(matchingJsonPath("$.roles[1]", equalTo("SELL"))));
    }

    @Test
    @DisplayName("an unknown organizer name or a missing title is left out, not sent blank")
    void blanks_areLeftOut() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202)));

        gateway.sendInvite(SCANNER, "+263771234567", null, " ", null);

        wireMock.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(notContaining("organizerName"))
                .withRequestBody(notContaining("eventTitle"))
                // No roles = user-service's default, SCAN: today's message.
                .withRequestBody(notContaining("roles")));
    }

    @Test
    @DisplayName("guard rail: no number makes no call")
    void guardRail_noNetworkCall() {
        gateway.sendInvite(SCANNER, null, "Harare Arena Events", "Jazz on the Lawn", List.of("SCAN"));
        gateway.sendInvite(SCANNER, " ", "Harare Arena Events", "Jazz on the Lawn", List.of("SELL"));

        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("best-effort: a 400 (bad number), a 401 and a 5xx never throw")
    void refusals_swallowed() {
        for (int status : new int[] {400, 401, 503}) {
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)));
            assertDoesNotThrow(() -> gateway.sendInvite(SCANNER, "+263771234567", "Harare Arena Events", null,
                    List.of("SCAN")));
        }
    }

    @Test
    @DisplayName("best-effort: connection refused does not throw")
    void connectionRefused_swallowed() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        ScannerInviteGateway dead = new ScannerInviteGateway(
                restTemplate(), "http://localhost:" + closedPort, "test-token");

        assertDoesNotThrow(() -> dead.sendInvite(SCANNER, "+263771234567", "Harare Arena Events", null,
                List.of("SCAN")));
    }
}
