package com.innbucks.bookingservice.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.bookingservice.config.InnbucksGatewayProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link SmsNotificationClient}: pins behaviour against the
 * response shapes the {@code innbucks-core-gateway} adapter's
 * {@code POST /notifications/sms} endpoint actually returns. Mirrors the
 * canonical {@code SmsNotificationClientContractTest} in user-service. Pure
 * JUnit + WireMock, no Spring context.
 */
class SmsNotificationClientContractTest {

    private static WireMockServer wireMock;
    private static SmsNotificationClient client;

    @BeforeAll
    static void startWireMockAndWireClient() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        InnbucksGatewayProperties props = new InnbucksGatewayProperties();
        props.setBaseUrl("http://localhost:" + wireMock.port());
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(2000);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        RestClient restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
        client = new SmsNotificationClient(restClient);
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @Test
    @DisplayName("happy path: gateway returns 200 SUBMITTED → client returns normally; verifies wire contract")
    void sendSms_happyPath() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reference\":\"r1\",\"status\":\"SUBMITTED\"}")));

        client.sendSms("+263782606983", "InnBucks: booking confirmed.", "BOOKING-CONFIRM-1");

        wireMock.verify(postRequestedFor(urlEqualTo("/notifications/sms"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.destination", equalTo("+263782606983")))
                .withRequestBody(matchingJsonPath("$.message", equalTo("InnBucks: booking confirmed.")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("BOOKING-CONFIRM-1")))
                .withRequestBody(matchingJsonPath("$.senderId", equalTo("INNBUCKS"))));
    }

    @Test
    @DisplayName("gateway returns 502 (messenger rejected): NotificationDeliveryException with status")
    void sendSms_gateway502_throws() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse()
                        .withStatus(502)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reference\":\"r2\",\"status\":\"FAILED\","
                                + "\"error\":\"messenger-interface rejected: HTTP 500\"}")));

        assertThatThrownBy(() -> client.sendSms("+263782606983", "msg", "r2"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 502");
    }

    @Test
    @DisplayName("gateway returns 503 (messenger unreachable): NotificationDeliveryException")
    void sendSms_gateway503_throws() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reference\":\"r3\",\"status\":\"FAILED\","
                                + "\"error\":\"messenger-interface unreachable\"}")));

        assertThatThrownBy(() -> client.sendSms("+263782606983", "msg", "r3"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    @DisplayName("gateway unreachable (connection refused): NotificationDeliveryException")
    void sendSms_unreachable_throws() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(500));
        RestClient dead = RestClient.builder()
                .baseUrl("http://localhost:" + closedPort)
                .requestFactory(factory)
                .build();
        SmsNotificationClient unreachable = new SmsNotificationClient(dead);

        assertThatThrownBy(() -> unreachable.sendSms("+263782606983", "msg", "r4"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("blank destination is rejected client-side; no HTTP call")
    void sendSms_blankDestination_noNetwork() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client.sendSms("", "msg", "r5"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("recipient is blank");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/notifications/sms")));
    }

    @Test
    @DisplayName("null reference is replaced with TKT-SMS-<uuid> on the wire")
    void sendSms_nullReference_autoGenerated() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse().withStatus(200)));

        client.sendSms("+263782606983", "msg", null);

        wireMock.verify(postRequestedFor(urlEqualTo("/notifications/sms"))
                .withRequestBody(matchingJsonPath("$.reference",
                        matching("TKT-SMS-[0-9a-f-]{36}"))));
    }
}
