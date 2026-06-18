package innbucks.paymentservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.config.CorrelationIdPropagatingInterceptor;
import innbucks.paymentservice.dto.DepositAccount;
import innbucks.paymentservice.dto.DepositTransferRequest;
import innbucks.paymentservice.dto.DepositTransferResponse;
import innbucks.paymentservice.dto.WithdrawalRequest;
import innbucks.paymentservice.dto.WithdrawalResponse;
import innbucks.paymentservice.util.MsisdnMasking;
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
 * Talks to Oradian middleware: deposit-account ownership lookups for
 * POST /payments/transfer (via GET /internal/customers/{msisdn}/deposits)
 * and the actual transfer (POST /internal/transfers/deposit, which calls
 * Oradian's instafin.SubmitDepositAccountTransfer). Direct pod-to-pod —
 * never via the api-gateway. The X-Internal-Token shared secret comes from
 * {@code oradian-middleware.internal-token} (env {@code ORADIAN_INTERNAL_TOKEN})
 * and is DIFFERENT from {@code innbucks.internal-api-token} — the latter is
 * the loyalty-service secret. Mixing them up gets you a 401 from Oradian.
 *
 * <p>Every outbound call is wrapped by Resilience4j Retry + CircuitBreaker
 * via the {@code oradian-middleware} registry instances configured in
 * {@code application.yaml}. Retry kicks in only on
 * {@link OradianMiddlewareTransientException} — connectivity errors and
 * 5xx from upstream — so 4xx validation rejections fail immediately
 * (retrying "Insufficient funds" doesn't help the customer). When the
 * circuit is open the decorated supplier throws
 * {@link CallNotPermittedException}, which {@link #execute} maps to a
 * 503-bearing transient exception so the FE can render an "upstream
 * temporarily unavailable" UX without burning the read-timeout.
 *
 * <p>Modelled after LoyaltyServiceClient so the wire conventions (JDK
 * HttpClient with explicit timeouts, correlation-id interceptor,
 * RestClientResponseException mapped to a typed domain exception that
 * carries the upstream status) stay uniform across all our S2S outbound
 * calls.
 */
@Component
@Slf4j
public class OradianMiddlewareClient {

    private static final String RESILIENCE_INSTANCE_NAME = "oradian-middleware";
    /** Required by the middleware's /internal/transfers/** endpoints for replay dedup. */
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    /**
     * Required by the middleware's /internal/transfers/** endpoints: the phone
     * of the account owner. The middleware re-verifies the source account
     * belongs to this customer (defence-in-depth ownership check) before
     * calling Oradian. We forward the JWT's phoneNumber claim — the same value
     * we already used for the local deposits-ownership lookup.
     */
    private static final String OWNER_MSISDN_HEADER = "X-Owner-Msisdn";

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
     * Submit a deposit-account transfer. {@code idempotencyKey} is forwarded as
     * the middleware's required {@code Idempotency-Key} header and MUST be
     * stable for the logical transfer (callers pass the transaction's own key).
     * That stability is what makes the Resilience4j retry safe: a read-timeout
     * after Oradian already moved the money triggers a retry that carries the
     * SAME key, so the middleware replays the cached response (Oradian's
     * {@code checkDuplicates} is the further backstop) instead of transferring
     * a second time.
     */
    public DepositTransferResponse submitDepositTransfer(
            DepositTransferRequest request, String idempotencyKey, String ownerMsisdn) {
        return execute(() -> doSubmitDepositTransfer(request, idempotencyKey, ownerMsisdn));
    }

    /** Submit a withdrawal. See {@link #submitDepositTransfer} for the {@code idempotencyKey} contract. */
    public WithdrawalResponse submitWithdrawal(
            WithdrawalRequest request, String idempotencyKey, String ownerMsisdn) {
        return execute(() -> doSubmitWithdrawal(request, idempotencyKey, ownerMsisdn));
    }

    public List<DepositAccount> getDepositsForMsisdn(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            return Collections.emptyList();
        }
        return execute(() -> doGetDepositsForMsisdn(msisdn));
    }

    private DepositTransferResponse doSubmitDepositTransfer(
            DepositTransferRequest request, String idempotencyKey, String ownerMsisdn) {
        try {
            DepositTransferResponse response = restClient.post()
                    .uri("/internal/transfers/deposit")
                    .header("X-Internal-Token", internalToken)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .header(OWNER_MSISDN_HEADER, ownerMsisdn)
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(DepositTransferResponse.class);
            if (response == null) {
                // Treat an empty body as an upstream contract violation —
                // same shape as a 502, retryable in case it's a brief glitch.
                throw new OradianMiddlewareTransientException(
                        "Oradian middleware returned an empty response body", 502);
            }
            log.info("Oradian deposit transfer succeeded from={} to={} txnID={}",
                    request.getFromAccountId(), request.getToAccountId(),
                    response.getTransactionID());
            return response;
        } catch (RestClientResponseException e) {
            String detail = parseErrorMessage(e.getResponseBodyAsString())
                    .orElse(e.getStatusText());
            int status = e.getStatusCode().value();
            log.warn("Oradian deposit transfer failed from={} to={} status={} detail={}",
                    request.getFromAccountId(), request.getToAccountId(), status, detail);
            throw classifyHttpFailure(
                    "Oradian middleware rejected the deposit transfer: " + detail,
                    status, e);
        } catch (OradianMiddlewareException e) {
            // Re-throw the empty-body / classified-HTTP case unchanged.
            throw e;
        } catch (Exception e) {
            log.warn("Oradian deposit transfer errored from={} to={} cause={}",
                    request.getFromAccountId(), request.getToAccountId(), e.toString());
            throw new OradianMiddlewareTransientException(
                    "Unable to reach Oradian middleware: " + e.getMessage(), 502, e);
        }
    }

    private WithdrawalResponse doSubmitWithdrawal(
            WithdrawalRequest request, String idempotencyKey, String ownerMsisdn) {
        try {
            WithdrawalResponse response = restClient.post()
                    .uri("/internal/transfers/withdraw")
                    .header("X-Internal-Token", internalToken)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .header(OWNER_MSISDN_HEADER, ownerMsisdn)
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(WithdrawalResponse.class);
            if (response == null) {
                throw new OradianMiddlewareTransientException(
                        "Oradian middleware returned an empty response body", 502);
            }
            log.info("Oradian withdrawal succeeded account={} amount={} txnID={}",
                    request.getAccountID(), request.getAmount(),
                    response.getTransactionID());
            return response;
        } catch (RestClientResponseException e) {
            String detail = parseErrorMessage(e.getResponseBodyAsString())
                    .orElse(e.getStatusText());
            int status = e.getStatusCode().value();
            log.warn("Oradian withdrawal failed account={} status={} detail={}",
                    request.getAccountID(), status, detail);
            throw classifyHttpFailure(
                    "Oradian middleware rejected the withdrawal: " + detail, status, e);
        } catch (OradianMiddlewareException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Oradian withdrawal errored account={} cause={}",
                    request.getAccountID(), e.toString());
            throw new OradianMiddlewareTransientException(
                    "Unable to reach Oradian middleware: " + e.getMessage(), 502, e);
        }
    }

    private List<DepositAccount> doGetDepositsForMsisdn(String msisdn) {
        try {
            List<DepositAccount> deposits = restClient.get()
                    .uri("/internal/customers/{msisdn}/deposits", msisdn)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<DepositAccount>>() {});
            return deposits == null ? Collections.emptyList() : deposits;
        } catch (RestClientResponseException e) {
            String detail = parseErrorMessage(e.getResponseBodyAsString())
                    .orElse(e.getStatusText());
            int status = e.getStatusCode().value();
            log.warn("Oradian deposits lookup failed msisdn={} status={} detail={}",
                    MsisdnMasking.mask(msisdn), status, detail);
            throw classifyHttpFailure(
                    "Oradian middleware rejected the deposits lookup: " + detail, status, e);
        } catch (OradianMiddlewareException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Oradian deposits lookup errored msisdn={} cause={}",
                    MsisdnMasking.mask(msisdn), e.toString());
            throw new OradianMiddlewareTransientException(
                    "Unable to reach Oradian middleware: " + e.getMessage(), 502, e);
        }
    }

    /**
     * Run the supplied upstream call inside the Retry + CircuitBreaker
     * decorators. Order matters: CircuitBreaker is the outer wrap so once
     * the breaker is OPEN the Retry doesn't burn its attempts hammering
     * a known-dead upstream. CallNotPermittedException (breaker rejected
     * the call before invoking the supplier) gets translated to a
     * 503-bearing transient exception so the caller sees a clear "upstream
     * temporarily unavailable" rather than a generic Resilience4j class.
     */
    private <T> T execute(Supplier<T> supplier) {
        // Compose inside-out: CircuitBreaker wraps the raw call, then
        // Retry wraps the CB. Effect: when the breaker is OPEN, Retry
        // sees CallNotPermittedException on every attempt and gives up
        // immediately (CallNotPermittedException isn't in retry-exceptions),
        // so we don't burn the customer's request time hammering a
        // known-dead upstream.
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
     *   * 5xx  -> transient (server-side problem, might recover on retry)
     *   * 4xx  -> permanent (validation, ownership, etc. — retrying won't help)
     */
    private static OradianMiddlewareException classifyHttpFailure(String message, int status, Throwable cause) {
        if (status >= 500 && status < 600) {
            return new OradianMiddlewareTransientException(message, status, cause);
        }
        return new OradianMiddlewareException(message, status, cause);
    }

    /**
     * Pulls a human-readable detail out of a ProblemDetail / ApiResult error
     * body. Tries `detail` first (ProblemDetail's standard field), then
     * `title`, then `message` (our ApiResult envelope). Returns empty when
     * the body isn't parseable JSON — the caller falls back to statusText.
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
