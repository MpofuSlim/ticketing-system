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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link ZimswitchCopyPayClient} against ZimSwitch Online
 * (COPYandPAY — spec pinned at {@code docs/api/zimswitch-copyandpay.md}).
 * When UAT responses diverge, update the doc, these stubs and the classifier
 * together — a contract drift fails the build, not production.
 *
 * <p><b>Stub provenance</b> (the convention is: every stub either transcribes
 * a real response or matches a serializer read out of the upstream source —
 * say which):
 * <ul>
 *   <li>{@link #PREPARED_OBSERVED} and {@link #NO_PAYMENT_SESSION_OBSERVED}
 *       are <b>transcribed from a live playground run against
 *       eu-test.oppwa.com on 2026-08-12</b> (opaque digests abbreviated).
 *       That run used the DOC'S DEMO ENTITY, not TICKETIZE's — so the
 *       platform contract is observed, but nothing entity-specific is.</li>
 *   <li>The remaining stubs are constructed from the documented shapes for
 *       response families we have not yet driven end to end (declines,
 *       pending, 5xx). They pin OUR classification of those families, not an
 *       observation — a real decline in UAT should be transcribed over the
 *       top of the constructed one.</li>
 * </ul>
 *
 * <p>Pure JUnit + WireMock, no Spring context. Retry registry uses a real
 * 2-attempt config so the read-only retry policy is observable: the status
 * GET retries on 5xx, checkout PREPARATION never does.
 */
class ZimswitchCopyPayClientContractTest {

    private static final String CHECKOUT_ID = "8a82944a4cc25ebf014cc2c782423202";
    private static final String STATUS_PATH = "/v1/checkouts/" + CHECKOUT_ID + "/payment?entityId=test-entity";

    private static WireMockServer wireMock;

    private static final String RESULT_URL = "https://tickets.example.co.zw/checkout/card-result";

    private static ZimswitchCopyPayClient newClient(String baseUrl) {
        ZimswitchProperties props = new ZimswitchProperties();
        props.setBaseUrl(baseUrl);
        props.setEntityId("test-entity");
        props.setAccessToken("test-bearer-token");
        props.setShopperResultUrl(RESULT_URL);
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

    /**
     * The REAL checkout id shape, observed 2026-08-12: 32 uppercase-ish hex
     * chars plus a dotted node suffix — NOT the bare hex the doc's prose
     * examples show. 46 chars, containing '.' and '-'.
     */
    private static final String OBSERVED_CHECKOUT_ID = "67b6338009845562954024F5A44FC2AE.uat01-vm-tx04";

    /** TRANSCRIBED from the 2026-08-12 live run (opaque values abbreviated). */
    private static final String PREPARED_OBSERVED = """
            {
              "result": { "code": "000.200.100", "description": "successfully created checkout" },
              "buildNumber": "73sd4ed6995712...2026-08-06 10:13:44 +0000",
              "timestamp": "2026-08-12 15:20:06+0000",
              "ndc": "67D6B36080984556...4FC2AE.uat01-vm-tx04",
              "id": "67b6338009845562954024F5A44FC2AE.uat01-vm-tx04",
              "integrity": "sha384-GLce9JQ/CDxNkrPz2mLliLQc+/p6jqgJCIzoYWMlGWlLSZJ0FUkexJUcJ3bvKQpc"
            }
            """;

    /**
     * TRANSCRIBED from the same run: the status read of a prepared-but-unpaid
     * checkout. Note it arrives wrapped in a non-2xx, and the wording is about
     * the PAYMENT session, not the checkout id being wrong.
     */
    private static final String NO_PAYMENT_SESSION_OBSERVED = """
            {
              "result": { "code": "200.300.404",
                          "description": "invalid or missing parameter - (opp) no payment session found for the requested id" },
              "buildNumber": "73sd4ed6995712...",
              "timestamp": "2026-08-12 15:19:34+0000",
              "ndc": "8ac7a4c79394bdc8019397...ada7d1f1"
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
    @DisplayName("prepare: shopperResultUrl rides the body so ASYNC brands (3DS) can redirect the shopper back")
    void prepare_sendsShopperResultUrl() {
        wireMock.stubFor(post(urlEqualTo("/v1/checkouts"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(PREPARED_OK)));

        newClient("http://localhost:" + wireMock.port())
                .prepareCheckout("TKT-PMT-x", 100, "USD");

        // Form-encoded, so the URL arrives percent-encoded in the body.
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/checkouts"))
                .withRequestBody(containing("shopperResultUrl=" +
                        java.net.URLEncoder.encode(RESULT_URL, java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    @DisplayName("guard: credentials without a usable shopperResultUrl can TALK to the gateway but must not START a checkout")
    void halfProvisionedRail_cannotStartButStaysPollable() {
        ZimswitchProperties props = new ZimswitchProperties();
        props.setBaseUrl("http://localhost:" + wireMock.port());
        props.setEntityId("test-entity");
        props.setAccessToken("test-bearer-token");
        // shopperResultUrl deliberately unset — the half-provisioned cell.
        ZimswitchCopyPayClient client = new ZimswitchCopyPayClient(props, new ObjectMapper(),
                RetryRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults());

        // Pollable: an already-open checkout must stay resolvable, or a config
        // slip would strand rows the customer may already have paid.
        assertThat(client.isConfigured()).isTrue();
        // But not startable: no form action means no payment form, while the
        // ledger row would still hold the order's only payment slot.
        assertThat(client.canStartCheckout()).isFalse();
    }

    @ParameterizedTest
    @DisplayName("guard: only absolute http(s) URLs count as a usable shopper result URL")
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "/checkout/card-result",                 // relative — no host for the gateway to redirect to
            "checkout/card-result",                  // relative, no leading slash
            "ftp://tickets.example.co.zw/result",    // wrong scheme
            "javascript:alert(1)",                   // not a location at all
            "https://",                              // scheme only, no host
    })
    void unusableResultUrls_areRejected(String url) {
        assertThat(ZimswitchCopyPayClient.isUsableResultUrl(url)).isFalse();
    }

    @ParameterizedTest
    @DisplayName("guard: real result URLs pass, including ports, paths and query strings")
    @ValueSource(strings = {
            "https://tickets.example.co.zw/checkout/card-result",
            "http://localhost:5173/checkout/card-result",
            "https://tickets.example.co.zw:8443/checkout/card-result?src=card",
    })
    void usableResultUrls_areAccepted(String url) {
        assertThat(ZimswitchCopyPayClient.isUsableResultUrl(url)).isTrue();
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
    @DisplayName("OBSERVED 2026-08-12: prepare returns a dotted/suffixed checkout id — parsed intact, not truncated at the dot")
    void prepare_parsesObservedSuffixedCheckoutId() {
        wireMock.stubFor(post(urlEqualTo("/v1/checkouts"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(PREPARED_OBSERVED)));

        CheckoutPreparation prepared = newClient("http://localhost:" + wireMock.port())
                .prepareCheckout("TKZ-PINKRUN26-4F3A2B1C0D9E", 9200, "USD");

        assertThat(prepared.created()).isTrue();
        assertThat(prepared.checkoutId()).isEqualTo(OBSERVED_CHECKOUT_ID);
        // The real id is 46 chars — comfortably inside payment.checkout_id
        // VARCHAR(64), but far past the 32 the doc's prose examples imply.
        assertThat(prepared.checkoutId()).hasSize(46).contains(".").contains("-");
        // ndc is a DIFFERENT value from id and must not be conflated with it.
        assertThat(prepared.ndc()).isNotEqualTo(prepared.checkoutId());
    }

    @Test
    @DisplayName("OBSERVED 2026-08-12 (SSRF guard, POSITIVE case): the real dotted id passes validation and reaches the wire")
    void status_acceptsObservedSuffixedCheckoutId() {
        // This is the case that would have broken the rail outright had the
        // guard been written hex-only or length-32: every REAL checkout id
        // carries a dotted node suffix.
        String observedStatusPath =
                "/v1/checkouts/" + OBSERVED_CHECKOUT_ID + "/payment?entityId=test-entity";
        wireMock.stubFor(get(urlEqualTo(observedStatusPath))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody(NO_PAYMENT_SESSION_OBSERVED)));

        CardPaymentStatus status = newClient("http://localhost:" + wireMock.port())
                .getPaymentStatus(OBSERVED_CHECKOUT_ID);

        assertThat(status.outcome()).isEqualTo(ZimswitchResultCode.CHECKOUT_NOT_FOUND);
        assertThat(status.resultCode()).isEqualTo("200.300.404");
        // The dot/hyphen id must survive into the path UNESCAPED — a client
        // that percent-encoded them would 404 against a different resource.
        wireMock.verify(getRequestedFor(urlEqualTo(observedStatusPath))
                .withHeader("Authorization", equalTo("Bearer test-bearer-token")));
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
