package com.innbucks.bookingservice.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for booking-service's {@link EmailNotificationClient} against
 * the {@code innbucks-core-gateway} {@code POST /notifications/email} adapter
 * (→ messenger-interface). Mirrors user-service's client of the same name and
 * the WireMock convention: pins the OUTBOUND payload shape + each response the
 * adapter actually returns, so a wire-contract drift fails the build.
 */
class EmailNotificationClientContractTest {

    private static WireMockServer wireMock;
    private static EmailNotificationClient client;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        client = new EmailNotificationClient(
                RestClient.builder().baseUrl("http://localhost:" + wireMock.port()).build());
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
    @DisplayName("happy path: posts to[]/subject/body/isHtml=true; SUBMITTED → no throw")
    void sendHtmlEmail_postsDocumentedShape() {
        wireMock.stubFor(post(urlEqualTo("/notifications/email"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reference\":\"BOOKING-CONFIRM-1\",\"status\":\"SUBMITTED\"}")));

        client.sendHtmlEmail("rufaro@example.com", "Your InnBucks tickets",
                "<div>tickets</div>", "BOOKING-CONFIRM-1");

        wireMock.verify(postRequestedFor(urlEqualTo("/notifications/email"))
                .withRequestBody(matchingJsonPath("$.to[0]", equalTo("rufaro@example.com")))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Your InnBucks tickets")))
                .withRequestBody(matchingJsonPath("$.body", equalTo("<div>tickets</div>")))
                .withRequestBody(matchingJsonPath("$.isHtml", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("BOOKING-CONFIRM-1"))));
    }

    @Test
    @DisplayName("blank recipient/subject/body: guarded before the network")
    void blankInputs_neverHitTheWire() {
        assertThatThrownBy(() -> client.sendHtmlEmail(" ", "s", "<b>b</b>", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> client.sendHtmlEmail("a@b.com", " ", "<b>b</b>", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> client.sendHtmlEmail("a@b.com", "s", " ", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        wireMock.verify(0, postRequestedFor(urlEqualTo("/notifications/email")));
    }

    @Test
    @DisplayName("blank reference: auto-fills TKT-EMAIL-<uuid>")
    void blankReference_autoFilled() {
        wireMock.stubFor(post(urlEqualTo("/notifications/email"))
                .willReturn(aResponse().withStatus(200)));

        client.sendHtmlEmail("rufaro@example.com", "subj", "<b>b</b>", null);

        wireMock.verify(postRequestedFor(urlEqualTo("/notifications/email"))
                .withRequestBody(matchingJsonPath("$.reference", matching("TKT-EMAIL-[0-9a-f-]{36}"))));
    }

    @Test
    @DisplayName("502 (messenger-interface rejected) → NotificationDeliveryException")
    void gatewayRejected_throws() {
        wireMock.stubFor(post(urlEqualTo("/notifications/email"))
                .willReturn(aResponse().withStatus(502)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reference\":\"r\",\"status\":\"FAILED\",\"error\":\"messenger-interface rejected: HTTP 500\"}")));

        assertThatThrownBy(() -> client.sendHtmlEmail("a@b.com", "s", "<b>b</b>", "r"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("502");
    }

    @Test
    @DisplayName("503 (messenger-interface unreachable) → NotificationDeliveryException")
    void gatewayUnavailable_throws() {
        wireMock.stubFor(post(urlEqualTo("/notifications/email"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.sendHtmlEmail("a@b.com", "s", "<b>b</b>", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    @DisplayName("connect refused: NotificationDeliveryException (separate dead-port client)")
    void connectRefused_throws() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        EmailNotificationClient dead = new EmailNotificationClient(
                RestClient.builder().baseUrl("http://localhost:" + closedPort).build());

        assertThatThrownBy(() -> dead.sendHtmlEmail("a@b.com", "s", "<b>b</b>", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    @DisplayName("200 SUBMITTED: returns cleanly (the booking-confirm happy path)")
    void submitted_returnsClean() {
        wireMock.stubFor(post(urlEqualTo("/notifications/email"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reference\":\"r\",\"status\":\"SUBMITTED\"}")));

        assertThatCode(() -> client.sendHtmlEmail("a@b.com", "s", "<b>b</b>", "r"))
                .doesNotThrowAnyException();
    }
}
