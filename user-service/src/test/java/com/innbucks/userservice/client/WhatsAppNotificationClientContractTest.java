package com.innbucks.userservice.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.userservice.config.WhatsAppProperties;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test: pins {@link WhatsAppNotificationClient}'s behaviour against
 * each response shape the external WhatsApp gateway has been observed to
 * return. Sibling of {@link SmsNotificationClientContractTest} — same audit-#10
 * template applied to the second notification channel.
 *
 * <p>The WhatsApp gateway is the OTP / approval fallback when messenger-interface
 * is down. We literally hit the 401 case live during this milestone (stale
 * api-key on the wasenda domain swap); pinning it as a test means a future
 * key-rotation or gateway-side error-shape change fails the build instead of
 * making OTP delivery silently brittle.
 *
 * <p>Pure JUnit + WireMock, no {@code @SpringBootTest} — keeps each case under
 * a second and surgical to the wire contract.
 */
class WhatsAppNotificationClientContractTest {

    private static final String API_KEY = "test-x-api-key";

    private static WireMockServer wireMock;
    private static WhatsAppNotificationClient client;

    @BeforeAll
    static void startWireMockAndWireClient() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        // Build the WhatsAppNotificationClient with the same RestClient shape
        // the prod WhatsAppClientConfig produces, just pointed at WireMock.
        WhatsAppProperties props = new WhatsAppProperties();
        props.setBaseUrl("http://localhost:" + wireMock.port());
        props.setApiKey(API_KEY);
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
    @DisplayName("happy path: gateway returns 200 → no throw, outbound shape pinned")
    void sendCustomNotification_happyPath_doesNotThrow() {
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse().withStatus(200)));

        // .toBodilessEntity() so any 2xx is success regardless of response body.
        client.sendCustomNotification("+263782606983", "Your OTP is 123456");

        // Wire contract: exact path, x-api-key header, JSON body with both
        // required fields. Field names "to" + "notification" are the gateway's
        // contract — renaming them in the client would silently break delivery.
        wireMock.verify(postRequestedFor(urlEqualTo("/api/messages/custom-notification"))
                .withHeader("x-api-key", equalTo(API_KEY))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.to", equalTo("+263782606983")))
                .withRequestBody(matchingJsonPath("$.notification", equalTo("Your OTP is 123456"))));
    }

    @Test
    @DisplayName("gateway 401 (stale API key): NotificationDeliveryException with HTTP 401")
    void sendCustomNotification_401_throwsWithRejection() {
        // The exact failure mode we hit during this milestone when the wasenda
        // domain swap happened with a stale x-api-key. Pinning it so a future
        // key-rotation regression surfaces at PR time.
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json; charset=utf-8")
                        .withBody("{\"error\":\"Unauthorized: invalid or missing API key\"}")));

        assertThatThrownBy(() -> client.sendCustomNotification("+263782606983", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    @DisplayName("gateway 502 (upstream WhatsApp Business API down): NotificationDeliveryException")
    void sendCustomNotification_502_throwsWithRejection() {
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse()
                        .withStatus(502)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"upstream WhatsApp API unavailable\"}")));

        assertThatThrownBy(() -> client.sendCustomNotification("+263782606983", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 502");
    }

    @Test
    @DisplayName("gateway 429 (rate-limited): NotificationDeliveryException so caller can back off")
    void sendCustomNotification_429_throwsWithRejection() {
        // WhatsApp Business API has tight per-recipient rate limits — the
        // gateway can surface 429 if a customer requests multiple OTPs in
        // quick succession. Treated as a rejection so the OTP rate-limit
        // path in user-service can fall back or alert.
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"rate limit exceeded\"}")));

        assertThatThrownBy(() -> client.sendCustomNotification("+263782606983", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 429");
    }

    @Test
    @DisplayName("gateway unreachable (connection refused): NotificationDeliveryException with 'unreachable'")
    void sendCustomNotification_gatewayDown_throwsWithUnreachableMessage() throws Exception {
        // Separate client at a known-closed port (same pattern as
        // SmsNotificationClientContractTest — don't stop/restart the shared
        // WireMock; the second start gets a different dynamic port and
        // breaks other tests in this class).
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }

        WhatsAppProperties props = new WhatsAppProperties();
        props.setBaseUrl("http://localhost:" + closedPort);
        props.setApiKey(API_KEY);
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(500);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        WhatsAppNotificationClient unreachableClient = new WhatsAppNotificationClient(
                RestClient.builder().baseUrl(props.getBaseUrl()).requestFactory(factory).build(),
                props);

        assertThatThrownBy(() -> unreachableClient.sendCustomNotification("+263782606983", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("blank recipient rejected client-side; no HTTP call made")
    void sendCustomNotification_blankRecipient_rejectedBeforeNetwork() {
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client.sendCustomNotification("", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("blank");

        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/messages/custom-notification")));
    }

    @Test
    @DisplayName("blank notification rejected client-side; no HTTP call made")
    void sendCustomNotification_blankNotification_rejectedBeforeNetwork() {
        assertThatThrownBy(() -> client.sendCustomNotification("+263782606983", "  "))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("blank");

        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/messages/custom-notification")));
    }

    @Test
    @DisplayName("notification over 1600 chars rejected client-side; no HTTP call made")
    void sendCustomNotification_oversized_rejectedBeforeNetwork() {
        // The gateway's documented max is 1600 chars. The client guards on
        // the way out so we don't waste a round-trip on a guaranteed-reject.
        String oversized = "a".repeat(WhatsAppNotificationClient.MAX_MESSAGE_LENGTH + 1);

        assertThatThrownBy(() -> client.sendCustomNotification("+263782606983", oversized))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("1600");

        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/messages/custom-notification")));
    }

    @Test
    @DisplayName("notification at exactly 1600 chars is allowed through to the gateway")
    void sendCustomNotification_atBoundary_isAllowed() {
        wireMock.stubFor(post(urlEqualTo("/api/messages/custom-notification"))
                .willReturn(aResponse().withStatus(200)));

        String boundary = "a".repeat(WhatsAppNotificationClient.MAX_MESSAGE_LENGTH);
        client.sendCustomNotification("+263782606983", boundary);

        // Off-by-one regression in the guard would either reject at 1600
        // (test fails with exception) or accept at 1601 (test above fails).
        // Both ends of the boundary pinned.
        assertThat(wireMock.findAll(postRequestedFor(
                urlEqualTo("/api/messages/custom-notification")))).hasSize(1);
    }
}
