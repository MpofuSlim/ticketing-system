package innbucks.paymentservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
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
 * Contract test for {@link InnbucksCoreGatewayClient}. Pins each response
 * shape the gateway has been observed to return so any change in the gateway's
 * wire contract fails the build at PR time.
 *
 * <p>Banking-discipline test cases:
 * <ul>
 *   <li>200 + outcome=COMPLETED → returns response (success).</li>
 *   <li>200 + outcome=REJECTED_INSUFFICIENT_FUNDS → returns response, does NOT
 *       throw — the caller switches on outcome to decide ledger transition.
 *       (If this regressed to throwing, Resilience4j would burn its retry
 *       budget on permanent rejections — exactly the bug the envelope-based
 *       contract was designed to prevent.)</li>
 *   <li>200 + outcome=DUPLICATE_DETECTED → returns response.</li>
 *   <li>503 → throws {@link InnbucksCoreGatewayTransientException} (retryable).</li>
 *   <li>400 → throws plain {@link InnbucksCoreGatewayException} (terminal).</li>
 *   <li>Empty body on 200 → throws transient (contract violation).</li>
 *   <li>Connection refused → throws transient.</li>
 *   <li>Wire-level: Idempotency-Key header + JSON body shape are exactly what
 *       the gateway expects.</li>
 * </ul>
 *
 * <p>Pure JUnit + WireMock, no {@code @SpringBootTest}. Retry is configured
 * one-shot and the CircuitBreaker never opens, so each test exercises a single
 * upstream call.
 */
class InnbucksCoreGatewayClientContractTest {

    private static final String IDEMPOTENCY_KEY = "test-idem-key-123";
    private static final String PAYMENT_REFERENCE = "TKT-PMT-abc-123";

    private static WireMockServer wireMock;
    private static InnbucksCoreGatewayClient client;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void startWireMockAndWireClient() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        objectMapper = new ObjectMapper();

        // One-shot retry / never-open CB so contract tests stay surgical.
        // Retry policy decisions are pinned in a separate resilience test, not here.
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.<Object>custom()
                .maxAttempts(1)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(InnbucksCoreGatewayTransientException.class)
                .build());
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(100)
                        .minimumNumberOfCalls(100)
                        .failureRateThreshold(100.0f)
                        .build());

        client = new InnbucksCoreGatewayClient(
                "http://localhost:" + wireMock.port(),
                500,
                2000,
                objectMapper,
                retryRegistry,
                circuitBreakerRegistry);
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
    @DisplayName("happy path: gateway 200 + outcome=COMPLETED → returns response, no exception")
    void debit_completed_returnsResponseWithUpstreamReference() {
        wireMock.stubFor(post(urlEqualTo("/payments/debit"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{"
                                + "\"paymentReference\":\"" + PAYMENT_REFERENCE + "\","
                                + "\"outcome\":\"COMPLETED\","
                                + "\"upstreamReference\":\"VNG-9af-2026-06-08-001\","
                                + "\"upstreamCode\":null,"
                                + "\"upstreamMessage\":null,"
                                + "\"error\":null"
                                + "}")));

        InnbucksCoreGatewayResponse response = client.debit(debitRequest(), IDEMPOTENCY_KEY);

        assertThat(response.outcome()).isEqualTo(PaymentOutcome.COMPLETED);
        assertThat(response.upstreamReference()).isEqualTo("VNG-9af-2026-06-08-001");
        assertThat(response.paymentReference()).isEqualTo(PAYMENT_REFERENCE);

        // Wire-level contract: path, Idempotency-Key header, body fields the
        // gateway requires. A rename / drop of any of these silently breaks
        // delivery, so they're pinned here.
        wireMock.verify(postRequestedFor(urlEqualTo("/payments/debit"))
                .withHeader("Idempotency-Key", equalTo(IDEMPOTENCY_KEY))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.paymentReference", equalTo(PAYMENT_REFERENCE)))
                .withRequestBody(matchingJsonPath("$.customerMsisdn", equalTo("+263782606983")))
                .withRequestBody(matchingJsonPath("$.customerAccount", equalTo("CUST-WALLET-1")))
                .withRequestBody(matchingJsonPath("$.merchantAccount", equalTo("MERCHANT-WALLET-1")))
                // Jackson serialises new BigDecimal("100.00") as JSON 100.0
                // (drops insignificant trailing zeros). The numeric value is
                // unchanged; we just have to match what's on the wire.
                .withRequestBody(matchingJsonPath("$.amount", equalTo("100.0")))
                .withRequestBody(matchingJsonPath("$.currency", equalTo("USD")))
                .withRequestBody(matchingJsonPath("$.narration", equalTo("Ticketing payment"))));
    }

    @Test
    @DisplayName("terminal rejection: 200 + outcome=REJECTED_INSUFFICIENT_FUNDS → returns response, no throw")
    void debit_insufficientFunds_returnsResponseDoesNotThrow() {
        // CRITICAL banking-discipline assertion: the gateway returns 200 (not
        // 4xx) for a terminal rejection so Resilience4j retry does NOT fire.
        // If this regressed to throwing, the customer would wait 3 retry
        // attempts before learning their balance is too low.
        wireMock.stubFor(post(urlEqualTo("/payments/debit"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{"
                                + "\"paymentReference\":\"" + PAYMENT_REFERENCE + "\","
                                + "\"outcome\":\"REJECTED_INSUFFICIENT_FUNDS\","
                                + "\"upstreamReference\":null,"
                                + "\"upstreamCode\":\"NOT_SUFFICIENT_FUNDS\","
                                + "\"upstreamMessage\":\"Customer balance insufficient for transaction\","
                                + "\"error\":\"veengu debit rejected: HTTP 400 NOT_SUFFICIENT_FUNDS\""
                                + "}")));

        InnbucksCoreGatewayResponse response = client.debit(debitRequest(), IDEMPOTENCY_KEY);

        assertThat(response.outcome()).isEqualTo(PaymentOutcome.REJECTED_INSUFFICIENT_FUNDS);
        assertThat(response.upstreamCode()).isEqualTo("NOT_SUFFICIENT_FUNDS");
        assertThat(response.upstreamMessage()).contains("balance insufficient");
        assertThat(response.upstreamReference()).isNull();
    }

    @Test
    @DisplayName("duplicate detection: 200 + outcome=DUPLICATE_DETECTED → returns response")
    void debit_duplicateDetected_returnsResponse() {
        // Veengu's validateDuplicates=true rejected this paymentReference as a
        // duplicate; previous attempt landed. Caller leaves PENDING; reconciler
        // resolves later (or, in a future PR, the GET-status endpoint).
        wireMock.stubFor(post(urlEqualTo("/payments/debit"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{"
                                + "\"paymentReference\":\"" + PAYMENT_REFERENCE + "\","
                                + "\"outcome\":\"DUPLICATE_DETECTED\","
                                + "\"upstreamCode\":\"RESOURCE_ALREADY_EXISTS\""
                                + "}")));

        InnbucksCoreGatewayResponse response = client.debit(debitRequest(), IDEMPOTENCY_KEY);

        assertThat(response.outcome()).isEqualTo(PaymentOutcome.DUPLICATE_DETECTED);
        assertThat(response.upstreamCode()).isEqualTo("RESOURCE_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("503: gateway/veengu unreachable → throws TransientException")
    void debit_503_throwsTransient() {
        // The gateway returns 503 when it couldn't reach veengu (discovery /
        // connectivity failure). The body still carries the envelope, but the
        // status is what flags it as retryable.
        wireMock.stubFor(post(urlEqualTo("/payments/debit"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{"
                                + "\"paymentReference\":\"" + PAYMENT_REFERENCE + "\","
                                + "\"outcome\":\"UPSTREAM_UNAVAILABLE\","
                                + "\"error\":\"veengu debit unreachable: connect timeout\""
                                + "}")));

        // Status preserved on the exception's getStatusCode(); the message
        // mirrors OradianMiddlewareClient's shape and does NOT embed the
        // status code, so we assert on the field directly.
        assertThatThrownBy(() -> client.debit(debitRequest(), IDEMPOTENCY_KEY))
                .isInstanceOf(InnbucksCoreGatewayTransientException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        InnbucksCoreGatewayTransientException.class))
                .satisfies(e -> assertThat(e.getStatusCode()).isEqualTo(503));
    }

    @Test
    @DisplayName("400: gateway validation error → throws plain (non-transient) exception")
    void debit_400_throwsNonTransient() {
        // 400 means our request was malformed BEFORE veengu was called. That's
        // a payment-service bug — retry would just burn budget on a guaranteed
        // reject.
        wireMock.stubFor(post(urlEqualTo("/payments/debit"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{"
                                + "\"paymentReference\":null,"
                                + "\"outcome\":\"REJECTED_VALIDATION\","
                                + "\"error\":\"paymentReference is required\""
                                + "}")));

        assertThatThrownBy(() -> client.debit(debitRequest(), IDEMPOTENCY_KEY))
                .isInstanceOf(InnbucksCoreGatewayException.class)
                .isNotInstanceOf(InnbucksCoreGatewayTransientException.class);
    }

    @Test
    @DisplayName("empty body on 200: treated as transient 502 (upstream contract violation)")
    void debit_emptyBodyOn200_throwsTransient() {
        wireMock.stubFor(post(urlEqualTo("/payments/debit"))
                .willReturn(aResponse().withStatus(200).withBody("")));

        assertThatThrownBy(() -> client.debit(debitRequest(), IDEMPOTENCY_KEY))
                .isInstanceOf(InnbucksCoreGatewayTransientException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    @DisplayName("connection refused: throws transient with 502")
    void debit_connectionRefused_throwsTransient() throws Exception {
        // Point a SEPARATE client at a known-closed port (open + immediately
        // close a server socket to grab a free port; subsequent connects get
        // RST). Keeps the shared WireMock untouched so other tests aren't
        // affected by execution order.
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }

        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.<Object>custom()
                .maxAttempts(1).waitDuration(Duration.ofMillis(1))
                .retryExceptions(InnbucksCoreGatewayTransientException.class).build());
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom().slidingWindowSize(100)
                        .minimumNumberOfCalls(100).failureRateThreshold(100.0f).build());

        InnbucksCoreGatewayClient deadClient = new InnbucksCoreGatewayClient(
                "http://localhost:" + closedPort,
                500, 500, objectMapper, retryRegistry, cbRegistry);

        assertThatThrownBy(() -> deadClient.debit(debitRequest(), IDEMPOTENCY_KEY))
                .isInstanceOf(InnbucksCoreGatewayTransientException.class)
                .hasMessageContaining("Unable to reach");
    }

    private static InnbucksCoreGatewayDebitRequest debitRequest() {
        return InnbucksCoreGatewayDebitRequest.builder()
                .paymentReference(PAYMENT_REFERENCE)
                .customerMsisdn("+263782606983")
                .customerAccount("CUST-WALLET-1")
                .merchantAccount("MERCHANT-WALLET-1")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .narration("Ticketing payment")
                .build();
    }
}
