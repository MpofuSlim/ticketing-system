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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test: pins {@link EmailNotificationClient}'s behaviour against each
 * response shape the {@code innbucks-core-gateway} adapter's
 * {@code POST /notifications/email} endpoint returns. Mirrors the audit-#10
 * template established by {@link SmsNotificationClientContractTest} — it exists
 * so any change in the adapter's wire contract (status code, path, error
 * envelope, OUTBOUND payload shape) fails the build at PR time rather than at
 * 2am in prod.
 *
 * <p>WireMock stands in for the adapter; the client's real {@code RestClient}
 * is built with the same shape {@code InnbucksGatewayClientConfig} produces,
 * just pointed at the WireMock port. The stubbed responses match what {@code
 * EmailController#send} actually returns: 200 {@code {reference,status:SUBMITTED}}
 * on a healthy messenger, 502 {@code {reference,status:FAILED,error}} when
 * messenger-interface itself answered non-2xx, and 503 when it was unreachable.
 *
 * <p>Pure JUnit + WireMock, no Spring context — fast and surgical to the wire
 * contract.
 */
class EmailNotificationClientContractTest {

    private static WireMockServer wireMock;
    private static EmailNotificationClient client;

    @BeforeAll
    static void startWireMockAndWireClient() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        // Build the EmailNotificationClient with the same RestClient shape the
        // prod InnbucksGatewayClientConfig produces, just pointed at WireMock —
        // exercises the real serialisation path; only the host:port differs.
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
        client = new EmailNotificationClient(restClient);
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
    @DisplayName("happy path: single HTML recipient, adapter returns 200 → client returns normally")
    void sendEmail_happyPathSingleHtml_doesNotThrow() {
        // Recorded shape: { "reference": "r1", "status": "SUBMITTED" }.
        wireMock.stubFor(post(urlEqualTo("/notifications/email"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reference\":\"r1\",\"status\":\"SUBMITTED\"}")));

        client.sendEmail("ops@innbucks.co.zw", "Your InnBucks account", "<p>Welcome</p>", "r1");

        // Verify wire-level contract: path, JSON shape, to[] as an array,
        // isHtml flag, and the required fields.
        wireMock.verify(postRequestedFor(urlEqualTo("/notifications/email"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.to[0]", equalTo("ops@innbucks.co.zw")))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Your InnBucks account")))
                .withRequestBody(matchingJsonPath("$.body", equalTo("<p>Welcome</p>")))
                .withRequestBody(matchingJsonPath("$.isHtml", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("r1"))));
    }

    @Test
    @DisplayName("multi-recipient with cc/bcc, plain text: arrays serialise and blank entries are stripped")
    void sendEmail_multiRecipientWithCcBcc_serialisesArraysAndStripsBlanks() {
        wireMock.stubFor(post(urlEqualTo("/notifications/email"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reference\":\"r2\",\"status\":\"SUBMITTED\"}")));

        // Blank / null entries in to are dropped; the first two valid addresses
        // must land at indices 0 and 1.
        client.sendEmail(
                new String[]{"a@x.com", "", null, "b@x.com"},
                new String[]{"cc@x.com"},
                new String[]{"bcc@x.com"},
                "Statement", "plain body", false, "r2");

        wireMock.verify(postRequestedFor(urlEqualTo("/notifications/email"))
                .withRequestBody(matchingJsonPath("$.to[0]", equalTo("a@x.com")))
                .withRequestBody(matchingJsonPath("$.to[1]", equalTo("b@x.com")))
                .withRequestBody(matchingJsonPath("$.cc[0]", equalTo("cc@x.com")))
                .withRequestBody(matchingJsonPath("$.bcc[0]", equalTo("bcc@x.com")))
                .withRequestBody(matchingJsonPath("$.isHtml", equalTo("false"))));
    }

    @Test
    @DisplayName("adapter returns 502 (messenger rejected): throws NotificationDeliveryException with status")
    void sendEmail_adapterReturns502_throwsWithRejectionDetail() {
        // From EmailController#body(): { reference, status:FAILED, error }.
        wireMock.stubFor(post(urlEqualTo("/notifications/email"))
                .willReturn(aResponse()
                        .withStatus(502)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reference\":\"r3\",\"status\":\"FAILED\","
                                + "\"error\":\"messenger-interface rejected: HTTP 500\"}")));

        assertThatThrownBy(() ->
                client.sendEmail("ops@innbucks.co.zw", "subject", "body", "r3"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 502");
    }

    @Test
    @DisplayName("adapter returns 503 (messenger unreachable): throws NotificationDeliveryException")
    void sendEmail_adapterReturns503_throwsAsRejection() {
        wireMock.stubFor(post(urlEqualTo("/notifications/email"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reference\":\"r4\",\"status\":\"FAILED\","
                                + "\"error\":\"messenger-interface unreachable\"}")));

        assertThatThrownBy(() ->
                client.sendEmail("ops@innbucks.co.zw", "subject", "body", "r4"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    @DisplayName("adapter unreachable (connection refused): throws NotificationDeliveryException")
    void sendEmail_adapterUnreachable_throwsWithUnreachableMessage() throws Exception {
        // Point a SEPARATE client at a known-closed port (grab a free port then
        // close it) so the shared WireMock is untouched and other tests stay
        // green regardless of execution order.
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        } // socket closed -> port unbound -> connect attempts get RST

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(500));
        RestClient deadClient = RestClient.builder()
                .baseUrl("http://localhost:" + closedPort)
                .requestFactory(factory)
                .build();
        EmailNotificationClient unreachableClient = new EmailNotificationClient(deadClient);

        assertThatThrownBy(() ->
                unreachableClient.sendEmail("ops@innbucks.co.zw", "subject", "body", "r5"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("blank recipient is rejected client-side; no HTTP call is made")
    void sendEmail_blankRecipient_rejectedBeforeNetwork() {
        wireMock.stubFor(post(urlEqualTo("/notifications/email"))
                .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client.sendEmail("  ", "subject", "body", "r6"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("recipient is blank");

        wireMock.verify(0, postRequestedFor(urlEqualTo("/notifications/email")));
    }

    @Test
    @DisplayName("blank subject is rejected client-side; no HTTP call is made")
    void sendEmail_blankSubject_rejectedBeforeNetwork() {
        assertThatThrownBy(() -> client.sendEmail("ops@innbucks.co.zw", "  ", "body", "r7"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("subject is blank");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/notifications/email")));
    }

    @Test
    @DisplayName("blank body is rejected client-side; no HTTP call is made")
    void sendEmail_blankBody_rejectedBeforeNetwork() {
        assertThatThrownBy(() -> client.sendEmail("ops@innbucks.co.zw", "subject", "  ", "r8"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("body is blank");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/notifications/email")));
    }

    @Test
    @DisplayName("null reference is replaced with a TKT-EMAIL-<uuid> reference on the wire")
    void sendEmail_nullReference_isAutoGenerated() {
        wireMock.stubFor(post(urlEqualTo("/notifications/email"))
                .willReturn(aResponse().withStatus(200)));

        client.sendEmail("ops@innbucks.co.zw", "subject", "body", null);

        // Reference must always be present so messenger can dedupe and the
        // adapter has an idempotency key. Auto-generated format: TKT-EMAIL-<uuid>.
        wireMock.verify(postRequestedFor(urlEqualTo("/notifications/email"))
                .withRequestBody(matchingJsonPath("$.reference",
                        matching("TKT-EMAIL-[0-9a-f-]{36}"))));
    }
}
