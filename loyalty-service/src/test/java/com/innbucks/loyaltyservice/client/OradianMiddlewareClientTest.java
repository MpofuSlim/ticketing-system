package com.innbucks.loyaltyservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.client.dto.CreditDepositAccountRequest;
import com.innbucks.loyaltyservice.client.dto.CreditDepositAccountResponse;
import com.innbucks.loyaltyservice.client.dto.DepositAccount;
import com.innbucks.loyaltyservice.client.dto.DepositAccountSnapshot;
import com.innbucks.loyaltyservice.client.dto.WithdrawDepositAccountRequest;
import com.innbucks.loyaltyservice.client.dto.WithdrawDepositAccountResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link OradianMiddlewareClient}. Uses
 * {@link MockRestServiceServer} bound to a fresh RestClient builder —
 * we then swap that built RestClient into the client via reflection,
 * giving fine-grained control over the wire-level interaction without
 * having to stand up a real HTTP server or the full Spring context.
 *
 * <p>Resilience4j is given trivial configs (retry: no retries / no wait;
 * circuit breaker: high threshold so it never trips during a single
 * test) so the wire assertions remain deterministic.
 */
class OradianMiddlewareClientTest {

    private static final String INTERNAL_TOKEN = "test-internal-token";
    private static final String BASE_URL = "http://oradian.test.local";

    private OradianMiddlewareClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl(BASE_URL).build();

        // Trivial Resilience4j configs: no retries, breaker that effectively
        // can't trip in one test. Keeps the wire-level assertions clean.
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(1)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(OradianMiddlewareTransientException.class)
                .build());
        CircuitBreakerRegistry breakerRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(100)
                .minimumNumberOfCalls(100)
                .failureRateThreshold(100f)
                .build());

        // Construct via the @Value constructor with dummy values; we
        // immediately swap the RestClient field for the MockRestServiceServer-
        // bound one so wire-level assertions actually run.
        client = new OradianMiddlewareClient(
                BASE_URL, 2000, 10000, INTERNAL_TOKEN,
                new ObjectMapper(),
                retryRegistry, breakerRegistry);
        ReflectionTestUtils.setField(client, "restClient", restClient);
    }

    @Test
    void creditDepositAccount_postsToMiddleware_withIdempotencyAndInternalTokenHeaders() {
        String responseBody = """
                {
                  "accountID": "A000015",
                  "paymentMethodName": "LoyaltyPointsIssue",
                  "transactionDate": "2026-05-19",
                  "amount": "50.0000",
                  "transactionBranchID": "MobileBanking",
                  "notes": "earn:PURCHASE",
                  "referenceNumber": "EARN-77",
                  "transactionID": "TX-9999",
                  "commandID": "CMD-7777"
                }
                """;
        server.expect(requestTo(BASE_URL + "/internal/transfers/credit"))
                .andExpect(method(POST))
                .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(header("Idempotency-Key", "sync-txn-abc-123"))
                .andExpect(content().contentType(APPLICATION_JSON))
                // Spot-check: the request must NOT carry overrideLimitCheck —
                // that field is withdraw-only.
                .andExpect(content().json("""
                        {"accountID":"A000015","amount":"50.0000","paymentMethodName":"LoyaltyPointsIssue"}
                        """))
                .andRespond(withSuccess(responseBody, APPLICATION_JSON));

        CreditDepositAccountRequest req = new CreditDepositAccountRequest(
                "A000015", "LoyaltyPointsIssue",
                LocalDate.parse("2026-05-19"),
                "50.0000", "MobileBanking",
                "earn:PURCHASE", "EARN-77");

        CreditDepositAccountResponse resp = client.creditDepositAccount(req, "sync-txn-abc-123");

        assertThat(resp.accountID()).isEqualTo("A000015");
        assertThat(resp.transactionID()).isEqualTo("TX-9999");
        assertThat(resp.commandID()).isEqualTo("CMD-7777");
        assertThat(resp.referenceNumber()).isEqualTo("EARN-77");
        server.verify();
    }

    @Test
    void creditDepositAccount_4xxFromMiddleware_throwsPermanentException() {
        // 4xx is a customer-side problem (validation, idempotency
        // conflict). Must throw the PERMANENT exception so the Retry
        // instance doesn't burn its attempts on something that won't
        // recover.
        server.expect(requestTo(BASE_URL + "/internal/transfers/credit"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(APPLICATION_JSON)
                        .body("""
                                {"errorCode":"idempotency_conflict","detail":"key reused with different body"}
                                """));

        CreditDepositAccountRequest req = new CreditDepositAccountRequest(
                "A000015", "LoyaltyPointsIssue", LocalDate.now(),
                "50.00", "MobileBanking", null, null);

        assertThatThrownBy(() -> client.creditDepositAccount(req, "k"))
                .isInstanceOf(OradianMiddlewareException.class)
                // CRITICAL: NOT a transient subclass — the Retry instance
                // would otherwise re-fire the same call.
                .isNotInstanceOf(OradianMiddlewareTransientException.class)
                .hasMessageContaining("key reused with different body");
    }

    @Test
    void creditDepositAccount_5xxFromMiddleware_throwsTransientException() {
        server.expect(requestTo(BASE_URL + "/internal/transfers/credit"))
                .andRespond(withServerError());

        CreditDepositAccountRequest req = new CreditDepositAccountRequest(
                "A000015", "LoyaltyPointsIssue", LocalDate.now(),
                "50.00", "MobileBanking", null, null);

        assertThatThrownBy(() -> client.creditDepositAccount(req, "k"))
                .isInstanceOf(OradianMiddlewareTransientException.class);
    }

    @Test
    void withdrawFromDepositAccount_postsToMiddleware_withOverrideLimitCheckInBody() {
        String responseBody = """
                {
                  "overrideLimitCheck": false,
                  "accountID": "A000015",
                  "paymentMethodName": "LoyaltyPointsRedeem",
                  "transactionDate": "2026-05-19",
                  "amount": "20.0000",
                  "transactionBranchID": "MobileBanking",
                  "notes": "redeem:VOUCHER",
                  "referenceNumber": "REDEEM-42",
                  "transactionID": "TX-100",
                  "commandID": "CMD-101"
                }
                """;
        server.expect(requestTo(BASE_URL + "/internal/transfers/withdraw"))
                .andExpect(method(POST))
                .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(header("Idempotency-Key", "redeem-key-1"))
                // Withdraw MUST carry overrideLimitCheck — its absence is
                // the wire-format signature of a buggy caller-side DTO.
                .andExpect(content().json("""
                        {"overrideLimitCheck":false,"accountID":"A000015","amount":"20.0000"}
                        """))
                .andRespond(withSuccess(responseBody, APPLICATION_JSON));

        WithdrawDepositAccountRequest req = new WithdrawDepositAccountRequest(
                false, "A000015", "LoyaltyPointsRedeem",
                LocalDate.parse("2026-05-19"),
                "20.0000", "MobileBanking", "redeem:VOUCHER");

        WithdrawDepositAccountResponse resp = client.withdrawFromDepositAccount(req, "redeem-key-1");

        assertThat(resp.accountID()).isEqualTo("A000015");
        assertThat(resp.transactionID()).isEqualTo("TX-100");
        server.verify();
    }

    @Test
    void getDepositAccount_getsByPath_andProjectsSnapshot() {
        String responseBody = """
                {
                  "ID": "A8347323",
                  "balance": "7500.00",
                  "status": "Active",
                  "productID": "LPW",
                  "currencyCode": "KES",
                  "clientID": "C000123"
                }
                """;
        server.expect(requestTo(BASE_URL + "/internal/deposits/A8347323"))
                .andExpect(method(GET))
                .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
                .andRespond(withSuccess(responseBody, APPLICATION_JSON));

        DepositAccountSnapshot snap = client.getDepositAccount("A8347323");

        assertThat(snap.ID()).isEqualTo("A8347323");
        assertThat(snap.balance()).isEqualTo("7500.00");
        assertThat(snap.productID()).isEqualTo("LPW");
        assertThat(snap.status()).isEqualTo("Active");
    }

    @Test
    void getDepositAccount_blankAccountId_throwsImmediately() {
        // Defensive: a blank accountId can't ever produce a valid call.
        // We must throw BEFORE the wire so the breaker doesn't see
        // a fake failure rate from accidental empty inputs.
        assertThatThrownBy(() -> client.getDepositAccount(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId");
        assertThatThrownBy(() -> client.getDepositAccount(null))
                .isInstanceOf(IllegalArgumentException.class);

        // No expectations registered → server.verify() passes only if
        // the client didn't hit the wire.
        server.verify();
    }

    @Test
    void getDepositsForMsisdn_returnsListAndFilterableByProductId() {
        // The lazy-LPW-discovery use case: walk this list, find the row
        // where productID == "LPW", store its ID on the wallet. The
        // client itself doesn't filter — that's the caller's
        // responsibility — but the test pins the wire shape callers
        // will rely on.
        String responseBody = """
                [
                  {"ID":"A123","productID":"SAVINGS","balance":"0.00","status":"Active"},
                  {"ID":"A8347323","productID":"LPW","balance":"7500.00","status":"Active","currencyCode":"KES"},
                  {"ID":"A456","productID":"CURRENT","balance":"100.00","status":"Active"}
                ]
                """;
        server.expect(requestTo(BASE_URL + "/internal/customers/254712345678/deposits"))
                .andExpect(method(GET))
                .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
                .andRespond(withSuccess(responseBody, APPLICATION_JSON));

        List<DepositAccount> deposits = client.getDepositsForMsisdn("254712345678");

        assertThat(deposits).hasSize(3);
        DepositAccount lpw = deposits.stream()
                .filter(d -> "LPW".equals(d.productID()))
                .findFirst().orElseThrow();
        assertThat(lpw.ID()).isEqualTo("A8347323");
        assertThat(lpw.balance()).isEqualTo("7500.00");
        assertThat(lpw.currencyCode()).isEqualTo("KES");
    }

    @Test
    void getDepositsForMsisdn_blankMsisdn_returnsEmptyWithoutHittingWire() {
        // Defensive: blank msisdn = "no customer to look up". Return
        // empty list, don't bother the middleware.
        assertThat(client.getDepositsForMsisdn("")).isEmpty();
        assertThat(client.getDepositsForMsisdn(null)).isEmpty();
        server.verify();
    }
}
