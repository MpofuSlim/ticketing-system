package com.innbucks.eventservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for {@link UserUuidLookupGateway}, per the CLAUDE.md
 * cross-service-client mandate. Pins the wire shape of BOTH internal calls
 * event-service makes to user-service:
 *
 * <ul>
 *   <li>{@code GET /users/internal/team-members/{uuid}/assigned-events} —
 *       the team-member event-access authorization source. Its FAIL-CLOSED
 *       behaviour (empty list on any failure) IS a security control:
 *       deny-by-default for team-member event access must hold even when
 *       user-service is down. The {@code *_failsClosed} cases below break the
 *       build if a refactor ever turns that into fail-open or reshapes the
 *       response envelope.</li>
 *   <li>{@code POST /users/internal/users/by-email} — the best-effort
 *       email&rarr;uuid resolver used by the backfill runner.</li>
 * </ul>
 *
 * <p>Pure JUnit + WireMock, no Spring context (per the convention). The
 * production {@link RestTemplate} shape is reproduced (default JSON
 * converters) and pointed at WireMock; only host:port differs. The
 * connection-refused case points a SEPARATE gateway at a known-closed port so
 * the shared WireMock is never stopped/restarted (which would hand the next
 * test a different dynamic port).
 */
class UserUuidLookupGatewayContractTest {

    private static WireMockServer wireMock;
    private static UserUuidLookupGateway gateway;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        gateway = new UserUuidLookupGateway(
                restTemplate(),
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

    /** Mirrors the prod RestTemplate, with short timeouts so the refused-connect case fails fast. */
    private static RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(2000));
        return new RestTemplate(factory);
    }

    // ============ assignedEventIdsFor — the team-member access control ============

    @Test
    @DisplayName("assigned-events 200: parses the data array of event UUIDs and sends X-Internal-Token on a GET")
    void assignedEvents_happyPath_parsesAndSendsToken() {
        UUID member = UUID.randomUUID();
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        String path = "/users/internal/team-members/" + member + "/assigned-events";
        wireMock.stubFor(get(urlEqualTo(path)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"200\",\"message\":\"ok\",\"data\":[\""
                        + e1 + "\",\"" + e2 + "\"]}")));

        List<UUID> out = gateway.assignedEventIdsFor(member);

        assertEquals(List.of(e1, e2), out);
        wireMock.verify(getRequestedFor(urlEqualTo(path))
                .withHeader("X-Internal-Token", equalTo("test-token")));
    }

    @Test
    @DisplayName("assigned-events with empty/absent data array → empty list")
    void assignedEvents_emptyData_returnsEmpty() {
        wireMock.stubFor(get(urlMatching("/users/internal/team-members/.*/assigned-events"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":[]}")));

        assertTrue(gateway.assignedEventIdsFor(UUID.randomUUID()).isEmpty());
    }

    @Test
    @DisplayName("assigned-events skips a malformed UUID in the data array, keeps the valid ones")
    void assignedEvents_malformedUuid_isSkipped() {
        UUID member = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        String path = "/users/internal/team-members/" + member + "/assigned-events";
        wireMock.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\":[\"not-a-uuid\",\"" + good + "\"]}")));

        assertEquals(List.of(good), gateway.assignedEventIdsFor(member));
    }

    @Test
    @DisplayName("SECURITY: a 5xx from user-service yields an empty list (fail CLOSED — never fail open)")
    void assignedEvents_serverError_failsClosed() {
        wireMock.stubFor(get(urlMatching("/users/internal/team-members/.*/assigned-events"))
                .willReturn(aResponse().withStatus(503)));

        assertTrue(gateway.assignedEventIdsFor(UUID.randomUUID()).isEmpty(),
                "deny-by-default: an unreachable assignment source must grant NO events");
    }

    @Test
    @DisplayName("SECURITY: user-service unreachable (connection refused) yields an empty list (fail CLOSED)")
    void assignedEvents_connectionRefused_failsClosed() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        } // socket closed -> the OS refuses any connect to this port
        UserUuidLookupGateway dead = new UserUuidLookupGateway(
                restTemplate(), new ObjectMapper(), "http://localhost:" + closedPort, "test-token");

        assertTrue(dead.assignedEventIdsFor(UUID.randomUUID()).isEmpty());
    }

    // ============ resolveUuidsByEmail — best-effort backfill resolver ============

    @Test
    @DisplayName("by-email 200: parses email->uuid rows and POSTs the emails with X-Internal-Token + JSON body")
    void resolveByEmail_happyPath_parsesAndVerifiesOutboundContract() {
        UUID u = UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo("/users/internal/users/by-email"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":[{\"email\":\"organizer@example.com\",\"userUuid\":\""
                                + u + "\"}]}")));

        Map<String, UUID> out = gateway.resolveUuidsByEmail(List.of("organizer@example.com"));

        assertEquals(u, out.get("organizer@example.com"));
        wireMock.verify(postRequestedFor(urlEqualTo("/users/internal/users/by-email"))
                .withHeader("X-Internal-Token", equalTo("test-token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.emails[0]", equalTo("organizer@example.com"))));
    }

    @Test
    @DisplayName("by-email guard rail: blank/empty input makes NO HTTP call")
    void resolveByEmail_blankInput_noNetworkCall() {
        assertTrue(gateway.resolveUuidsByEmail(List.of()).isEmpty());
        assertTrue(gateway.resolveUuidsByEmail(List.of("   ")).isEmpty());

        wireMock.verify(0, postRequestedFor(urlEqualTo("/users/internal/users/by-email")));
    }

    @Test
    @DisplayName("by-email 5xx → empty map (best-effort; the backfill retries on the next startup)")
    void resolveByEmail_serverError_returnsEmpty() {
        wireMock.stubFor(post(urlEqualTo("/users/internal/users/by-email"))
                .willReturn(aResponse().withStatus(500)));

        assertTrue(gateway.resolveUuidsByEmail(List.of("a@b.co")).isEmpty());
    }

    @Test
    @DisplayName("by-email skips a row whose userUuid is malformed")
    void resolveByEmail_malformedUuid_isSkipped() {
        wireMock.stubFor(post(urlEqualTo("/users/internal/users/by-email"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":[{\"email\":\"bad@x.co\",\"userUuid\":\"nope\"}]}")));

        assertTrue(gateway.resolveUuidsByEmail(List.of("bad@x.co")).isEmpty());
    }
}
