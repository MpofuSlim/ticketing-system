package innbucks.paymentservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link ZimswitchCopyPayClient} against ZimSwitch Online
 * (COPYandPAY — spec pinned at {@code docs/api/zimswitch-copyandpay.md},
 * transcribed from zimswitch.docs.oppwa.com). The stubs mirror the doc's
 * sample shapes; when UAT responses diverge, update the doc, these stubs and
 * the classifier together — a contract drift fails the build, not production.
 *
 * <p>Pure JUnit + WireMock, no Spring context. Retry registry uses a real
 * 2-attempt config so the read-only retry policy is observable: the status
 * GET retries on 5xx, checkout PREPARATION never does.
 */
class ZimswitchCopyPayClientContractTest {

    private static final String CHECKOUT_ID = "8a82944a4cc25ebf014cc2c782423202";
    private static final String STATUS_PATH = "/v1/checkouts/" + CHECKOUT_ID + "/payment?entityId=test-entity";

    private static WireMockServer wireMock;

    private static ZimswitchCopyPayClient newClient(String baseUrl) {
        ZimswitchProperties props = new ZimswitchProperties();
        props.setBaseUrl(baseUrl);
        props.setEntityId("test-entity");
        props.setAccessToken("test-bearer-token");
        props.setTestMode("EXTERNAL");
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(2000);
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(ZimswitchApiTransientException.class)
                .build());
        return new ZimswitchCopyPayClient(props, new ObjectMapper(), retries, CircuitBreakerRegistry.ofDefaults());
    }

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    /** The doc's prepare-checkout success shape (000.200.100 + id + integrity). */
    private static final String PREPARED_OK = """
            {
              "result": { "code": "000.200.100", "description": "successfully created checkout" },
              "buildNumber": "b7e8f",
              "timestamp": "2026-08-12 10:15:00+0000",
              "ndc": "ndc-123",
              "id": "8a82944a4cc25ebf014cc2c782423202",
              "integrity": "sha384-3phAZzHTYFuLtHT2AzM5PIYjPLGtqcBQXAq7fbQw0QHIhJEQZUJEG52uV6uWBSQE"
            }
            """;

    @Test
    @DisplayName("prepare: form-encoded wire shape — entityId, MAJOR-unit amount, DB, our reference, integrity, testMode; Bearer auth")
    void prepare_postsDocumentedFormShape() {
        wireMock.stubFor(post(urlEqualTo("/v1/checkouts"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(PREPARED_OK)));

        CheckoutPreparation prepared = newClient("http://localhost:" + wireMock.port())
                .prepareCheckout("TKZ-PINKRUN26-4F3A2B1C0D9E", 9200, "USD");

        assertThat(prepared.created()).isTrue();
        assertThat(prepared.checkoutId()).isEqualTo(CHECKOUT_ID);
        assertThat(prepared.integrity()).startsWith("sha384-");
        assertThat(prepared.ndc()).isEqualTo("ndc-123");

        // The wire contract: 9200 CENTS leaves as MAJOR-unit "92.00" (the
        // 100x guard's first half), all params in the BODY, Bearer header.
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/checkouts"))
                .withHeader("Authorization", equalTo("Bearer test-bearer-token"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("entityId=test-entity"))
                .withRequestBody(containing("amount=92.00"))
                .withRequestBody(containing("currency=USD"))
                .withRequestBody(containing("paymentType=DB"))
                .withRequestBody(containing("merchantTransactionId=TKZ-PINKRUN26-4F3A2B1C0D9E"))
                .withRequestBody(containing("integrity=true"))
                .withRequestBody(containing("testMode=EXTERNAL")));
    }

    @Test
    @DisplayName("prepare: blank testMode is OMITTED from the body, not sent empty")
    void prepare_omitsBlankTestMode() {
        wireMock.stubFor(post(urlEqualTo("/v1/checkouts"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(PREPARED_OK)));
        ZimswitchProperties props = new ZimswitchProperties();
        props.setBaseUrl("http://localhost:" + wireMock.port());
        props.setEntityId("test-entity");
        props.setAccessToken("test-bearer-token");
        props.setTestMode("   ");
        ZimswitchCopyPayClient client = new ZimswitchCopyPayClient(props, new ObjectMapper(),
                RetryRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults());

        client.prepareCheckout("TKT-PMT-x", 100, "USD");

        wireMock.verify(postRequestedFor(urlEqualTo("/v1/checkouts"))
                .withRequestBody(notMatching(".*testMode.*")));
    }

    @Test
    @DisplayName("prepare refused (4xx + envelope): created=false with the upstream reason — no exception")
    void prepare_surfacesRefusalEnvelope() {
        wireMock.stubFor(post(urlEqualTo("/v1/checkouts"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "result": { "code": "200.300.404",
                                              "description": "invalid or missing parameter" },
                                  "buildNumber": "b", "timestamp": "t", "ndc": "n" }
                                """)));

        CheckoutPreparation prepared = newClient("http://localhost:" + wireMock.port())
                .prepareCheckout("TKT-PMT-x", 100, "USD");

        assertThat(prepared.created()).isFalse();
        assertThat(prepared.resultCode()).isEqualTo("200.300.404");
        assertThat(prepared.resultMessage()).contains("invalid or missing parameter");
    }

    @Test
    @DisplayName("prepare 5xx: transient exception AND exactly ONE request — preparation is never retried")
    void prepare_neverRetriesOn5xx() {
        wireMock.stubFor(post(urlEqualTo("/v1/checkouts"))
                .willReturn(aResponse().withStatus(502).withBody("bad gateway")));

        assertThatThrownBy(() -> newClient("http://localhost:" + wireMock.port())
                .prepareCheckout("TKT-PMT-x", 100, "USD"))
                .isInstanceOf(ZimswitchApiTransientException.class);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/checkouts")));
    }

    @Test
    @DisplayName("prepare connect-refused: transient exception, nothing partial returned")
    void prepare_connectRefusedIsTransient() {
        // Closed port — deliberately NOT the shared WireMock (a stop/restart
        // would change its dynamic port and break sibling tests).
        assertThatThrownBy(() -> newClient("http://127.0.0.1:1")
                .prepareCheckout("TKT-PMT-x", 100, "USD"))
                .isInstanceOf(ZimswitchApiTransientException.class);
    }

    @Test
    @DisplayName("status success: GET path rebuilt from OUR checkoutId + entityId param + Bearer; echoes parsed for verification")
    void status_successShape() {
        wireMock.stubFor(get(urlEqualTo(STATUS_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "8ac7a49f8e1a2b3c018e1b0d4a5f0001",
                                  "paymentType": "DB",
                                  "paymentBrand": "VISA",
                                  "amount": "92.00",
                                  "currency": "USD",
                                  "merchantTransactionId": "TKZ-PINKRUN26-4F3A2B1C0D9E",
                                  "result": { "code": "000.100.110",
                                              "description": "Request successfully processed in 'Merchant in Integrator Test Mode'" },
                                  "ndc": "ndc-456"
                                }
                                """)));

        CardPaymentStatus status = newClient("http://localhost:" + wireMock.port())
                .getPaymentStatus(CHECKOUT_ID);

        assertThat(status.outcome()).isEqualTo(ZimswitchResultCode.SUCCESS);
        assertThat(status.transactionId()).isEqualTo("8ac7a49f8e1a2b3c018e1b0d4a5f0001");
        assertThat(status.amountEcho()).isEqualByComparingTo(new BigDecimal("92.00"));
        assertThat(status.currencyEcho()).isEqualTo("USD");
        assertThat(status.brand()).isEqualTo("VISA");
        assertThat(status.merchantTransactionId()).isEqualTo("TKZ-PINKRUN26-4F3A2B1C0D9E");

        wireMock.verify(getRequestedFor(urlEqualTo(STATUS_PATH))
                .withHeader("Authorization", equalTo("Bearer test-bearer-token")));
    }

    @Test
    @DisplayName("status pending (000.200.x): classified PENDING — row stays open")
    void status_pendingShape() {
        wireMock.stubFor(get(urlEqualTo(STATUS_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "id": "tx-1", "result": { "code": "000.200.000",
                                  "description": "transaction pending" }, "ndc": "n" }
                                """)));

        assertThat(newClient("http://localhost:" + wireMock.port())
                .getPaymentStatus(CHECKOUT_ID).outcome())
                .isEqualTo(ZimswitchResultCode.PENDING);
    }

    @Test
    @DisplayName("status decline: classified REJECTED with the decline code preserved verbatim")
    void status_declineShape() {
        wireMock.stubFor(get(urlEqualTo(STATUS_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "id": "tx-2", "paymentBrand": "MASTER", "amount": "92.00",
                                  "currency": "USD",
                                  "result": { "code": "800.100.153",
                                              "description": "transaction declined (invalid CVV)" },
                                  "ndc": "n" }
                                """)));

        CardPaymentStatus status = newClient("http://localhost:" + wireMock.port())
                .getPaymentStatus(CHECKOUT_ID);

        assertThat(status.outcome()).isEqualTo(ZimswitchResultCode.REJECTED);
        assertThat(status.resultCode()).isEqualTo("800.100.153");
    }

    @Test
    @DisplayName("status 200.300.404 wrapped in HTTP 400: a REAL answer (no payment yet), not an error")
    void status_notFoundIsAnAnswer() {
        wireMock.stubFor(get(urlEqualTo(STATUS_PATH))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "result": { "code": "200.300.404",
                                  "description": "invalid or missing parameter - no payment session found" },
                                  "ndc": "n" }
                                """)));

        assertThat(newClient("http://localhost:" + wireMock.port())
                .getPaymentStatus(CHECKOUT_ID).outcome())
                .isEqualTo(ZimswitchResultCode.CHECKOUT_NOT_FOUND);
    }

    @Test
    @DisplayName("status 5xx then success: the read-only call IS retried")
    void status_retriesOn5xx() {
        wireMock.stubFor(get(urlEqualTo(STATUS_PATH))
                .inScenario("retry").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second"));
        wireMock.stubFor(get(urlEqualTo(STATUS_PATH))
                .inScenario("retry").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "id": "tx-3", "result": { "code": "000.000.000",
                                  "description": "success" }, "ndc": "n" }
                                """)));

        assertThat(newClient("http://localhost:" + wireMock.port())
                .getPaymentStatus(CHECKOUT_ID).outcome())
                .isEqualTo(ZimswitchResultCode.SUCCESS);
        wireMock.verify(2, getRequestedFor(urlEqualTo(STATUS_PATH)));
    }

    @Test
    @DisplayName("guard rail: unconfigured client refuses BOTH calls without touching the network")
    void unconfigured_neverHitsTheNetwork() {
        ZimswitchProperties props = new ZimswitchProperties();
        props.setBaseUrl("http://localhost:" + wireMock.port());
        // entityId + accessToken deliberately blank.
        ZimswitchCopyPayClient client = new ZimswitchCopyPayClient(props, new ObjectMapper(),
                RetryRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults());

        assertThatThrownBy(() -> client.prepareCheckout("TKT-PMT-x", 100, "USD"))
                .isInstanceOf(ZimswitchApiException.class)
                .hasMessageContaining("not configured");
        assertThatThrownBy(() -> client.getPaymentStatus(CHECKOUT_ID))
                .isInstanceOf(ZimswitchApiException.class);

        wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/checkouts")));
        wireMock.verify(0, getRequestedFor(urlPathMatching("/v1/checkouts/.*")));
    }

    @Test
    @DisplayName("guard rail (SSRF): a malformed checkoutId is refused client-side — zero requests")
    void malformedCheckoutId_neverHitsTheNetwork() {
        ZimswitchCopyPayClient client = newClient("http://localhost:" + wireMock.port());

        assertThatThrownBy(() -> client.getPaymentStatus("../../../v1/query?x="))
                .isInstanceOf(ZimswitchApiException.class)
                .hasMessageContaining("malformed");
        assertThatThrownBy(() -> client.getPaymentStatus("id with spaces"))
                .isInstanceOf(ZimswitchApiException.class);
        assertThatThrownBy(() -> client.getPaymentStatus(null))
                .isInstanceOf(ZimswitchApiException.class);

        wireMock.verify(0, getRequestedFor(urlPathMatching(".*")));
    }

    @Test
    @DisplayName("widget script URL: base-url + paymentWidgets.js + checkoutId, no double slash")
    void widgetScriptUrl_shape() {
        ZimswitchProperties props = new ZimswitchProperties();
        props.setBaseUrl("https://eu-test.oppwa.com/");
        props.setEntityId("e");
        props.setAccessToken("t");
        ZimswitchCopyPayClient client = new ZimswitchCopyPayClient(props, new ObjectMapper(),
                RetryRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults());

        assertThat(client.widgetScriptUrl(CHECKOUT_ID))
                .isEqualTo("https://eu-test.oppwa.com/v1/paymentWidgets.js?checkoutId=" + CHECKOUT_ID);
    }
}
