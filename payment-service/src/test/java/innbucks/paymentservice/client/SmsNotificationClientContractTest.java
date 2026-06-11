package innbucks.paymentservice.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for payment-service's {@link SmsNotificationClient} against
 * the innbucks-core-gateway {@code POST /notifications/sms} adapter — the SMS
 * fallback for payment-code delivery. Mirrors booking-service's client (same
 * gateway, same body contract): destination/message/reference/senderId, with
 * the {@code TKT-SMS-<uuid>} reference auto-fill.
 */
class SmsNotificationClientContractTest {

    private static WireMockServer wireMock;
    private static SmsNotificationClient client;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        client = new SmsNotificationClient(
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
    @DisplayName("happy path: posts destination/message/reference/senderId to /notifications/sms")
    void sendSms_postsDocumentedShape() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse().withStatus(200)));

        client.sendSms("+263770000001", "InnBucks code 701285660", "TKT-PMT-ref-1");

        wireMock.verify(postRequestedFor(urlEqualTo("/notifications/sms"))
                .withRequestBody(matchingJsonPath("$.destination", equalTo("+263770000001")))
                .withRequestBody(matchingJsonPath("$.message", equalTo("InnBucks code 701285660")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("TKT-PMT-ref-1")))
                .withRequestBody(matchingJsonPath("$.senderId", equalTo("INNBUCKS"))));
    }

    @Test
    @DisplayName("null reference: auto-fills TKT-SMS-<uuid> instead of sending blank")
    void sendSms_autoFillsReference() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse().withStatus(200)));

        client.sendSms("+263770000001", "msg", null);

        wireMock.verify(postRequestedFor(urlEqualTo("/notifications/sms"))
                .withRequestBody(matchingJsonPath("$.reference", matching("TKT-SMS-[0-9a-f-]{36}"))));
    }

    @Test
    @DisplayName("blank destination/message: guarded before the network")
    void sendSms_blankInputs_neverHitTheWire() {
        assertThatThrownBy(() -> client.sendSms(" ", "msg", null))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> client.sendSms("+263770000001", "", null))
                .isInstanceOf(NotificationDeliveryException.class);

        wireMock.verify(0, postRequestedFor(urlEqualTo("/notifications/sms")));
    }

    @Test
    @DisplayName("gateway 5xx: surfaces as NotificationDeliveryException (caller falls through)")
    void sendSms_gatewayError_throwsDeliveryException() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse().withStatus(502).withBody("upstream messenger down")));

        assertThatThrownBy(() -> client.sendSms("+263770000001", "msg", null))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("502");
    }

    @Test
    @DisplayName("connect refused: NotificationDeliveryException (separate dead-port client)")
    void sendSms_connectRefused_throwsDeliveryException() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        SmsNotificationClient dead = new SmsNotificationClient(
                RestClient.builder().baseUrl("http://localhost:" + closedPort).build());

        assertThatThrownBy(() -> dead.sendSms("+263770000001", "msg", null))
                .isInstanceOf(NotificationDeliveryException.class);
    }
}
