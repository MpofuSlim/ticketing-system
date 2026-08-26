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
 * Contract test for {@link EcocashEipClient} against EcoCash Instant Payment
 * (spec pinned at {@code docs/api/ecocash-eip.md}). When preprod responses
 * diverge, update the doc, these stubs and the classifier together — a
 * contract drift fails the build, not production.
 *
 * <p><b>Stub provenance:</b> every stub below is TRANSCRIBED from the EIP V3
 * PDF's own samples (charge PENDING SUBSCRIBER VALIDATION, query COMPLETED),
 * including the samples' documented unreliabilities — the charge response
 * echoing {@code endUserId} WITHOUT its country code, and the query response
 * returning the merchant/customer identity fields SWAPPED. Pinning the
 * swapped shape is deliberate: it proves the client never keys on identity
 * echoes. The FAILED / 404 / ServiceError shapes are constructed from the
 * doc's parameter tables (families not yet driven end to end); transcribe
 * real preprod bodies over them once the test msisdn is registered.
 *
 * <p>Pure JUnit + WireMock, no Spring context. Retry registry uses a real
 * 2-attempt config so the retry policy is observable: the Query GET retries
 * on 5xx, the CHARGE never does.
 */
class EcocashEipClientContractTest {

    private static final String CORRELATOR = "1763385010123456";
    private static final String MSISDN = "263777222093";
    private static final String QUERY_PATH = "/payment/v1/" + MSISDN + "/transactions/amount/" + CORRELATOR;
    private static final String CHARGE_PATH = "/payment/v1/transactions/amount";

    private static WireMockServer wireMock;

    private static EcocashEipClient newClient(String baseUrl) {
        EcocashProperties props = new EcocashProperties();
        props.setBaseUrl(baseUrl);
        props.setApiUsername("ecocash");
        props.setApiPassword("test-password");
        props.setMerchantCode("8003");
        props.setMerchantNumber("789111401");
        props.setMerchantPin("1234");
        props.setNotifyUrl("https://tickets.example.co.zw/foundry/payments/ecocash/notify");
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(2000);
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(EcocashApiTransientException.class)
                .build());
        return new EcocashEipClient(props, new ObjectMapper(), retries, CircuitBreakerRegistry.ofDefaults());
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

    /** PDF charge sample: accepted, prompt pushed. Note endUserId echoed WITHOUT its country code. */
    private static final String CHARGE_PENDING_PDF = """
            {
              "id": 1783948034,
              "clientCorrelator": "1763385010123456",
              "endUserId": "777222093",
              "serverReferenceCode": "TEST_1701060500009",
              "transactionOperationStatus": "PENDING SUBSCRIBER VALIDATION",
              "paymentAmount": {
                "totalAmountCharged": 0.0,
                "charginginformation": { "amount": 3.00, "cashbackAmount": null, "currency": "USD",
                                         "description": "Ticketize online payment" }
              },
              "ecocashReference": null,
              "merchantCode": "8003",
              "merchantPin": "1234",
              "merchantNumber": "789111401",
              "responseCode": null
            }
            """;

    /** PDF query sample: COMPLETED — with the identity fields SWAPPED, as observed. */
    private static final String QUERY_COMPLETED_PDF = """
            {
              "id": 1421243387,
              "clientCorrelator": "1763385010123456",
              "endUserId": "8003",
              "serverReferenceCode": "MP251117.0952.T0527795",
              "transactionOperationStatus": "COMPLETED",
              "paymentAmount": {
                "totalAmountCharged": 3.0,
                "charginginformation": { "amount": 3.0, "cashbackAmount": null, "currency": "USD",
                                         "description": "Online Merchant Payment Request" }
              },
              "ecocashReference": "MP251117.0952.T0527795",
              "merchantCode": "202000000000070010",
              "merchantPin": null,
              "merchantNumber": "263777222093",
              "remarks": "Process service request successfully."
            }
            """;

    /** Constructed from the doc's status table: subscriber rejected. */
    private static final String QUERY_FAILED = """
            {
              "clientCorrelator": "1763385010123456",
              "transactionOperationStatus": "FAILED",
              "paymentAmount": {
                "totalAmountCharged": 0.0,
                "charginginformation": { "amount": 3.0, "currency": "USD" }
              }
            }
            """;

    @Test
    @DisplayName("charge: PDF's accepted shape maps to PENDING; outbound body pins the full wire contract")
    void charge_acceptedShape_andOutboundContract() {
        wireMock.stubFor(post(urlEqualTo(CHARGE_PATH))
                .willReturn(okJson(CHARGE_PENDING_PDF)));

        EcocashChargeStatus status = newClient(wireMock.baseUrl())
                .charge(CORRELATOR, "+263777222093", 300, "USD", "TKZ-TEST-REF");

        assertThat(status.outcome()).isEqualTo(EcocashChargeStatus.Outcome.PENDING);
        assertThat(status.rawStatus()).isEqualTo("PENDING SUBSCRIBER VALIDATION");
        // serverReferenceCode is picked up when ecocashReference is null.
        assertThat(status.ecocashReference()).isEqualTo("TEST_1701060500009");

        // The OUTBOUND wire contract: HTTP Basic, msisdn WITHOUT '+', amount
        // as a JSON NUMBER in major units, correlator + notifyUrl + merchant
        // identity + the doc's mandatory constants all present.
        wireMock.verify(postRequestedFor(urlEqualTo(CHARGE_PATH))
                .withBasicAuth(new com.github.tomakehurst.wiremock.client.BasicCredentials("ecocash", "test-password"))
                .withRequestBody(matchingJsonPath("$.clientCorrelator", equalTo(CORRELATOR)))
                .withRequestBody(matchingJsonPath("$.endUserId", equalTo("263777222093")))
                // WireMock's JsonPath comparison normalises 3.00 -> 3.0, so the
                // two-decimal MAJOR-unit rendering (the 100x guard's other
                // half) is pinned against the raw wire bytes instead.
                .withRequestBody(containing("\"amount\":3.00"))
                .withRequestBody(matchingJsonPath("$.paymentAmount.charginginformation.currency", equalTo("USD")))
                .withRequestBody(matchingJsonPath("$.referenceCode", equalTo("TKZ-TEST-REF")))
                .withRequestBody(matchingJsonPath("$.tranType", equalTo("MER")))
                .withRequestBody(matchingJsonPath("$.transactionOperationStatus", equalTo("Charged")))
                .withRequestBody(matchingJsonPath("$.notifyUrl",
                        equalTo("https://tickets.example.co.zw/foundry/payments/ecocash/notify")))
                .withRequestBody(matchingJsonPath("$.merchantCode", equalTo("8003")))
                .withRequestBody(matchingJsonPath("$.merchantNumber", equalTo("789111401")))
                .withRequestBody(matchingJsonPath("$.merchantPin", equalTo("1234")))
                .withRequestBody(matchingJsonPath("$.currencyCode", equalTo("USD")))
                .withRequestBody(matchingJsonPath("$.countryCode", equalTo("ZW")))
                .withRequestBody(matchingJsonPath("$.paymentAmount.chargeMetaData.channel", equalTo("WEB"))));
    }

    @Test
    @DisplayName("charge: a 4xx refusal throws EcocashApiException and is NEVER retried")
    void charge_refused_neverRetried() {
        wireMock.stubFor(post(urlEqualTo(CHARGE_PATH))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":400,"exception":"ProcessingException",
                                 "message":"Duplicate clientCorrelator"}""")));

        assertThatThrownBy(() -> newClient(wireMock.baseUrl())
                .charge(CORRELATOR, MSISDN, 300, "USD", "TKZ-TEST-REF"))
                .isInstanceOf(EcocashApiException.class)
                .hasMessageContaining("Duplicate clientCorrelator");

        wireMock.verify(1, postRequestedFor(urlEqualTo(CHARGE_PATH)));
    }

    @Test
    @DisplayName("charge: a 5xx is transient (charge MAY exist) and is still NEVER retried")
    void charge_transient_neverRetried() {
        wireMock.stubFor(post(urlEqualTo(CHARGE_PATH))
                .willReturn(aResponse().withStatus(502)));

        assertThatThrownBy(() -> newClient(wireMock.baseUrl())
                .charge(CORRELATOR, MSISDN, 300, "USD", "TKZ-TEST-REF"))
                .isInstanceOf(EcocashApiTransientException.class);

        // THE money guard of this rail: one attempt only — a retried charge
        // with a fresh correlator could debit the customer twice.
        wireMock.verify(1, postRequestedFor(urlEqualTo(CHARGE_PATH)));
    }

    @Test
    @DisplayName("query: the PDF's COMPLETED shape (swapped identity fields and all) resolves with amounts + reference")
    void query_completedShape() {
        wireMock.stubFor(get(urlEqualTo(QUERY_PATH))
                .willReturn(okJson(QUERY_COMPLETED_PDF)));

        EcocashChargeStatus status = newClient(wireMock.baseUrl()).query("+" + MSISDN, CORRELATOR);

        assertThat(status.outcome()).isEqualTo(EcocashChargeStatus.Outcome.COMPLETED);
        assertThat(status.ecocashReference()).isEqualTo("MP251117.0952.T0527795");
        assertThat(status.totalAmountCharged()).isEqualByComparingTo(new BigDecimal("3.0"));
        assertThat(status.amountEcho()).isEqualByComparingTo(new BigDecimal("3.0"));
        assertThat(status.currencyEcho()).isEqualTo("USD");
        wireMock.verify(getRequestedFor(urlEqualTo(QUERY_PATH))
                .withBasicAuth(new com.github.tomakehurst.wiremock.client.BasicCredentials("ecocash", "test-password")));
    }

    @Test
    @DisplayName("query: FAILED maps to FAILED — the positive 'subscriber rejected' answer")
    void query_failedShape() {
        wireMock.stubFor(get(urlEqualTo(QUERY_PATH)).willReturn(okJson(QUERY_FAILED)));

        EcocashChargeStatus status = newClient(wireMock.baseUrl()).query(MSISDN, CORRELATOR);

        assertThat(status.outcome()).isEqualTo(EcocashChargeStatus.Outcome.FAILED);
    }

    @Test
    @DisplayName("query: still-pending stays PENDING; an unrecognised status also lands PENDING (open set)")
    void query_pendingAndUnrecognised() {
        wireMock.stubFor(get(urlEqualTo(QUERY_PATH)).willReturn(okJson("""
                {"clientCorrelator":"1763385010123456",
                 "transactionOperationStatus":"PENDING SUBSCRIBER VALIDATION"}""")));
        assertThat(newClient(wireMock.baseUrl()).query(MSISDN, CORRELATOR).outcome())
                .isEqualTo(EcocashChargeStatus.Outcome.PENDING);

        wireMock.resetAll();
        wireMock.stubFor(get(urlEqualTo(QUERY_PATH)).willReturn(okJson("""
                {"transactionOperationStatus":"CHARGED"}""")));
        assertThat(newClient(wireMock.baseUrl()).query(MSISDN, CORRELATOR).outcome())
                .isEqualTo(EcocashChargeStatus.Outcome.PENDING);
    }

    @Test
    @DisplayName("query: 404 is the positive NOT_FOUND answer, not an error")
    void query_notFound() {
        wireMock.stubFor(get(urlEqualTo(QUERY_PATH)).willReturn(aResponse().withStatus(404)));

        assertThat(newClient(wireMock.baseUrl()).query(MSISDN, CORRELATOR).outcome())
                .isEqualTo(EcocashChargeStatus.Outcome.NOT_FOUND);
    }

    @Test
    @DisplayName("query: an unreadable 200 maps to UNKNOWN — which never expires a row")
    void query_unparseable() {
        wireMock.stubFor(get(urlEqualTo(QUERY_PATH))
                .willReturn(aResponse().withStatus(200).withBody("<html>gateway error page</html>")));

        assertThat(newClient(wireMock.baseUrl()).query(MSISDN, CORRELATOR).outcome())
                .isEqualTo(EcocashChargeStatus.Outcome.UNKNOWN);
    }

    @Test
    @DisplayName("query: 5xx retries once (read-only) then surfaces transient")
    void query_retriesOnTransient() {
        wireMock.stubFor(get(urlEqualTo(QUERY_PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> newClient(wireMock.baseUrl()).query(MSISDN, CORRELATOR))
                .isInstanceOf(EcocashApiTransientException.class);

        wireMock.verify(2, getRequestedFor(urlEqualTo(QUERY_PATH)));
    }

    @Test
    @DisplayName("connect-refused surfaces transient (charge) — separate client on a closed port")
    void connectRefused_transient() {
        // Known-closed port; never stop/restart the shared server (its dynamic
        // port would change and break other tests).
        EcocashEipClient client = newClient("http://127.0.0.1:1");

        assertThatThrownBy(() -> client.charge(CORRELATOR, MSISDN, 300, "USD", "TKZ-TEST-REF"))
                .isInstanceOf(EcocashApiTransientException.class);
    }

    @Test
    @DisplayName("guard rails: unconfigured client refuses locally; out-of-shape stored values never reach the wire")
    void guardRails() {
        EcocashProperties blank = new EcocashProperties();
        EcocashEipClient unconfigured = new EcocashEipClient(blank, new ObjectMapper(),
                RetryRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults());
        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.charge(CORRELATOR, MSISDN, 300, "USD", "X"))
                .isInstanceOf(EcocashApiException.class);

        // A corrupted stored correlator must not be spliced into a URL.
        EcocashEipClient client = newClient(wireMock.baseUrl());
        EcocashChargeStatus status = client.query(MSISDN, "../../etc/passwd");
        assertThat(status.outcome()).isEqualTo(EcocashChargeStatus.Outcome.UNKNOWN);
        wireMock.verify(0, getRequestedFor(urlMatching("/payment/v1/.*")));
    }

    @Test
    @DisplayName("notify-URL split: credentials alone allow resolving but not starting (half-provisioned gate)")
    void notifyUrlSplit() {
        EcocashProperties props = new EcocashProperties();
        props.setBaseUrl(wireMock.baseUrl());
        props.setApiUsername("u");
        props.setApiPassword("p");
        props.setMerchantCode("8003");
        props.setMerchantNumber("789111401");
        props.setMerchantPin("1234");
        // no notifyUrl
        EcocashEipClient client = new EcocashEipClient(props, new ObjectMapper(),
                RetryRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults());
        assertThat(client.isConfigured()).isTrue();
        assertThat(client.canStartCharge()).isFalse();

        props.setNotifyUrl("relative/path");
        assertThat(client.canStartCharge()).isFalse();
        props.setNotifyUrl("https://tickets.example.co.zw/foundry/payments/ecocash/notify");
        assertThat(client.canStartCharge()).isTrue();
    }
}
