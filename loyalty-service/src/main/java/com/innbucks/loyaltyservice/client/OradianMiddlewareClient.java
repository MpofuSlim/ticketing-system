package com.innbucks.loyaltyservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.client.dto.CreditDepositAccountRequest;
import com.innbucks.loyaltyservice.client.dto.CreditDepositAccountResponse;
import com.innbucks.loyaltyservice.client.dto.DepositAccount;
import com.innbucks.loyaltyservice.client.dto.DepositAccountSnapshot;
import com.innbucks.loyaltyservice.client.dto.WithdrawDepositAccountRequest;
import com.innbucks.loyaltyservice.client.dto.WithdrawDepositAccountResponse;
import com.innbucks.loyaltyservice.config.CorrelationIdPropagatingInterceptor;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Talks to Oradian middleware for the loyalty-LPW sync path.
 *
 * <p>Three outbound operations:
 * <ul>
 *   <li>{@link #creditDepositAccount} — earn / issuance of loyalty
 *       points; wraps middleware's {@code POST /internal/transfers/credit}
 *       which in turn calls Oradian's
 *       {@code instafin.EnterDepositOnDepositAccount}. Idempotency-Key is
 *       <b>required</b>; loyalty-service passes the local
 *       {@code OradianSyncTransaction.id} so retries naturally dedup.</li>
 *   <li>{@link #withdrawFromDepositAccount} — spend / redemption; wraps
 *       {@code POST /internal/transfers/withdraw}.</li>
 *   <li>{@link #getDepositAccount} — single-account state read for the
 *       reconciliation + balance-audit jobs; wraps
 *       {@code GET /internal/deposits/{accountId}}.</li>
 *   <li>{@link #getDepositsForMsisdn} — used by lazy LPW account discovery.
 *       Walks the customer's deposit list, the caller filters for
 *       {@code productID == "LPW"}.</li>
 * </ul>
 *
 * <p>Direct pod-to-pod — never via the api-gateway. The X-Internal-Token
 * shared secret comes from {@code oradian-middleware.internal-token}
 * (env {@code ORADIAN_INTERNAL_TOKEN}) and is DIFFERENT from
 * {@code innbucks.internal-api-token} (the loyalty webhook secret).
 *
 * <p>Every outbound call is wrapped by Resilience4j Retry +
 * CircuitBreaker via the {@code oradian-middleware} registry instances
 * configured in {@code application.yaml}. Retry kicks in only on
 * {@link OradianMiddlewareTransientException} — connectivity errors and
 * 5xx — so 4xx rejections fail immediately. When the circuit is open the
 * decorated supplier throws {@link CallNotPermittedException}, which
 * {@link #execute} maps to a 503-bearing transient exception so the sync
 * layer can stamp {@code UPSTREAM_UNAVAILABLE} cleanly.
 *
 * <p>Modelled after the payment-service {@code OradianMiddlewareClient}
 * so the two services classify Oradian failures identically and the
 * Resilience4j config shape stays uniform across the platform.
 */
@Component
@Slf4j
public class OradianMiddlewareClient {

    private static final String RESILIENCE_INSTANCE_NAME = "oradian-middleware";
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String internalToken;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public OradianMiddlewareClient(
            @Value("${oradian-middleware.base-url:http://localhost:8090}") String baseUrl,
            @Value("${oradian-middleware.connect-timeout-ms:2000}") int connectMs,
            @Value("${oradian-middleware.read-timeout-ms:10000}") int readMs,
            @Value("${oradian-middleware.internal-token:}") String internalToken,
            ObjectMapper objectMapper,
            RetryRegistry retryRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(Duration.ofMillis(readMs));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE_NAME);
    }

    /**
     * Earn / loyalty-points issuance against the customer's LPW
     * account. The {@code idempotencyKey} MUST be passed — the
     * middleware rejects the call without it. Loyalty-service passes
     * the {@code OradianSyncTransaction.id} so a network-retry from
     * us naturally dedups against the same upstream Oradian commit.
     */
    public CreditDepositAccountResponse creditDepositAccount(
            CreditDepositAccountRequest request, String idempotencyKey) {
        return execute(() -> doCredit(request, idempotencyKey));
    }

    /**
     * Spend / redemption against the customer's LPW account. Does NOT
     * currently require an Idempotency-Key on the middleware (Stage 1
     * deferred adding it to the existing withdraw endpoint), but we
     * send one regardless so that when the middleware backfills
     * idempotency support, loyalty-service is already producing the
     * right header.
     */
    public WithdrawDepositAccountResponse withdrawFromDepositAccount(
            WithdrawDepositAccountRequest request, String idempotencyKey) {
        return execute(() -> doWithdraw(request, idempotencyKey));
    }

    /**
     * Reconciliation / balance-audit read. Returns the flat snapshot
     * the middleware projects from Oradian's nested LookupDepositAccount
     * envelope. Used to (a) finalise stale {@code PENDING}
     * {@code OradianSyncTransaction} rows after their grace window
     * elapses, and (b) detect drift between local
     * {@code wallets.balance} and Oradian-canonical balance.
     */
    public DepositAccountSnapshot getDepositAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required");
        }
        return execute(() -> doGetAccount(accountId));
    }

    /**
     * Walks the customer's full deposits list — used by lazy LPW
     * account discovery on a wallet's first sync attempt. The caller
     * filters the result for {@code productID == "LPW"} and stores
     * the resulting {@code ID} on {@code wallets.oradian_account_id}.
     */
    public List<DepositAccount> getDepositsForMsisdn(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            return Collections.emptyList();
        }
        return execute(() -> doGetDepositsForMsisdn(msisdn));
    }

    // ------------------------------------------------------------------
    //  Inner call implementations.
    //  Every thrown exception is correctly typed (transient vs permanent)
    //  so the Retry instance can act on its retry-exceptions config.
    // ------------------------------------------------------------------

    private CreditDepositAccountResponse doCredit(CreditDepositAccountRequest request, String idempotencyKey) {
        try {
            CreditDepositAccountResponse response = restClient.post()
                    .uri("/internal/transfers/credit")
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .header(IDEMPOTENCY_HEADER, idempotencyKey)
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(CreditDepositAccountResponse.class);
            if (response == null) {
                throw new OradianMiddlewareTransientException(
                        "Oradian middleware returned an empty response body", 502);
            }
            log.info("Oradian credit succeeded account={} amount={} txnID={}",
                    request.accountID(), request.amount(), response.transactionID());
            return response;
        } catch (RestClientResponseException e) {
            String detail = parseErrorMessage(e.getResponseBodyAsString())
                    .orElse(e.getStatusText());
            int status = e.getStatusCode().value();
            log.warn("Oradian credit failed account={} status={} detail={}",
                    request.accountID(), status, detail);
            throw classifyHttpFailure(
                    "Oradian middleware rejected the credit: " + detail, status, e);
        } catch (OradianMiddlewareException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Oradian credit errored account={} cause={}", request.accountID(), e.toString());
            throw new OradianMiddlewareTransientException(
                    "Unable to reach Oradian middleware: " + e.getMessage(), 502, e);
        }
    }

    private WithdrawDepositAccountResponse doWithdraw(WithdrawDepositAccountRequest request, String idempotencyKey) {
        try {
            WithdrawDepositAccountResponse response = restClient.post()
                    .uri("/internal/transfers/withdraw")
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .header(IDEMPOTENCY_HEADER, idempotencyKey)
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(WithdrawDepositAccountResponse.class);
            if (response == null) {
                throw new OradianMiddlewareTransientException(
                        "Oradian middleware returned an empty response body", 502);
            }
            log.info("Oradian withdraw succeeded account={} amount={} txnID={}",
                    request.accountID(), request.amount(), response.transactionID());
            return response;
        } catch (RestClientResponseException e) {
            String detail = parseErrorMessage(e.getResponseBodyAsString())
                    .orElse(e.getStatusText());
            int status = e.getStatusCode().value();
            log.warn("Oradian withdraw failed account={} status={} detail={}",
                    request.accountID(), status, detail);
            throw classifyHttpFailure(
                    "Oradian middleware rejected the withdrawal: " + detail, status, e);
        } catch (OradianMiddlewareException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Oradian withdraw errored account={} cause={}", request.accountID(), e.toString());
            throw new OradianMiddlewareTransientException(
                    "Unable to reach Oradian middleware: " + e.getMessage(), 502, e);
        }
    }

    private DepositAccountSnapshot doGetAccount(String accountId) {
        try {
            DepositAccountSnapshot snapshot = restClient.get()
                    .uri("/internal/deposits/{accountId}", accountId)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .retrieve()
                    .body(DepositAccountSnapshot.class);
            if (snapshot == null) {
                throw new OradianMiddlewareTransientException(
                        "Oradian middleware returned an empty response body", 502);
            }
            return snapshot;
        } catch (RestClientResponseException e) {
            String detail = parseErrorMessage(e.getResponseBodyAsString())
                    .orElse(e.getStatusText());
            int status = e.getStatusCode().value();
            log.warn("Oradian account lookup failed accountId={} status={} detail={}",
                    accountId, status, detail);
            throw classifyHttpFailure(
                    "Oradian middleware rejected the account lookup: " + detail, status, e);
        } catch (OradianMiddlewareException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Oradian account lookup errored accountId={} cause={}", accountId, e.toString());
            throw new OradianMiddlewareTransientException(
                    "Unable to reach Oradian middleware: " + e.getMessage(), 502, e);
        }
    }

    private List<DepositAccount> doGetDepositsForMsisdn(String msisdn) {
        try {
            List<DepositAccount> deposits = restClient.get()
                    .uri("/internal/customers/{msisdn}/deposits", msisdn)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<DepositAccount>>() {});
            return deposits == null ? Collections.emptyList() : deposits;
        } catch (RestClientResponseException e) {
            String detail = parseErrorMessage(e.getResponseBodyAsString())
                    .orElse(e.getStatusText());
            int status = e.getStatusCode().value();
            // msisdn deliberately not logged in plaintext — masking is
            // handled at the call site if it cares.
            log.warn("Oradian deposits lookup failed status={} detail={}", status, detail);
            throw classifyHttpFailure(
                    "Oradian middleware rejected the deposits lookup: " + detail, status, e);
        } catch (OradianMiddlewareException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Oradian deposits lookup errored cause={}", e.toString());
            throw new OradianMiddlewareTransientException(
                    "Unable to reach Oradian middleware: " + e.getMessage(), 502, e);
        }
    }

    /**
     * Run the supplied upstream call inside the Retry + CircuitBreaker
     * decorators. Composed inside-out: CircuitBreaker wraps the raw call,
     * Retry wraps the CB. When the breaker is OPEN, Retry sees
     * CallNotPermittedException on every attempt and gives up immediately
     * (CallNotPermittedException isn't in retry-exceptions), so we don't
     * burn the customer's request time hammering a known-dead upstream.
     */
    private <T> T execute(Supplier<T> supplier) {
        Supplier<T> withCb = CircuitBreaker.decorateSupplier(circuitBreaker, supplier);
        Supplier<T> decorated = Retry.decorateSupplier(retry, withCb);
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            log.warn("Oradian middleware circuit breaker is OPEN — failing fast");
            throw new OradianMiddlewareTransientException(
                    "Oradian middleware is temporarily unavailable (circuit open)", 503, e);
        }
    }

    /**
     * Map an upstream HTTP status to the right exception class so the
     * Retry instance retries only on truly transient failures.
     * <ul>
     *   <li>5xx → transient (server-side problem, might recover on retry)</li>
     *   <li>4xx → permanent (validation, idempotency conflict,
     *       insufficient funds, ... retrying won't help)</li>
     * </ul>
     */
    private static OradianMiddlewareException classifyHttpFailure(
            String message, int status, Throwable cause) {
        if (status >= 500 && status < 600) {
            return new OradianMiddlewareTransientException(message, status, cause);
        }
        return new OradianMiddlewareException(message, status, cause);
    }

    /**
     * Pull a human-readable detail out of a ProblemDetail / ApiResult
     * error body. Tries {@code detail} first (ProblemDetail's standard
     * field), then {@code title}, then {@code message} (our ApiResult
     * envelope). Returns empty when the body isn't parseable JSON so
     * the caller falls back to statusText.
     */
    private Optional<String> parseErrorMessage(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {});
            Object detail = parsed.get("detail");
            if (detail != null) return Optional.of(detail.toString());
            Object title = parsed.get("title");
            if (title != null) return Optional.of(title.toString());
            Object message = parsed.get("message");
            return Optional.ofNullable(message == null ? null : message.toString());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
