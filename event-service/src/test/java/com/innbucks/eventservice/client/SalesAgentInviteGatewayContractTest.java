package com.innbucks.eventservice.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire contract with user-service's sales-agent portal invites (V21):
 * {@code POST /users/internal/sales-agent-invites} and {@code DELETE
 * /users/internal/sales-agent-invites/{teamMemberId}}. One case per response
 * shape: the 202, each 400 errorCode user-service answers (passed through to
 * the organizer), the 401 of a wrong token, the 503 of an unconfigured
 * user-service, a 5xx, connection refused, and the guard rails that never
 * reach the network.
 */
class SalesAgentInviteGatewayContractTest {

    private static final PooledHttpClient POOL = new PooledHttpClient(new PooledHttpClientProperties());
    private static final String PATH = "/users/internal/sales-agent-invites";
    private static final UUID TEAM_MEMBER = UUID.fromString("4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e");
    private static final UUID ORGANIZER = UUID.fromString("8f14e45f-ceea-467a-9b0e-1c2d3e4f5a6b");
    private static final String PHONE = "+263771234567";
    private static final String EMAIL = "tendai.moyo@gmail.com";

    private static WireMockServer wireMock;
    private static SalesAgentInviteGateway gateway;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        gateway = new SalesAgentInviteGateway(restTemplate(), "http://localhost:" + wireMock.port(), "test-token");
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

    private static SalesAgentInviteGateway.Outcome invite() {
        return gateway.invite(TEAM_MEMBER, ORGANIZER, "Harare Arena Events", PHONE, EMAIL);
    }

    private static void refusedWith(int status, String body) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    // ------------------------------------------------------------------- invite

    @Test
    @DisplayName("202: the team member, the organizer, the number and the address go with the internal token")
    void invite_202_verifiesOutboundContract() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"202 ACCEPTED\",\"message\":\"Portal invite sent\","
                        + "\"data\":{\"expiresAt\":\"2026-11-09T10:00:00Z\"}}")));

        SalesAgentInviteGateway.Outcome outcome = invite();

        assertThat(outcome).isEqualTo(new SalesAgentInviteGateway.Sent("2026-11-09T10:00:00Z"));
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withHeader("X-Internal-Token", equalTo("test-token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.teamMemberId", equalTo(TEAM_MEMBER.toString())))
                .withRequestBody(matchingJsonPath("$.organizerUuid", equalTo(ORGANIZER.toString())))
                .withRequestBody(matchingJsonPath("$.organizerName", equalTo("Harare Arena Events")))
                .withRequestBody(matchingJsonPath("$.phoneNumber", equalTo(PHONE)))
                .withRequestBody(matchingJsonPath("$.email", equalTo(EMAIL)))
                // user-service owns the wording and the link; neither is sent from here.
                .withRequestBody(notContaining("message"))
                .withRequestBody(notContaining("link")));
    }

    @Test
    @DisplayName("202 with no body, or no expiresAt: still sent")
    void invite_202_withoutABody_isSent() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202)));

        assertThat(invite()).isEqualTo(new SalesAgentInviteGateway.Sent(null));
    }

    @Test
    @DisplayName("an unknown organizer name is left out, not sent blank")
    void invite_withoutAnOrganizerName_leavesItOut() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202)));

        gateway.invite(TEAM_MEMBER, ORGANIZER, null, PHONE, EMAIL);

        wireMock.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(notContaining("organizerName")));
    }

    @Test
    @DisplayName("400 invalid_email: user-service's code and words come back for the organizer")
    void invite_400_invalidEmail_isPassedThrough() {
        refusedWith(400, "{\"code\":\"400 BAD_REQUEST\",\"message\":\"That isn't a valid email address.\","
                + "\"data\":{\"errorCode\":\"invalid_email\",\"field\":\"email\"}}");

        assertThat(invite()).isEqualTo(new SalesAgentInviteGateway.Refused("invalid_email",
                "That isn't a valid email address.", "email"));
    }

    @Test
    @DisplayName("400 invalid_phone is passed through")
    void invite_400_invalidPhone_isPassedThrough() {
        refusedWith(400, "{\"code\":\"400 BAD_REQUEST\",\"message\":\"That isn't a valid mobile number.\","
                + "\"data\":{\"errorCode\":\"invalid_phone\"}}");

        assertThat(invite()).isEqualTo(new SalesAgentInviteGateway.Refused("invalid_phone",
                "That isn't a valid mobile number.", null));
    }

    @Test
    @DisplayName("400 email_domain_reserved (an InnBucks address) is passed through")
    void invite_400_reservedDomain_isPassedThrough() {
        refusedWith(400, "{\"code\":\"400 BAD_REQUEST\",\"message\":\"InnBucks staff addresses can't be used "
                + "here. Your administrator will invite you.\",\"data\":{\"errorCode\":\"email_domain_reserved\","
                + "\"field\":\"email\"}}");

        assertThat(invite()).isEqualTo(new SalesAgentInviteGateway.Refused("email_domain_reserved",
                "InnBucks staff addresses can't be used here. Your administrator will invite you.", "email"));
    }

    @Test
    @DisplayName("400 with no usable body, or an odd errorCode: our own code and words, never theirs unchecked")
    void invite_400_unreadable_fallsBackToOurWords() {
        refusedWith(400, "<html>bad request</html>");
        assertThat(invite()).isEqualTo(new SalesAgentInviteGateway.Refused(
                SalesAgentInviteGateway.FALLBACK_ERROR_CODE, SalesAgentInviteGateway.FALLBACK_MESSAGE, null));

        refusedWith(400, "{\"message\":\"" + "x".repeat(400) + "\",\"data\":{\"errorCode\":\"Not A Code!\","
                + "\"field\":\"<script>\"}}");
        assertThat(invite()).isEqualTo(new SalesAgentInviteGateway.Refused(
                SalesAgentInviteGateway.FALLBACK_ERROR_CODE, SalesAgentInviteGateway.FALLBACK_MESSAGE, null));
    }

    @Test
    @DisplayName("401 (wrong token) is unavailable, not a refusal of the address")
    void invite_401_isUnavailable() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThat(invite()).isEqualTo(new SalesAgentInviteGateway.Unavailable("http_401"));
    }

    @Test
    @DisplayName("503 sales_agent_invites_unconfigured is unavailable, and is not retried")
    void invite_503_unconfigured_isUnavailable_andSentOnce() {
        refusedWith(503, "{\"code\":\"503 SERVICE_UNAVAILABLE\",\"message\":\"Portal invites are not set up.\","
                + "\"data\":{\"errorCode\":\"sales_agent_invites_unconfigured\"}}");

        assertThat(invite()).isEqualTo(new SalesAgentInviteGateway.Unavailable("http_503"));
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("500 and a dropped connection are unavailable")
    void invite_5xxAndFault_areUnavailable() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
        assertThat(invite()).isEqualTo(new SalesAgentInviteGateway.Unavailable("http_500"));

        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThat(invite()).isEqualTo(new SalesAgentInviteGateway.Unavailable("unreachable"));
    }

    @Test
    @DisplayName("connection refused is unavailable and never throws")
    void invite_connectionRefused_isUnavailable() throws Exception {
        SalesAgentInviteGateway dead = new SalesAgentInviteGateway(restTemplate(), "http://localhost:" + closedPort(),
                "test-token");

        assertThat(dead.invite(TEAM_MEMBER, ORGANIZER, "Harare Arena Events", PHONE, EMAIL))
                .isEqualTo(new SalesAgentInviteGateway.Unavailable("unreachable"));
    }

    @Test
    @DisplayName("guard rails: no address, no number or no ids make no call")
    void invite_guardRails_noNetworkCall() {
        assertThat(gateway.invite(TEAM_MEMBER, ORGANIZER, "Harare Arena Events", PHONE, " "))
                .isInstanceOf(SalesAgentInviteGateway.Refused.class);
        assertThat(gateway.invite(TEAM_MEMBER, ORGANIZER, "Harare Arena Events", PHONE, null))
                .isInstanceOf(SalesAgentInviteGateway.Refused.class);
        assertThat(gateway.invite(TEAM_MEMBER, ORGANIZER, "Harare Arena Events", null, EMAIL))
                .isInstanceOf(SalesAgentInviteGateway.Unavailable.class);
        assertThat(gateway.invite(null, ORGANIZER, "Harare Arena Events", PHONE, EMAIL))
                .isInstanceOf(SalesAgentInviteGateway.Unavailable.class);
        assertThat(gateway.invite(TEAM_MEMBER, null, "Harare Arena Events", PHONE, EMAIL))
                .isInstanceOf(SalesAgentInviteGateway.Unavailable.class);

        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    // ------------------------------------------------------------------- revoke

    @Test
    @DisplayName("revoke: DELETE by team member id with the internal token")
    void revoke_200_verifiesOutboundContract() {
        wireMock.stubFor(delete(urlEqualTo(PATH + "/" + TEAM_MEMBER)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"200 OK\",\"message\":\"Portal invites revoked\",\"data\":{\"revoked\":1}}")));

        assertThat(gateway.revoke(TEAM_MEMBER)).isTrue();

        wireMock.verify(1, deleteRequestedFor(urlEqualTo(PATH + "/" + TEAM_MEMBER))
                .withHeader("X-Internal-Token", equalTo("test-token")));
    }

    @Test
    @DisplayName("revoke: best-effort on a 401, a 5xx and connection refused")
    void revoke_failures_neverThrow() throws Exception {
        for (int status : new int[] {401, 404, 503}) {
            wireMock.stubFor(delete(urlEqualTo(PATH + "/" + TEAM_MEMBER)).willReturn(aResponse().withStatus(status)));
            assertThat(gateway.revoke(TEAM_MEMBER)).isFalse();
        }
        SalesAgentInviteGateway dead = new SalesAgentInviteGateway(restTemplate(), "http://localhost:" + closedPort(),
                "test-token");
        assertThat(dead.revoke(TEAM_MEMBER)).isFalse();
    }

    @Test
    @DisplayName("guard rail: revoke with no id makes no call")
    void revoke_guardRail_noNetworkCall() {
        assertThat(gateway.revoke(null)).isFalse();
        wireMock.verify(0, deleteRequestedFor(anyUrl()));
    }

    private static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
