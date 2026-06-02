package com.innbucks.userservice.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.userservice.config.InnbucksGatewayProperties;
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
 * Contract test: pins {@link SmsNotificationClient}'s behaviour against each
 * response shape the {@code innbucks-core-gateway} adapter has actually been
 * observed to return. This is the audit-#10 template — it exists so that any
 * change in the adapter's wire contract (status code, path, body parsing)
 * fails the build at PR time rather than at 2am in prod.
 *
 * <p>WireMock stands in for the adapter; the {@link SmsNotificationClient}'s
 * real {@code RestClient} is wired to point at the WireMock port. Every
 * stub in this class corresponds to a response we've SEEN from the live
 * gateway during this milestone (200 SUBMITTED on a healthy messenger;
 * 502 with the rejection wrapper when messenger returned 500 because
 * RabbitMQ was down; 503 when messenger-interface wasn't in the Eureka
 * registry yet). Recording them as code prevents silent regression if the
 * adapter's error envelope is ever reshaped.
 *
 * <p>Pure JUnit + WireMock, no Spring context — keeps the test fast (~1s
 * per case) and surgical to the wire contract.
 */
class SmsNotificationClientContractTest {

    private static WireMockServer wireMock;
    private static SmsNotificationClient client;

    @BeforeAll
    static void startWireMockAndWireClient() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        // Build the SmsNotificationClient with the same RestClient shape the
        // prod InnbucksGatewayClientConfig produces, just pointed at WireMock.
        // Mirrors prod wiring so the test exercises the real serialisation
        // path; only the host:port differs.
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
    @DisplayName("happy path: adapter returns 200 → client returns normally")
    void sendSms_happyPath_doesNotThrow() {
        // Recorded shape from the milestone-2 messenger smoke test:
        //   { "reference": "smoke-2", "status": "SUBMITTED" }
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reference\":\"r1\",\"status\":\"SUBMITTED\"}")));

        // Should complete without throwing — the client uses .toBodilessEntity()
        // so a 2xx with any body shape is treated as success.
        client.sendSms("+263782606983", "InnBucks OTP 123456", "r1");

        // Verify wire-level contract: correct path, JSON body shape, required fields.
        wireMock.verify(postRequestedFor(urlEqualTo("/notifications/sms"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.destination", equalTo("+263782606983")))
                .withRequestBody(matchingJsonPath("$.message", equalTo("InnBucks OTP 123456")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("r1")))
                .withRequestBody(matchingJsonPath("$.senderId", equalTo("INNBUCKS"))));
    }

    @Test
    @DisplayName("adapter returns 502 (messenger rejected): throws NotificationDeliveryException with status")
    void sendSms_adapterReturns502_throwsWithRejectionDetail() {
        // The shape the adapter returns when messenger-interface itself answered
        // with a non-2xx. From SmsController#body(): { reference, status:FAILED, error }.
        // Recorded from the live "RabbitMQ down → messenger returned 500" scenario.
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
    @DisplayName("adapter returns 503 (messenger unreachable): throws NotificationDeliveryException")
    void sendSms_adapterReturns503_throwsAsRejection() {
        // The shape when messenger-interface isn't in Eureka / circuit open.
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
    @DisplayName("adapter unreachable (connection refused): throws NotificationDeliveryException")
    void sendSms_adapterUnreachable_throwsWithUnreachableMessage() throws Exception {
        // Point a SEPARATE client at a known-closed port (open + immediately
        // close a server socket to grab a free port number, then the OS will
        // refuse any subsequent connect). Keeps the shared WireMock untouched
        // so other tests stay green regardless of execution order.
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        } // socket closed -> port is unbound -> connect attempts get RST

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(500));
        RestClient deadClient = RestClient.builder()
                .baseUrl("http://localhost:" + closedPort)
                .requestFactory(factory)
                .build();
        SmsNotificationClient unreachableClient = new SmsNotificationClient(deadClient);

        assertThatThrownBy(() -> unreachableClient.sendSms("+263782606983", "msg", "r4"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("blank destination is rejected client-side; no HTTP call is made")
    void sendSms_blankDestination_rejectedBeforeNetwork() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client.sendSms("", "msg", "r5"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("recipient is blank");

        // No request should have hit WireMock — guard-rails before the wire call.
        wireMock.verify(0, postRequestedFor(urlEqualTo("/notifications/sms")));
    }

    @Test
    @DisplayName("blank message is rejected client-side; no HTTP call is made")
    void sendSms_blankMessage_rejectedBeforeNetwork() {
        assertThatThrownBy(() -> client.sendSms("+263782606983", "  ", "r6"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("message is blank");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/notifications/sms")));
    }

    @Test
    @DisplayName("null reference is replaced with a TKT-SMS-<uuid> reference on the wire")
    void sendSms_nullReference_isAutoGenerated() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse().withStatus(200)));

        client.sendSms("+263782606983", "msg", null);

        // Reference must always be present on the wire so messenger can dedupe
        // and the adapter has an idempotency key. Auto-generated format:
        // TKT-SMS-<uuid>.
        wireMock.verify(postRequestedFor(urlEqualTo("/notifications/sms"))
                .withRequestBody(matchingJsonPath("$.reference",
                        matching("TKT-SMS-[0-9a-f-]{36}"))));
    }
}
