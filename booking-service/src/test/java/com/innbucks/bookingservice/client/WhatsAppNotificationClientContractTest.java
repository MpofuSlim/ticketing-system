package com.innbucks.bookingservice.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.bookingservice.config.WhatsAppProperties;
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
 * Contract test for {@link WhatsAppNotificationClient}: pins behaviour against
 * the response shapes the external WhatsApp gateway's
 * {@code POST /api/messages/custom-notification} endpoint actually returns,
 * per the CLAUDE.md WireMock convention. Pure JUnit + WireMock, no Spring
 * context — surgical and fast.
 *
 * <p>The client is constructed with the same {@link RestClient} shape the prod
 * {@code WhatsAppClientConfig} bean produces, just pointed at WireMock.
 */
class WhatsAppNotificationClientContractTest {

    private static WireMockServer wireMock;
    private static WhatsAppNotificationClient client;
    private static WhatsAppProperties props;

    @BeforeAll
    static void startWireMockAndWireClient() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        props = new WhatsAppProperties();
        props.setBaseUrl("http://localhost:" + wireMock.port());
        props.setApiKey("test-api-key");
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(2000);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        RestClient restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
        client = new WhatsAppNotificationClient(restClient, props);
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
    @DisplayName("happy path: gateway returns 200 → client returns normally; verifies wire contract")
    void sendCustomNotification_happyPath_postsExpectedPayloadAndHeader() {
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"queued\"}")));

        client.sendCustomNotification("+263782606983", "InnBucks: booking INN-X confirmed.");

        wireMock.verify(postRequestedFor(urlEqualTo("/api/messages/custom-notification"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("x-api-key", equalTo("test-api-key"))
                .withRequestBody(matchingJsonPath("$.to", equalTo("+263782606983")))
                .withRequestBody(matchingJsonPath("$.notification",
                        equalTo("InnBucks: booking INN-X confirmed."))));
    }

    @Test
    @DisplayName("gateway returns 4xx (auth / bad payload) → NotificationDeliveryException with HTTP code")
    void sendCustomNotification_gatewayReturns4xx_throws() {
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid api key\"}")));

        assertThatThrownBy(() -> client.sendCustomNotification("+263782606983", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    @DisplayName("gateway returns 5xx → NotificationDeliveryException so the listener falls back to SMS")
    void sendCustomNotification_gatewayReturns5xx_throws() {
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse().withStatus(503).withBody("upstream down")));

        assertThatThrownBy(() -> client.sendCustomNotification("+263782606983", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    @DisplayName("gateway unreachable (connect refused) → NotificationDeliveryException")
    void sendCustomNotification_unreachable_throws() throws Exception {
        // Grab a free port then close it so any connect attempt is refused —
        // keeps the shared WireMock untouched for the rest of the suite.
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
        WhatsAppNotificationClient unreachable = new WhatsAppNotificationClient(dead, props);

        assertThatThrownBy(() -> unreachable.sendCustomNotification("+263782606983", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("blank recipient is rejected client-side; no HTTP call")
    void sendCustomNotification_blankRecipient_noNetwork() {
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client.sendCustomNotification("  ", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("Recipient");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/messages/custom-notification")));
    }

    @Test
    @DisplayName("blank message is rejected client-side; no HTTP call")
    void sendCustomNotification_blankMessage_noNetwork() {
        assertThatThrownBy(() -> client.sendCustomNotification("+263782606983", " "))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("blank");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/messages/custom-notification")));
    }

    @Test
    @DisplayName("over-length message is rejected client-side; gateway never sees it")
    void sendCustomNotification_overLengthMessage_noNetwork() {
        String tooLong = "x".repeat(1601);
        assertThatThrownBy(() -> client.sendCustomNotification("+263782606983", tooLong))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("1600");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/messages/custom-notification")));
    }

    @Test
    @DisplayName("e-ticket happy path: 200 → returns normally; pins the to/eventName/qrCodePath wire shape + header")
    void sendEventQrCode_happyPath_postsExpectedPayloadAndHeader() {
        wireMock.stubFor(post(urlEqualTo("/api/messages/event-qr-code"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"queued\"}")));

        client.sendEventQrCode("+263782606983", "InnBucks Annual Gala 2025",
                "/bookings/3fa85f64-5717-4562-b3fc-2c963f66afa6/tickets/20260615-22135L/qr");

        wireMock.verify(postRequestedFor(urlEqualTo("/api/messages/event-qr-code"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("x-api-key", equalTo("test-api-key"))
                .withRequestBody(matchingJsonPath("$.to", equalTo("+263782606983")))
                .withRequestBody(matchingJsonPath("$.eventName", equalTo("InnBucks Annual Gala 2025")))
                .withRequestBody(matchingJsonPath("$.qrCodePath",
                        equalTo("/bookings/3fa85f64-5717-4562-b3fc-2c963f66afa6/tickets/20260615-22135L/qr"))));
    }

    @Test
    @DisplayName("e-ticket gateway 4xx → NotificationDeliveryException with HTTP code")
    void sendEventQrCode_gatewayReturns4xx_throws() {
        wireMock.stubFor(post(urlEqualTo("/api/messages/event-qr-code"))
                .willReturn(aResponse().withStatus(422).withBody("{\"error\":\"template rejected\"}")));

        assertThatThrownBy(() -> client.sendEventQrCode("+263782606983", "Gala", "/qrcodes/t.png"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 422");
    }

    @Test
    @DisplayName("e-ticket gateway unreachable → NotificationDeliveryException")
    void sendEventQrCode_unreachable_throws() throws Exception {
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
        WhatsAppNotificationClient unreachable = new WhatsAppNotificationClient(dead, props);

        assertThatThrownBy(() -> unreachable.sendEventQrCode("+263782606983", "Gala", "/qrcodes/t.png"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("e-ticket: a non-relative qrCodePath is rejected client-side (gateway prepends BASE_URL)")
    void sendEventQrCode_absolutePath_noNetwork() {
        assertThatThrownBy(() -> client.sendEventQrCode("+263782606983", "Gala",
                "https://api.example.com/qrcodes/t.png"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("qrCodePath");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/messages/event-qr-code")));
    }

    @Test
    @DisplayName("e-ticket: blank eventName / recipient rejected client-side; gateway never sees it")
    void sendEventQrCode_blankInputs_noNetwork() {
        assertThatThrownBy(() -> client.sendEventQrCode("+263782606983", "  ", "/qrcodes/t.png"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("eventName");
        assertThatThrownBy(() -> client.sendEventQrCode(" ", "Gala", "/qrcodes/t.png"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("Recipient");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/messages/event-qr-code")));
    }
}
