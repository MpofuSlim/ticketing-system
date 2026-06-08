package innbucks.paymentservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.config.CorrelationIdPropagatingInterceptor;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Calls innbucks-core-gateway's {@code POST /payments/debit} and
 * {@code POST /payments/{ref}/reverse} — the Boot-3 bridge that fronts veengu
 * for ticketing. Direct pod-to-pod over an explicit URL: the gateway lives on
 * the InnBucks-core stack, NOT in our Eureka registry, so this is the one
 * deliberately non-discovery client in payment-service (same pattern as
 * {@link OradianMiddlewareClient}).
 *
 * <p>Every outbound call is wrapped by Resilience4j Retry + CircuitBreaker via
 * the {@code innbucks-core-gateway} registry instances configured in
 * {@code application.yaml}. Retry fires only on
 * {@link InnbucksCoreGatewayTransientException} — 503 (gateway/veengu
 * unreachable), connectivity errors, empty response body. The gateway's
 * envelope-based contract means veengu's own {@code REJECTED_*} outcomes
 * (insufficient funds, account locked) come back as HTTP 200 with the
 * outcome in the body — those are deliberately NOT exceptions, so retry
 * doesn't fire on permanent rejections. See {@link InnbucksCoreGatewayResponse}.
 *
 * <p>Resilience4j composition: CircuitBreaker is the OUTER wrap, Retry is
 * INNER — so when the breaker is OPEN the Retry layer sees
 * {@link CallNotPermittedException} on every attempt and gives up immediately
 * (it isn't in the retry-exceptions list), instead of burning the customer's
 * request time hammering a known-dead gateway.
 */
@Component
@Slf4j
public class InnbucksCoreGatewayClient {

    private static final String RESILIENCE_INSTANCE_NAME = "innbucks-core-gateway";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public InnbucksCoreGatewayClient(
            @Value("${innbucks-core-gateway.base-url:http://localhost:8088}") String baseUrl,
            @Value("${innbucks-core-gateway.connect-timeout-ms:2000}") int connectMs,
            @Value("${innbucks-core-gateway.read-timeout-ms:15000}") int readMs,
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
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE_NAME);
    }

    /**
     * Submit a debit. {@code idempotencyKey} is forwarded as the standard
     * {@code Idempotency-Key} header; the gateway also uses
     * {@code request.paymentReference()} as veengu's caller-assigned reference
     * (the upstream idempotency key on veengu's side via {@code validateDuplicates}).
     * The pair must be stable across retries — the Resilience4j retry passes
     * the SAME values on every attempt by virtue of operating on the same
     * supplier.
     */
    public InnbucksCoreGatewayResponse debit(InnbucksCoreGatewayDebitRequest request, String idempotencyKey) {
        return execute(() -> doDebit(request, idempotencyKey));
    }

    private InnbucksCoreGatewayResponse doDebit(InnbucksCoreGatewayDebitRequest request, String idempotencyKey) {
        try {
            InnbucksCoreGatewayResponse response = restClient.post()
                    .uri("/payments/debit")
                    .header("Idempotency-Key", idempotencyKey)
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(InnbucksCoreGatewayResponse.class);
            if (response == null) {
                // Treat an empty body as an upstream contract violation —
                // retryable in case it's a brief glitch.
                throw new InnbucksCoreGatewayTransientException(
                        "innbucks-core-gateway returned an empty response body", 502);
            }
            log.info("innbucks-core-gateway debit submitted paymentReference={} outcome={} upstreamRef={}",
                    request.paymentReference(), response.outcome(), response.upstreamReference());
            return response;
        } catch (RestClientResponseException e) {
            String detail = parseErrorMessage(e.getResponseBodyAsString())
                    .orElse(e.getStatusText());
            int status = e.getStatusCode().value();
            log.warn("innbucks-core-gateway debit failed paymentReference={} status={} detail={}",
                    request.paymentReference(), status, detail);
            throw classifyHttpFailure(
                    "innbucks-core-gateway rejected the debit: " + detail, status, e);
        } catch (InnbucksCoreGatewayException e) {
            // Re-throw the empty-body classified case unchanged.
            throw e;
        } catch (Exception e) {
            log.warn("innbucks-core-gateway debit errored paymentReference={} cause={}",
                    request.paymentReference(), e.toString());
            throw new InnbucksCoreGatewayTransientException(
                    "Unable to reach innbucks-core-gateway: " + e.getMessage(), 502, e);
        }
    }

    /**
     * Run the supplied upstream call inside the Retry + CircuitBreaker
     * decorators. Order matters: CircuitBreaker is the OUTER wrap so once
     * the breaker is OPEN the Retry doesn't burn its attempts hammering a
     * known-dead upstream. {@link CallNotPermittedException} (breaker
     * rejected the call before invoking the supplier) gets translated to a
     * 503-bearing transient exception so the caller sees a clear "upstream
     * temporarily unavailable" rather than a generic Resilience4j class.
     */
    private <T> T execute(Supplier<T> supplier) {
        Supplier<T> withCb = CircuitBreaker.decorateSupplier(circuitBreaker, supplier);
        Supplier<T> decorated = Retry.decorateSupplier(retry, withCb);
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            log.warn("innbucks-core-gateway circuit breaker is OPEN — failing fast");
            throw new InnbucksCoreGatewayTransientException(
                    "innbucks-core-gateway is temporarily unavailable (circuit open)", 503, e);
        }
    }

    /**
     * Map an upstream HTTP status to the right exception class so Retry
     * fires only on truly transient failures:
     *   * 5xx (incl. the gateway's 503 for veengu-unreachable) -> transient
     *   * 4xx (request-shape errors) -> permanent
     *
     * <p>Veengu's own REJECTED_* outcomes do NOT reach this path — the
     * gateway returns them as HTTP 200 with the outcome in the body.
     */
    private static InnbucksCoreGatewayException classifyHttpFailure(String message, int status, Throwable cause) {
        if (status >= 500 && status < 600) {
            return new InnbucksCoreGatewayTransientException(message, status, cause);
        }
        return new InnbucksCoreGatewayException(message, status, cause);
    }

    /**
     * Pulls a human-readable detail out of the gateway's error envelope.
     * The gateway returns either a {@link InnbucksCoreGatewayResponse} (with
     * {@code error} field) on classified failures, or a generic map on
     * validation 400s. Tries {@code error} first, then {@code message},
     * then {@code detail}.
     */
    private Optional<String> parseErrorMessage(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {});
            Object error = parsed.get("error");
            if (error != null) return Optional.of(error.toString());
            Object message = parsed.get("message");
            if (message != null) return Optional.of(message.toString());
            Object detail = parsed.get("detail");
            return Optional.ofNullable(detail == null ? null : detail.toString());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
