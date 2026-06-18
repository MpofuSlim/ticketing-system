package innbucks.paymentservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import innbucks.paymentservice.dto.DepositAccount;
import innbucks.paymentservice.dto.DepositTransferRequest;
import innbucks.paymentservice.dto.DepositTransferResponse;
import innbucks.paymentservice.dto.WithdrawalRequest;
import innbucks.paymentservice.dto.WithdrawalResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link OradianMiddlewareClient} — runs every assertion
 * against a real WireMock HTTP server (TCP, headers, JSON serialisation)
 * rather than a RestClient-layer mock. Pins each request/response shape we
 * actually rely on from the Oradian middleware, so any drift in the wire
 * contract (renamed field, changed status text, missing header) fails this
 * test at PR time instead of misbehaving on a real money transfer.
 *
 * <p>Sibling of {@link OradianMiddlewareClientResilienceTest} (which uses
 * MockRestServiceServer to pin retry/circuit-breaker semantics). The two
 * tests are deliberately complementary:
 * <ul>
 *   <li>Resilience test → covers <em>when</em> the client retries / opens the breaker.
 *   <li>This contract test → covers <em>what</em> the client sends and how
 *       it parses each upstream shape (200 OK, 4xx rejection envelope,
 *       5xx upstream error, empty body, connection refused).
 * </ul>
 *
 * <p>Resilience4j is configured with retry/CB effectively disabled here
 * (max attempts = 1, breaker never opens) so each assertion exercises a
 * single upstream call — keeps the contract assertions surgical and
 * decouples them from the retry-policy choices the Resilience test pins.
 *
 * <p>Follows the audit-#10 template documented in CLAUDE.md
 * ("External-service contract tests"). Sibling of
 * user-service/.../SmsNotificationClientContractTest.
 */
class OradianMiddlewareClientContractTest {

    private static final String INTERNAL_TOKEN = "test-internal-token";
    private static final String IDEMPOTENCY_KEY = "idem-abc-123";
    private static final String OWNER_MSISDN = "+254712345678";

    private static WireMockServer wireMock;
    private static OradianMiddlewareClient client;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        // Retry + CB effectively disabled. We test wire shapes here, not policy.
        RetryConfig oneShot = RetryConfig.<Object>custom()
                .maxAttempts(1)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(OradianMiddlewareTransientException.class)
                .build();
        CircuitBreakerConfig neverOpen = CircuitBreakerConfig.custom()
                .slidingWindowSize(100).minimumNumberOfCalls(100)
                .failureRateThreshold(100.0f).build();

        client = new OradianMiddlewareClient(
                "http://localhost:" + wireMock.port(),
                500,  // connect timeout
                2000, // read timeout
                INTERNAL_TOKEN,
                new ObjectMapper(),
                RetryRegistry.of(oneShot),
                CircuitBreakerRegistry.of(neverOpen));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void resetStubs() {
        wireMock.resetAll();
    }

    private static DepositTransferRequest depositRequest() {
        return DepositTransferRequest.builder()
                .fromAccountId("A000001")
                .toAccountId("A000002")
                .amount("123.00")
                .notes("ticket purchase")
                .transactionDate(LocalDate.of(2026, 5, 19))
                .build();
    }

    @Test
    @DisplayName("deposit transfer happy path: 200 → response parsed, all headers + body asserted")
    void submitDepositTransfer_happyPath_pinsRequestAndResponseShape() {
        // Recorded from a real successful Oradian transfer response.
        wireMock.stubFor(post(urlEqualTo("/internal/transfers/deposit"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "fromAccountId":"A000001",
                                  "toAccountId":"A000002",
                                  "amount":"123.00",
                                  "transactionID":"1155",
                                  "referenceNumber":"1234567980123",
                                  "transactionDate":"2026-05-19",
                                  "notes":"ticket purchase"
                                }
                                """)));

        DepositTransferResponse response = client.submitDepositTransfer(
                depositRequest(), IDEMPOTENCY_KEY, OWNER_MSISDN);

        // Response shape pinned: every field the caller relies on must be
        // populated from the upstream body.
        assertThat(response.getTransactionID()).isEqualTo("1155");
        assertThat(response.getReferenceNumber()).isEqualTo("1234567980123");
        assertThat(response.getFromAccountId()).isEqualTo("A000001");
        assertThat(response.getToAccountId()).isEqualTo("A000002");
        assertThat(response.getAmount()).isEqualTo("123.00");

        // Request shape pinned: every header Oradian middleware contractually
        // requires AND every JSON field it parses.
        wireMock.verify(postRequestedFor(urlEqualTo("/internal/transfers/deposit"))
                .withHeader("X-Internal-Token", equalTo(INTERNAL_TOKEN))
                .withHeader("Idempotency-Key", equalTo(IDEMPOTENCY_KEY))
                .withHeader("X-Owner-Msisdn", equalTo(OWNER_MSISDN))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.fromAccountId", equalTo("A000001")))
                .withRequestBody(matchingJsonPath("$.toAccountId", equalTo("A000002")))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("123.00")))
                .withRequestBody(matchingJsonPath("$.notes", equalTo("ticket purchase"))));
    }

    @Test
    @DisplayName("deposit transfer: 422 with ProblemDetail-shaped body → OradianMiddlewareException(422) with detail")
    void submitDepositTransfer_422InsufficientFunds_throwsPermanentWithDetail() {
        // The shape Oradian middleware returns for business rejections —
        // RFC-7807 ProblemDetail with 'detail' as the human-readable cause.
        wireMock.stubFor(post(urlEqualTo("/internal/transfers/deposit"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"type":"about:blank","title":"Unprocessable Entity",
                                 "status":422,"detail":"Insufficient funds in account A000001"}
                                """)));

        assertThatThrownBy(() -> client.submitDepositTransfer(
                depositRequest(), IDEMPOTENCY_KEY, OWNER_MSISDN))
                .isInstanceOf(OradianMiddlewareException.class)
                .isNotInstanceOf(OradianMiddlewareTransientException.class) // 4xx -> non-transient
                .satisfies(ex -> {
                    OradianMiddlewareException ome = (OradianMiddlewareException) ex;
                    assertThat(ome.getStatusCode()).isEqualTo(422);
                    assertThat(ome.getMessage()).contains("Insufficient funds in account A000001");
                });
    }

    @Test
    @DisplayName("deposit transfer: 401 from Oradian → permanent 401 (wrong X-Internal-Token)")
    void submitDepositTransfer_401_throwsPermanentWith401() {
        wireMock.stubFor(post(urlEqualTo("/internal/transfers/deposit"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"detail\":\"invalid_internal_token\"}")));

        assertThatThrownBy(() -> client.submitDepositTransfer(
                depositRequest(), IDEMPOTENCY_KEY, OWNER_MSISDN))
                .isInstanceOf(OradianMiddlewareException.class)
                .isNotInstanceOf(OradianMiddlewareTransientException.class)
                .satisfies(ex -> assertThat(((OradianMiddlewareException) ex).getStatusCode()).isEqualTo(401));
    }

    @Test
    @DisplayName("deposit transfer: 502 upstream → transient subclass so Retry would fire on it")
    void submitDepositTransfer_502_throwsTransientWith502() {
        wireMock.stubFor(post(urlEqualTo("/internal/transfers/deposit"))
                .willReturn(aResponse()
                        .withStatus(502)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"detail\":\"upstream Oradian rejected\"}")));

        assertThatThrownBy(() -> client.submitDepositTransfer(
                depositRequest(), IDEMPOTENCY_KEY, OWNER_MSISDN))
                .isInstanceOf(OradianMiddlewareTransientException.class)
                .satisfies(ex -> assertThat(((OradianMiddlewareException) ex).getStatusCode()).isEqualTo(502));
    }

    @Test
    @DisplayName("deposit transfer: 200 with empty body → transient 502 (treated as upstream contract violation)")
    void submitDepositTransfer_emptyBodyOn200_treatedAsTransient502() {
        // Documented in OradianMiddlewareClient: an empty body on a 2xx is a
        // contract violation that we surface as a transient 502 so Retry can
        // pick it up — same as a real upstream blip.
        wireMock.stubFor(post(urlEqualTo("/internal/transfers/deposit"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("")));

        assertThatThrownBy(() -> client.submitDepositTransfer(
                depositRequest(), IDEMPOTENCY_KEY, OWNER_MSISDN))
                .isInstanceOf(OradianMiddlewareTransientException.class)
                .satisfies(ex -> {
                    OradianMiddlewareException ome = (OradianMiddlewareException) ex;
                    assertThat(ome.getStatusCode()).isEqualTo(502);
                    assertThat(ome.getMessage()).contains("empty response");
                });
    }

    @Test
    @DisplayName("withdrawal happy path: 200 → response parsed, all required fields + commandID present")
    void submitWithdrawal_happyPath_pinsResponseShape() {
        wireMock.stubFor(post(urlEqualTo("/internal/transfers/withdraw"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "accountID":"A000015",
                                  "paymentMethodName":"Cash",
                                  "amount":"10.00",
                                  "transactionID":"1151",
                                  "referenceNumber":"1234567890123",
                                  "commandID":"210",
                                  "transactionDate":"2026-05-18",
                                  "transactionBranchID":"MobileBanking",
                                  "notes":"",
                                  "overrideLimitCheck":false
                                }
                                """)));

        WithdrawalRequest req = WithdrawalRequest.builder()
                .accountID("A000015")
                .paymentMethodName("Cash")
                .amount("10.00")
                .transactionDate(LocalDate.of(2026, 5, 18))
                .transactionBranchID("MobileBanking")
                .build();

        WithdrawalResponse response = client.submitWithdrawal(req, IDEMPOTENCY_KEY, OWNER_MSISDN);

        // The commandID is the field unique to withdrawals (vs deposit
        // transfers) — pin it explicitly so an upstream rename surfaces.
        assertThat(response.getTransactionID()).isEqualTo("1151");
        assertThat(response.getReferenceNumber()).isEqualTo("1234567890123");
        assertThat(response.getCommandID()).isEqualTo("210");

        wireMock.verify(postRequestedFor(urlEqualTo("/internal/transfers/withdraw"))
                .withHeader("X-Internal-Token", equalTo(INTERNAL_TOKEN))
                .withHeader("Idempotency-Key", equalTo(IDEMPOTENCY_KEY))
                .withHeader("X-Owner-Msisdn", equalTo(OWNER_MSISDN))
                .withRequestBody(matchingJsonPath("$.accountID", equalTo("A000015")))
                .withRequestBody(matchingJsonPath("$.paymentMethodName", equalTo("Cash"))));
    }

    @Test
    @DisplayName("deposits lookup: 200 with array → list parsed, string-typed fields preserved verbatim")
    void getDepositsForMsisdn_happyPath_pinsArrayShape() {
        // Real Oradian quirk: booleans + numbers come back as STRINGS
        // ("true", "1234.56"). The DTO uses String for these fields so
        // Jackson doesn't reject the payload — pin that here.
        // RestClient URL-encodes "+" in path vars to "%2B", so urlMatching
        // with a wildcard is the safe matcher here (urlEqualTo with the
        // literal "+" wouldn't match).
        wireMock.stubFor(get(urlMatching("/internal/customers/.+/deposits"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "ID":"A000001","internalID":"i-1",
                                    "balance":"1234.56","currencyCode":"KES",
                                    "status":"Active","isMainAccount":"true",
                                    "productID":"P1","productName":"Savings"
                                  },
                                  {
                                    "ID":"A000002","internalID":"i-2",
                                    "balance":"0.00","currencyCode":"KES",
                                    "status":"Frozen","isMainAccount":"false",
                                    "productID":"P1","productName":"Savings"
                                  }
                                ]
                                """)));

        List<DepositAccount> deposits = client.getDepositsForMsisdn(OWNER_MSISDN);

        assertThat(deposits).hasSize(2);
        assertThat(deposits.get(0).getID()).isEqualTo("A000001");
        assertThat(deposits.get(0).getBalance()).isEqualTo("1234.56"); // string, not BigDecimal
        assertThat(deposits.get(0).getStatus()).isEqualTo("Active");
        assertThat(deposits.get(0).getIsMainAccount()).isEqualTo("true"); // string, not boolean
        assertThat(deposits.get(1).getStatus()).isEqualTo("Frozen");

        wireMock.verify(getRequestedFor(urlMatching("/internal/customers/.+/deposits"))
                .withHeader("X-Internal-Token", equalTo(INTERNAL_TOKEN)));
    }

    @Test
    @DisplayName("deposits lookup: empty array → empty list, no exception")
    void getDepositsForMsisdn_emptyArray_returnsEmptyList() {
        // Customer exists but has no deposit accounts. NOT an error — the
        // ownership-check caller turns this into a 403 ("source account does
        // not belong to the authenticated customer") at the controller layer.
        wireMock.stubFor(get(urlMatching("/internal/customers/.*/deposits"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        List<DepositAccount> deposits = client.getDepositsForMsisdn(OWNER_MSISDN);
        assertThat(deposits).isEmpty();
    }

    @Test
    @DisplayName("deposits lookup: 404 → permanent OradianMiddlewareException(404)")
    void getDepositsForMsisdn_404_throwsPermanent() {
        wireMock.stubFor(get(urlMatching("/internal/customers/.*/deposits"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"detail\":\"customer not found\"}")));

        assertThatThrownBy(() -> client.getDepositsForMsisdn(OWNER_MSISDN))
                .isInstanceOf(OradianMiddlewareException.class)
                .isNotInstanceOf(OradianMiddlewareTransientException.class)
                .satisfies(ex -> assertThat(((OradianMiddlewareException) ex).getStatusCode()).isEqualTo(404));
    }

    @Test
    @DisplayName("connection refused (Oradian down): transient 502 so Retry/CB react correctly")
    void submitDepositTransfer_connectionRefused_throwsTransient502() throws Exception {
        // Grab a free port + close it immediately — subsequent connects on it
        // get RST. A separate client instance points at this dead port so the
        // shared WireMock isn't disturbed (don't stop/start it; the second
        // start picks a different dynamic port and breaks other tests).
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        OradianMiddlewareClient deadClient = new OradianMiddlewareClient(
                "http://localhost:" + closedPort,
                200, 200, INTERNAL_TOKEN,
                new ObjectMapper(),
                RetryRegistry.of(RetryConfig.<Object>custom()
                        .maxAttempts(1).waitDuration(Duration.ofMillis(1))
                        .retryExceptions(OradianMiddlewareTransientException.class).build()),
                CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                        .slidingWindowSize(100).minimumNumberOfCalls(100)
                        .failureRateThreshold(100.0f).build()));

        assertThatThrownBy(() -> deadClient.submitDepositTransfer(
                depositRequest(), IDEMPOTENCY_KEY, OWNER_MSISDN))
                .isInstanceOf(OradianMiddlewareTransientException.class)
                .satisfies(ex -> {
                    // Class wraps connect failure as 502 so Retry's
                    // retry-exceptions filter fires on the next attempt.
                    assertThat(((OradianMiddlewareException) ex).getStatusCode()).isEqualTo(502);
                    assertThat(ex.getMessage()).containsIgnoringCase("unable to reach");
                });
    }
}
