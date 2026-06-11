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
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link BankApiClient} against the public InnBucks Bank
 * API (spec pinned at {@code docs/api/innbucks-bank-api.postman_collection.json}).
 *
 * <p>IMPORTANT: the collection ships no response examples, so the response
 * shapes stubbed here are the classifier's ASSUMED conventions (status/
 * responseCode markers, reference keys). When real staging responses are
 * captured, update these stubs and the classifier together — that is the
 * point of pinning them: a contract drift fails the build, not production.
 *
 * <p>Pure JUnit + WireMock, no Spring context. Retry registry uses a real
 * 2-attempt config so the idempotent-only retry behaviour is observable.
 */
class BankApiClientContractTest {

    private static WireMockServer wireMock;
    private static BankApiClient client;

    private static BankApiClient newClient(String baseUrl) {
        BankApiProperties props = new BankApiProperties();
        props.setBaseUrl(baseUrl);
        props.setApiKey("test-api-key");
        props.setUsername("client-user");
        props.setPassword("client-pass");
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(2000);
        props.setTokenTtl(Duration.ofMinutes(5));
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(BankApiTransientException.class)
                .build());
        return new BankApiClient(props, new ObjectMapper(), retries, CircuitBreakerRegistry.ofDefaults());
    }

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        client = newClient("http://localhost:" + wireMock.port());
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private void stubLogin() {
        wireMock.stubFor(post(urlEqualTo("/auth/third-party"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"jwt-token-1\"}")));
    }

    private static BankPaymentCommand command() {
        return new BankPaymentCommand(new BigDecimal("100.00"), "USD",
                "Ticketing payment TKT-PMT-x", "2005110665576", "2009200566693", "TKT-PMT-x");
    }

    @Test
    @DisplayName("login: posts X-Api-Key + credentials, caches the token across calls")
    void login_sendsApiKeyAndCredentials_andCachesToken() {
        BankApiClient fresh = newClient("http://localhost:" + wireMock.port());
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/bank/api/payment"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"SUCCESS\",\"reference\":\"BANK-1\"}")));

        fresh.pay(command());
        fresh.pay(new BankPaymentCommand(new BigDecimal("50.00"), "USD",
                "n2", "src", "dst", "TKT-PMT-y"));

        // Exactly ONE login despite two payments — the token is cached.
        wireMock.verify(1, postRequestedFor(urlEqualTo("/auth/third-party"))
                .withHeader("X-Api-Key", equalTo("test-api-key"))
                .withRequestBody(matchingJsonPath("$.username", equalTo("client-user")))
                .withRequestBody(matchingJsonPath("$.password", equalTo("client-pass"))));
    }

    @Test
    @DisplayName("payment: wire shape — X-Api-Key + Bearer + the documented body fields")
    void pay_postsDocumentedShape() {
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/bank/api/payment"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"SUCCESS\",\"reference\":\"BANK-9\"}")));

        BankPaymentResult result = newClient("http://localhost:" + wireMock.port()).pay(command());

        assertThat(result.outcome()).isEqualTo(PaymentOutcome.COMPLETED);
        assertThat(result.reference()).isEqualTo("BANK-9");
        wireMock.verify(postRequestedFor(urlEqualTo("/bank/api/payment"))
                .withHeader("X-Api-Key", equalTo("test-api-key"))
                .withHeader("Authorization", equalTo("Bearer jwt-token-1"))
                // Jackson renders BigDecimal("100.00") as a JSON number whose
                // string form is 100.0 — match the numeric value, not the scale.
                .withRequestBody(matchingJsonPath("$.amount", matching("100\\.0+")))
                .withRequestBody(matchingJsonPath("$.currency", equalTo("USD")))
                .withRequestBody(matchingJsonPath("$.type", equalTo("CARD_ON_US")))
                .withRequestBody(matchingJsonPath("$.sourceAccount", equalTo("2005110665576")))
                .withRequestBody(matchingJsonPath("$.destinationAccount", equalTo("2009200566693")))
                .withRequestBody(matchingJsonPath("$.participantReference", equalTo("TKT-PMT-x"))));
    }

    @Test
    @DisplayName("payment: ambiguous 2xx body → PROCESSING (never a guessed success)")
    void pay_ambiguousBody_isProcessing() {
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/bank/api/payment"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"something\":\"else\"}")));

        BankPaymentResult result = newClient("http://localhost:" + wireMock.port()).pay(command());

        assertThat(result.outcome()).isEqualTo(PaymentOutcome.PROCESSING);
    }

    @Test
    @DisplayName("payment: 4xx with INSUFFICIENT marker → clean decline, not an exception")
    void pay_insufficientFunds4xx_isDecline() {
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/bank/api/payment"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"FAILED\",\"message\":\"Insufficient funds in wallet\"}")));

        BankPaymentResult result = newClient("http://localhost:" + wireMock.port()).pay(command());

        assertThat(result.outcome()).isEqualTo(PaymentOutcome.REJECTED_INSUFFICIENT_FUNDS);
        assertThat(result.message()).contains("Insufficient");
    }

    @Test
    @DisplayName("payment: generic 4xx without decline markers → permanent BankApiException")
    void pay_generic4xx_throwsPermanent() {
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/bank/api/payment"))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"oops\":\"malformed request\"}")));

        assertThatThrownBy(() -> newClient("http://localhost:" + wireMock.port()).pay(command()))
                .isInstanceOf(BankApiException.class)
                .isNotInstanceOf(BankApiTransientException.class);
    }

    @Test
    @DisplayName("payment: 5xx → transient (caller parks IN_DOUBT); NOT auto-retried")
    void pay_5xx_isTransient_andNotRetried() {
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/bank/api/payment"))
                .willReturn(aResponse().withStatus(503).withBody("down")));

        assertThatThrownBy(() -> newClient("http://localhost:" + wireMock.port()).pay(command()))
                .isInstanceOf(BankApiTransientException.class);

        // The money-mover must hit the wire exactly ONCE even though the
        // retry registry would allow 2 attempts — payments are never retried.
        wireMock.verify(1, postRequestedFor(urlEqualTo("/bank/api/payment")));
    }

    @Test
    @DisplayName("payment: 401 → one token refresh + replay, then success")
    void pay_401_refreshesTokenOnce_andReplays() {
        // First login token gets rejected; refreshed token succeeds.
        wireMock.stubFor(post(urlEqualTo("/auth/third-party"))
                .inScenario("relogin").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"stale-token\"}"))
                .willSetStateTo("second-login"));
        wireMock.stubFor(post(urlEqualTo("/auth/third-party"))
                .inScenario("relogin").whenScenarioStateIs("second-login")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"fresh-token\"}")));
        wireMock.stubFor(post(urlEqualTo("/bank/api/payment"))
                .withHeader("Authorization", equalTo("Bearer stale-token"))
                .willReturn(aResponse().withStatus(401)));
        wireMock.stubFor(post(urlEqualTo("/bank/api/payment"))
                .withHeader("Authorization", equalTo("Bearer fresh-token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"SUCCESS\",\"reference\":\"BANK-2\"}")));

        BankPaymentResult result = newClient("http://localhost:" + wireMock.port()).pay(command());

        assertThat(result.outcome()).isEqualTo(PaymentOutcome.COMPLETED);
        wireMock.verify(2, postRequestedFor(urlEqualTo("/auth/third-party")));
    }

    @Test
    @DisplayName("linked account: strips '+', extracts accountNumber (root or nested)")
    void findWalletAccount_extractsAccountNumber() {
        stubLogin();
        wireMock.stubFor(get(urlEqualTo("/bank/api/account/msisdn/263782341479"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"accountNumber\":\"2005110665576\"}}")));

        Optional<String> account = newClient("http://localhost:" + wireMock.port())
                .findWalletAccount("+263782341479");

        assertThat(account).contains("2005110665576");
        wireMock.verify(getRequestedFor(urlEqualTo("/bank/api/account/msisdn/263782341479"))
                .withHeader("X-Api-Key", equalTo("test-api-key"))
                .withHeader("Authorization", equalTo("Bearer jwt-token-1")));
    }

    @Test
    @DisplayName("linked account: 404 → empty (no wallet), not an exception")
    void findWalletAccount_404_isEmpty() {
        stubLogin();
        wireMock.stubFor(get(urlEqualTo("/bank/api/account/msisdn/263700000000"))
                .willReturn(aResponse().withStatus(404)));

        assertThat(newClient("http://localhost:" + wireMock.port())
                .findWalletAccount("263700000000")).isEmpty();
    }

    @Test
    @DisplayName("inquiry: GET-with-body keyed by originalParticipantReference; classifies COMPLETED")
    void inquireTransaction_getWithBody_classifiesCompleted() {
        stubLogin();
        wireMock.stubFor(request("GET", urlEqualTo("/bank/api/transaction/inquiry"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"SUCCESS\",\"transactionReference\":\"BANK-77\"}")));

        BankInquiryResult result = newClient("http://localhost:" + wireMock.port())
                .inquireTransaction("2005110665576", "TKT-PMT-abc");

        assertThat(result.outcome()).isEqualTo(BankInquiryResult.Outcome.COMPLETED);
        assertThat(result.reference()).isEqualTo("BANK-77");
        wireMock.verify(com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
                .newRequestPattern(com.github.tomakehurst.wiremock.http.RequestMethod.GET,
                        urlEqualTo("/bank/api/transaction/inquiry"))
                .withRequestBody(matchingJsonPath("$.originalParticipantReference", equalTo("TKT-PMT-abc")))
                .withRequestBody(matchingJsonPath("$.accountNumber", equalTo("2005110665576"))));
    }

    @Test
    @DisplayName("inquiry: 404 → NOT_FOUND (submission never landed)")
    void inquireTransaction_404_isNotFound() {
        stubLogin();
        wireMock.stubFor(request("GET", urlEqualTo("/bank/api/transaction/inquiry"))
                .willReturn(aResponse().withStatus(404)));

        BankInquiryResult result = newClient("http://localhost:" + wireMock.port())
                .inquireTransaction(null, "TKT-PMT-missing");

        assertThat(result.outcome()).isEqualTo(BankInquiryResult.Outcome.NOT_FOUND);
    }

    @Test
    @DisplayName("unconfigured credentials: refuses before any HTTP call")
    void unconfigured_refusesWithoutNetwork() {
        BankApiProperties blank = new BankApiProperties();
        BankApiClient unconfigured = new BankApiClient(blank, new ObjectMapper(),
                RetryRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults());

        assertThatThrownBy(() -> unconfigured.pay(command()))
                .isInstanceOf(BankApiException.class)
                .hasMessageContaining("not configured");
        assertThat(unconfigured.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("connect refused: transient (separate dead-port client; shared WireMock untouched)")
    void connectRefused_isTransient() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        BankApiClient dead = newClient("http://localhost:" + closedPort);

        assertThatThrownBy(() -> dead.pay(command()))
                .isInstanceOf(BankApiTransientException.class);
    }
}
