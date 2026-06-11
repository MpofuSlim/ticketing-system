package innbucks.paymentservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.config.CorrelationIdPropagatingInterceptor;
import innbucks.paymentservice.util.MsisdnMasking;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Direct client for the public InnBucks Bank API — the integration that
 * replaced the innbucks-core-gateway s2s hop for ticket payments.
 *
 * <p>Operations (paths per the pinned Postman collection,
 * {@code docs/api/innbucks-bank-api.postman_collection.json}):
 * <ul>
 *   <li>{@code POST /auth/third-party} — client login; Bearer token cached,
 *       refreshed 30s before expiry or on a 401 (single replay).</li>
 *   <li>{@code GET /bank/api/account/msisdn/{msisdn}} — linked-account
 *       lookup: customer phone → wallet account number.</li>
 *   <li>{@code POST /bank/api/payment} — the debit. <b>Never retried</b>:
 *       it is not provably idempotent upstream, so a timeout surfaces as
 *       {@link BankApiTransientException} and the ledger row goes IN_DOUBT
 *       for the reconciler to resolve by inquiry. Circuit breaker still
 *       applies (fail fast when the bank is down).</li>
 *   <li>{@code GET /bank/api/transaction/inquiry} — resolve-by-query for
 *       IN_DOUBT/stale rows, keyed by our {@code participantReference}.</li>
 * </ul>
 *
 * <p>Response classification is deliberately TOLERANT: the collection ships
 * no response examples, so the classifier scans the (flattened) body for
 * conventional status markers and reference keys, and treats anything
 * unrecognisable as PROCESSING/UNKNOWN — never a guessed success or failure.
 * With the inquiry endpoint available, ambiguity is always resolvable.
 * The contract tests pin the assumed shapes; when real staging responses
 * are captured, adjust {@code classify*} + the tests together.
 *
 * <p>Resilience: CircuitBreaker (instance {@code bank-api}) wraps every call;
 * Retry wraps only the idempotent ones (login / lookup / inquiry).
 */
@Slf4j
@Component
public class BankApiClient {

    private static final String RESILIENCE_INSTANCE_NAME = "bank-api";
    private static final String API_KEY_HEADER = "X-Api-Key";

    /** Conventional "it worked" marker values seen across bank integrations. */
    private static final Set<String> SUCCESS_MARKERS = Set.of(
            "SUCCESS", "SUCCESSFUL", "COMPLETED", "APPROVED", "PROCESSED", "OK", "00", "000");
    private static final Set<String> FAILURE_MARKERS = Set.of(
            "FAILED", "FAILURE", "DECLINED", "REJECTED", "ERROR", "CANCELLED");
    private static final Set<String> NOT_FOUND_MARKERS = Set.of(
            "NOT_FOUND", "NOTFOUND", "NO_RECORD", "UNKNOWN_TRANSACTION");
    /** Keys whose value counts as a status marker (lowercased, dots stripped). */
    private static final Set<String> STATUS_KEYS = Set.of(
            "status", "transactionstatus", "responsecode", "code", "result", "state");
    /** Keys (in priority order) whose value is the bank's transaction reference. */
    private static final List<String> REFERENCE_KEYS = List.of(
            "transactionreference", "reference", "rrn", "retrievalreferencenumber",
            "originalreference", "transactionid", "bankreference");
    private static final List<String> MESSAGE_KEYS = List.of(
            "message", "description", "narrative", "responsemessage", "error", "reason");

    private final BankApiProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    private volatile String accessToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public BankApiClient(BankApiProperties properties,
                         ObjectMapper objectMapper,
                         RetryRegistry retryRegistry,
                         CircuitBreakerRegistry circuitBreakerRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        // JDK HttpClient request factory: the only Spring factory that can
        // send the GET-with-body the bank's transaction-inquiry endpoint uses.
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl() == null ? "http://localhost" : properties.getBaseUrl())
                .requestFactory(rf)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE_NAME);
    }

    /** True when base-url + all three credentials are present. */
    public boolean isConfigured() {
        return notBlank(properties.getBaseUrl()) && notBlank(properties.getApiKey())
                && notBlank(properties.getUsername()) && notBlank(properties.getPassword());
    }

    // ------------------------------------------------------------------ pay

    /**
     * Submit the debit. Single attempt (circuit breaker only — see class
     * javadoc): a transient failure means the outcome is unknown and the
     * caller must park the row IN_DOUBT, not resubmit.
     */
    public BankPaymentResult pay(BankPaymentCommand command) {
        requireConfigured();
        Supplier<BankPaymentResult> call = () -> withAuthRetryOn401(token -> doPay(command, token));
        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker, call).get();
        } catch (CallNotPermittedException e) {
            throw new BankApiTransientException(
                    "Bank API is temporarily unavailable (circuit open)", 503, e);
        }
    }

    private BankPaymentResult doPay(BankPaymentCommand command, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", command.amount());
        body.put("currency", command.currency());
        body.put("type", properties.getPaymentType());
        body.put("narration", command.narration());
        body.put("sourceAccount", command.sourceAccount());
        body.put("destinationAccount", command.destinationAccount());
        body.put("participantReference", command.participantReference());
        body.put("additionalData", Map.of());
        try {
            String raw = restClient.post()
                    .uri("/bank/api/payment")
                    .header(API_KEY_HEADER, properties.getApiKey())
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            BankPaymentResult result = classifyPayment(raw);
            log.info("bank-api payment submitted participantReference={} outcome={} bankRef={}",
                    command.participantReference(), result.outcome(), result.reference());
            return result;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            if (status == 401) {
                throw new UnauthorizedException();
            }
            if (status >= 500) {
                // Outcome unknown — the request may have been processed before
                // the failure. Transient => caller parks IN_DOUBT.
                throw new BankApiTransientException(
                        "Bank API returned HTTP " + status + " on payment", status, e);
            }
            // 4xx: the bank actively answered. A decline (insufficient funds
            // etc.) is a clean business outcome, not an exception; a
            // request-shape rejection is our bug.
            Optional<BankPaymentResult> decline = classifyDecline(status, responseBody);
            if (decline.isPresent()) {
                log.info("bank-api payment declined participantReference={} code={} message={}",
                        command.participantReference(), decline.get().code(), decline.get().message());
                return decline.get();
            }
            log.error("bank-api rejected payment request participantReference={} status={} body={}",
                    command.participantReference(), status, truncate(responseBody, 300));
            throw new BankApiException("Bank API rejected the payment request: HTTP " + status, status, e);
        } catch (BankApiException | UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("bank-api payment errored participantReference={} cause={}",
                    command.participantReference(), e.toString());
            throw new BankApiTransientException(
                    "Unable to reach the Bank API: " + e.getMessage(), 502, e);
        }
    }

    // -------------------------------------------------------- account lookup

    /**
     * Linked-account inquiry: customer MSISDN → wallet account number. The
     * bank expects the MSISDN without a leading '+' (per the collection's
     * example). Returns empty when the response carries no recognisable
     * account-number field.
     */
    public Optional<String> findWalletAccount(String msisdn) {
        requireConfigured();
        String normalised = msisdn.startsWith("+") ? msisdn.substring(1) : msisdn;
        return executeIdempotent(() -> withAuthRetryOn401(token -> {
            try {
                String raw = restClient.get()
                        .uri("/bank/api/account/msisdn/{msisdn}", normalised)
                        .header(API_KEY_HEADER, properties.getApiKey())
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .body(String.class);
                Optional<String> account = extractAccountNumber(raw);
                if (account.isEmpty()) {
                    log.warn("bank-api linked-account response had no recognisable account field msisdn={} keys={}",
                            MsisdnMasking.mask(msisdn), topLevelKeys(raw));
                }
                return account;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status == 401) throw new UnauthorizedException();
                if (status == 404) return Optional.<String>empty();
                if (status >= 500) throw new BankApiTransientException(
                        "Bank API returned HTTP " + status + " on account lookup", status, e);
                throw new BankApiException(
                        "Bank API rejected the account lookup: HTTP " + status, status, e);
            } catch (BankApiException | UnauthorizedException e) {
                throw e;
            } catch (Exception e) {
                throw new BankApiTransientException(
                        "Unable to reach the Bank API: " + e.getMessage(), 502, e);
            }
        }));
    }

    // ---------------------------------------------------------------- inquiry

    /**
     * Transaction inquiry by our {@code participantReference} (sent as
     * {@code originalParticipantReference}) — the reconciler's resolver for
     * IN_DOUBT / stale-PENDING rows. Conservative mapping: anything that
     * doesn't clearly classify comes back UNKNOWN and the row is left alone.
     */
    public BankInquiryResult inquireTransaction(String accountNumber, String originalParticipantReference) {
        requireConfigured();
        return executeIdempotent(() -> withAuthRetryOn401(token -> {
            Map<String, Object> body = new LinkedHashMap<>();
            if (notBlank(accountNumber)) {
                body.put("accountNumber", accountNumber);
            }
            body.put("participantReference", "INQ-" + UUID.randomUUID());
            body.put("originalParticipantReference", originalParticipantReference);
            try {
                String raw = restClient.method(HttpMethod.GET)
                        .uri("/bank/api/transaction/inquiry")
                        .header(API_KEY_HEADER, properties.getApiKey())
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return classifyInquiry(raw);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status == 401) throw new UnauthorizedException();
                if (status == 404) {
                    return new BankInquiryResult(BankInquiryResult.Outcome.NOT_FOUND, null, "404", null);
                }
                if (status >= 500) throw new BankApiTransientException(
                        "Bank API returned HTTP " + status + " on inquiry", status, e);
                throw new BankApiException("Bank API rejected the inquiry: HTTP " + status, status, e);
            } catch (BankApiException | UnauthorizedException e) {
                throw e;
            } catch (Exception e) {
                throw new BankApiTransientException(
                        "Unable to reach the Bank API: " + e.getMessage(), 502, e);
            }
        }));
    }

    // ------------------------------------------------------------------ auth

    /** Run an authed call; on 401, force one token refresh and replay once. */
    private <T> T withAuthRetryOn401(java.util.function.Function<String, T> call) {
        try {
            return call.apply(currentToken(false));
        } catch (UnauthorizedException first) {
            log.info("bank-api returned 401 — refreshing token and replaying once");
            try {
                return call.apply(currentToken(true));
            } catch (UnauthorizedException second) {
                throw new BankApiException(
                        "Bank API rejected our credentials twice (401) — check BANK_API_USERNAME/PASSWORD/KEY", 401);
            }
        }
    }

    private synchronized String currentToken(boolean force) {
        if (!force && accessToken != null && Instant.now().isBefore(tokenExpiry)) {
            return accessToken;
        }
        try {
            String raw = restClient.post()
                    .uri("/auth/third-party")
                    .header(API_KEY_HEADER, properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(Map.of("username", properties.getUsername(),
                            "password", properties.getPassword()))
                    .retrieve()
                    .body(String.class);
            Map<String, Object> parsed = parseJson(raw);
            Object token = parsed.get("accessToken");
            if (token == null || token.toString().isBlank()) {
                throw new BankApiException("Bank API login returned no accessToken", 502);
            }
            accessToken = token.toString();
            tokenExpiry = deriveExpiry(accessToken).minusSeconds(30);
            log.info("bank-api login succeeded; token cached until {}", tokenExpiry);
            return accessToken;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new BankApiException(
                        "Bank API login rejected (HTTP " + status + ") — check BANK_API_USERNAME/PASSWORD/KEY", status, e);
            }
            throw new BankApiTransientException("Bank API login failed: HTTP " + status, status, e);
        } catch (BankApiException e) {
            throw e;
        } catch (Exception e) {
            throw new BankApiTransientException(
                    "Unable to reach the Bank API for login: " + e.getMessage(), 502, e);
        }
    }

    /** Best-effort JWT exp parse; falls back to the configured token TTL. */
    private Instant deriveExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length >= 2) {
                String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                Map<String, Object> claims = parseJson(payload);
                Object exp = claims.get("exp");
                if (exp instanceof Number n) {
                    return Instant.ofEpochSecond(n.longValue());
                }
            }
        } catch (Exception ignored) {
            // Opaque token — fall through to TTL.
        }
        return Instant.now().plus(properties.getTokenTtl());
    }

    // ------------------------------------------------------------- resilience

    private <T> T executeIdempotent(Supplier<T> supplier) {
        Supplier<T> withCb = CircuitBreaker.decorateSupplier(circuitBreaker, supplier);
        Supplier<T> decorated = Retry.decorateSupplier(retry, withCb);
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            throw new BankApiTransientException(
                    "Bank API is temporarily unavailable (circuit open)", 503, e);
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new BankApiException(
                    "Bank API is not configured — set BANK_API_URL/BANK_API_KEY/BANK_API_USERNAME/BANK_API_PASSWORD", 503);
        }
    }

    // ---------------------------------------------------------- classification

    private BankPaymentResult classifyPayment(String raw) {
        Map<String, String> flat = flatten(parseJson(raw));
        String reference = firstValue(flat, REFERENCE_KEYS).orElse(null);
        String message = firstValue(flat, MESSAGE_KEYS).orElse(null);
        String marker = statusMarker(flat).orElse(null);

        if (marker != null && SUCCESS_MARKERS.contains(marker)) {
            return new BankPaymentResult(PaymentOutcome.COMPLETED, reference, marker, message);
        }
        if (containsInsufficientFunds(flat)) {
            return new BankPaymentResult(PaymentOutcome.REJECTED_INSUFFICIENT_FUNDS, reference,
                    marker != null ? marker : "DECLINED", message);
        }
        if (marker != null && FAILURE_MARKERS.contains(marker)) {
            return new BankPaymentResult(PaymentOutcome.REJECTED_OTHER, reference, marker, message);
        }
        // 2xx but unclassifiable: NEVER guess. PROCESSING leaves the row
        // PENDING; the reconciler resolves it by inquiry within a minute.
        log.warn("bank-api payment response unclassifiable — treating as PROCESSING; body keys={}",
                flat.keySet());
        return new BankPaymentResult(PaymentOutcome.PROCESSING, reference, marker, message);
    }

    private Optional<BankPaymentResult> classifyDecline(int status, String body) {
        Map<String, String> flat = flatten(parseJson(body));
        String reference = firstValue(flat, REFERENCE_KEYS).orElse(null);
        String message = firstValue(flat, MESSAGE_KEYS).orElse(null);
        String marker = statusMarker(flat).orElse(null);
        if (containsInsufficientFunds(flat)) {
            return Optional.of(new BankPaymentResult(PaymentOutcome.REJECTED_INSUFFICIENT_FUNDS,
                    reference, marker != null ? marker : String.valueOf(status), message));
        }
        if (marker != null && FAILURE_MARKERS.contains(marker)) {
            return Optional.of(new BankPaymentResult(PaymentOutcome.REJECTED_OTHER,
                    reference, marker, message));
        }
        return Optional.empty();
    }

    private BankInquiryResult classifyInquiry(String raw) {
        Map<String, String> flat = flatten(parseJson(raw));
        String reference = firstValue(flat, REFERENCE_KEYS).orElse(null);
        String message = firstValue(flat, MESSAGE_KEYS).orElse(null);
        String marker = statusMarker(flat).orElse(null);
        if (marker != null && SUCCESS_MARKERS.contains(marker)) {
            return new BankInquiryResult(BankInquiryResult.Outcome.COMPLETED, reference, marker, message);
        }
        if (marker != null && (FAILURE_MARKERS.contains(marker))) {
            return new BankInquiryResult(BankInquiryResult.Outcome.FAILED, reference, marker, message);
        }
        if ((marker != null && NOT_FOUND_MARKERS.contains(marker))
                || (message != null && message.toUpperCase(Locale.ROOT).contains("NOT FOUND"))) {
            return new BankInquiryResult(BankInquiryResult.Outcome.NOT_FOUND, reference, marker, message);
        }
        return new BankInquiryResult(BankInquiryResult.Outcome.UNKNOWN, reference, marker, message);
    }

    private Optional<String> extractAccountNumber(String raw) {
        Map<String, String> flat = flatten(parseJson(raw));
        return firstValue(flat, List.of("accountnumber", "walletaccountnumber", "account", "accountno"));
    }

    private Optional<String> statusMarker(Map<String, String> flat) {
        for (Map.Entry<String, String> e : flat.entrySet()) {
            String leafKey = leafOf(e.getKey());
            if (STATUS_KEYS.contains(leafKey) && notBlank(e.getValue())) {
                return Optional.of(e.getValue().trim().toUpperCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    private static boolean containsInsufficientFunds(Map<String, String> flat) {
        return flat.values().stream()
                .anyMatch(v -> v != null && v.toUpperCase(Locale.ROOT).contains("INSUFFICIENT"));
    }

    private static Optional<String> firstValue(Map<String, String> flat, List<String> keysInPriorityOrder) {
        for (String wanted : keysInPriorityOrder) {
            for (Map.Entry<String, String> e : flat.entrySet()) {
                if (leafOf(e.getKey()).equals(wanted) && notBlank(e.getValue())) {
                    return Optional.of(e.getValue());
                }
            }
        }
        return Optional.empty();
    }

    private static String leafOf(String flatKey) {
        int idx = flatKey.lastIndexOf('.');
        return idx < 0 ? flatKey : flatKey.substring(idx + 1);
    }

    private Map<String, Object> parseJson(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Lowercased dotted-key flatten, three levels deep, scalars only. */
    private static Map<String, String> flatten(Map<String, Object> map) {
        Map<String, String> out = new LinkedHashMap<>();
        flattenInto(out, "", map, 0);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flattenInto(Map<String, String> out, String prefix, Object node, int depth) {
        if (depth > 3 || node == null) return;
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = prefix.isEmpty()
                        ? e.getKey().toString().toLowerCase(Locale.ROOT)
                        : prefix + "." + e.getKey().toString().toLowerCase(Locale.ROOT);
                flattenInto(out, key, e.getValue(), depth + 1);
            }
        } else if (node instanceof List<?> list) {
            if (!list.isEmpty()) {
                flattenInto(out, prefix, list.get(0), depth + 1);
            }
        } else if (node instanceof BigDecimal || node instanceof Number
                || node instanceof String || node instanceof Boolean) {
            out.putIfAbsent(prefix, node.toString());
        }
    }

    private String topLevelKeys(String raw) {
        return String.join(",", parseJson(raw).keySet());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Internal signal: business call got a 401; refresh token and replay once. */
    private static final class UnauthorizedException extends RuntimeException { }

    /** Registers {@link BankApiProperties} for binding. */
    @Configuration
    @EnableConfigurationProperties(BankApiProperties.class)
    static class BankApiPropertiesConfiguration { }
}
