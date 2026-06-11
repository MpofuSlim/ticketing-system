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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client for the public <b>InnBucks Merchant API</b> — the 2D-code payment
 * rail the InnBucks team designated as the primary (and only) way ticketing
 * collects money. Spec pinned at {@code docs/api/innbucks-merchant-api.md}
 * (distilled from {@code docs/api/InnBucks_Merchant_Api_Doc_v1.0.9.pdf}).
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code POST /auth/third-party} — client login; Bearer token cached,
 *       refreshed 30s before expiry or on a 401 (single replay).</li>
 *   <li>{@code POST /api/code/generate} — issue a PAYMENT-type InnBucks code
 *       the customer approves in their own app/USSD. <b>Never retried</b>: a
 *       retry after an ambiguous failure could mint a SECOND live code while
 *       the first is still claimable, splitting our tracking from the code
 *       the customer might pay. A failed/timed-out generate moves no money,
 *       so the caller closes the ledger row FAILED and the customer simply
 *       taps pay again. Circuit breaker still applies.</li>
 *   <li>{@code POST /api/code/query/originalReference} — code status
 *       (New / Claimed / Paid / Expired / Timed Out), keyed by the
 *       {@code authNumber} from generation. Read-only, so retried on
 *       transients; the reconciler polls this until the code resolves.</li>
 * </ul>
 *
 * <p>Envelope conventions (per the doc): {@code responseCode} {@code 0} /
 * {@code "00"} is success, anything else is a failure explained by
 * {@code responseMsg} / {@code responseDescription}. <b>All amounts are in
 * CENTS</b> (minor units) — the one InnBucks-platform contract most likely
 * to cause a 100x incident, so {@code generatePaymentCode} takes a
 * {@code long amountCents} and the caller cross-checks the response's echo.
 *
 * <p>Env vars intentionally keep their {@code BANK_API_*} names from the
 * first integration round — same platform, same credentials, already in
 * deployment runbooks. Only the code-level naming moved to "merchant API".
 */
@Slf4j
@Component
public class InnbucksApiClient {

    private static final String RESILIENCE_INSTANCE_NAME = "innbucks-api";
    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final String LOGIN_PATH = "/auth/third-party";
    private static final String CODE_GENERATE_PATH = "/api/code/generate";
    private static final String CODE_QUERY_PATH = "/api/code/query/originalReference";

    private final InnbucksApiProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    private volatile String accessToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public InnbucksApiClient(InnbucksApiProperties properties,
                             ObjectMapper objectMapper,
                             RetryRegistry retryRegistry,
                             CircuitBreakerRegistry circuitBreakerRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
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

    // ------------------------------------------------------------- generate

    /**
     * Issue a PAYMENT-type InnBucks code for {@code amountCents}. Single
     * attempt (circuit breaker only — see class javadoc). A non-zero
     * {@code responseCode} comes back as {@code approved=false} with the
     * upstream reason; transport-level failures throw
     * {@link InnbucksApiTransientException}.
     */
    public CodeGenerationResult generatePaymentCode(String reference, String narration, long amountCents) {
        requireConfigured();
        Supplier<CodeGenerationResult> call =
                () -> withAuthRetryOn401(token -> doGenerate(reference, narration, amountCents, token));
        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker, call).get();
        } catch (CallNotPermittedException e) {
            throw new InnbucksApiTransientException(
                    "InnBucks API is temporarily unavailable (circuit open)", 503, e);
        }
    }

    private CodeGenerationResult doGenerate(String reference, String narration, long amountCents, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", reference);
        body.put("narration", narration);
        body.put("amount", amountCents);
        body.put("type", "PAYMENT");
        try {
            String raw = restClient.post()
                    .uri(CODE_GENERATE_PATH)
                    .header(API_KEY_HEADER, properties.getApiKey())
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            CodeGenerationResult result = classifyGeneration(raw);
            log.info("innbucks-api code generate reference={} approved={} authNumber={} responseCode={}",
                    reference, result.approved(), result.authNumber(), result.responseCode());
            return result;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401) {
                throw new UnauthorizedException();
            }
            if (status >= 500) {
                // Outcome ambiguous (a code MAY have been minted upstream) but
                // no money moves on generate and an undelivered code just
                // expires — the caller closes the row FAILED, never retries.
                throw new InnbucksApiTransientException(
                        "InnBucks API returned HTTP " + status + " on code generation", status, e);
            }
            // 4xx: the platform actively refused the request. Surface the
            // documented envelope's reason when the body carries one.
            CodeGenerationResult refused = classifyGeneration(e.getResponseBodyAsString());
            if (!refused.approved() && notBlank(refused.responseMsg())) {
                log.warn("innbucks-api refused code generation reference={} status={} responseCode={} msg={}",
                        reference, status, refused.responseCode(), refused.responseMsg());
                return refused;
            }
            log.error("innbucks-api rejected code generation reference={} status={} body={}",
                    reference, status, truncate(e.getResponseBodyAsString(), 300));
            throw new InnbucksApiException(
                    "InnBucks API rejected the code generation request: HTTP " + status, status, e);
        } catch (InnbucksApiException | UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("innbucks-api code generation errored reference={} cause={}", reference, e.toString());
            throw new InnbucksApiTransientException(
                    "Unable to reach the InnBucks API: " + e.getMessage(), 502, e);
        }
    }

    // ---------------------------------------------------------------- query

    /**
     * Code status by {@code originalReference} = the {@code authNumber} from
     * the generation response. Read-only — retried on transients. Anything
     * the platform reports that doesn't map onto a documented status comes
     * back {@code UNKNOWN}; the poller leaves such rows alone rather than
     * guessing (a wrong guess here is the double-charge path).
     */
    public CodeStatusResult queryCodeStatus(String originalReference) {
        requireConfigured();
        return executeIdempotent(() -> withAuthRetryOn401(token -> {
            Map<String, Object> body = new LinkedHashMap<>();
            // Per the doc the request reference is "from the source system";
            // unique per inquiry so InnBucks-side logs distinguish polls.
            body.put("reference", "Q-" + UUID.randomUUID());
            body.put("originalReference", originalReference);
            try {
                String raw = restClient.post()
                        .uri(CODE_QUERY_PATH)
                        .header(API_KEY_HEADER, properties.getApiKey())
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return classifyStatus(raw);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status == 401) throw new UnauthorizedException();
                if (status >= 500) throw new InnbucksApiTransientException(
                        "InnBucks API returned HTTP " + status + " on code query", status, e);
                // 4xx — refused query (unknown reference, validation). Surface
                // as ERROR so the poller counts it without mutating the row.
                CodeStatusResult refused = classifyStatus(e.getResponseBodyAsString());
                return new CodeStatusResult(CodeStatusResult.Status.ERROR, refused.rawStatus(),
                        refused.responseMsg() != null ? refused.responseMsg() : "HTTP " + status);
            } catch (InnbucksApiException | UnauthorizedException e) {
                throw e;
            } catch (Exception e) {
                throw new InnbucksApiTransientException(
                        "Unable to reach the InnBucks API: " + e.getMessage(), 502, e);
            }
        }));
    }

    // ------------------------------------------------------------------ auth

    /** Run an authed call; on 401, force one token refresh and replay once. */
    private <T> T withAuthRetryOn401(java.util.function.Function<String, T> call) {
        try {
            return call.apply(currentToken(false));
        } catch (UnauthorizedException first) {
            log.info("innbucks-api returned 401 — refreshing token and replaying once");
            try {
                return call.apply(currentToken(true));
            } catch (UnauthorizedException second) {
                throw new InnbucksApiException(
                        "InnBucks API rejected our credentials twice (401) — check BANK_API_USERNAME/PASSWORD/KEY", 401);
            }
        }
    }

    private synchronized String currentToken(boolean force) {
        if (!force && accessToken != null && Instant.now().isBefore(tokenExpiry)) {
            return accessToken;
        }
        try {
            String raw = restClient.post()
                    .uri(LOGIN_PATH)
                    .header(API_KEY_HEADER, properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(Map.of("username", properties.getUsername(),
                            "password", properties.getPassword()))
                    .retrieve()
                    .body(String.class);
            Map<String, Object> parsed = parseJson(raw);
            Object token = parsed.get("accessToken");
            if (token == null || token.toString().isBlank()) {
                throw new InnbucksApiException("InnBucks API login returned no accessToken", 502);
            }
            accessToken = token.toString();
            tokenExpiry = deriveExpiry(accessToken).minusSeconds(30);
            log.info("innbucks-api login succeeded; token cached until {}", tokenExpiry);
            return accessToken;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new InnbucksApiException(
                        "InnBucks API login rejected (HTTP " + status + ") — check BANK_API_USERNAME/PASSWORD/KEY", status, e);
            }
            throw new InnbucksApiTransientException("InnBucks API login failed: HTTP " + status, status, e);
        } catch (InnbucksApiException e) {
            throw e;
        } catch (Exception e) {
            throw new InnbucksApiTransientException(
                    "Unable to reach the InnBucks API for login: " + e.getMessage(), 502, e);
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
            throw new InnbucksApiTransientException(
                    "InnBucks API is temporarily unavailable (circuit open)", 503, e);
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new InnbucksApiException(
                    "InnBucks API is not configured — set BANK_API_URL/BANK_API_KEY/BANK_API_USERNAME/BANK_API_PASSWORD", 503);
        }
    }

    // ---------------------------------------------------------- classification

    private CodeGenerationResult classifyGeneration(String raw) {
        Map<String, Object> parsed = parseJson(raw);
        Integer responseCode = parseResponseCode(parsed.get("responseCode"));
        String responseMsg = firstString(parsed, "responseMsg", "responseDescription", "description");
        String code = asString(parsed.get("code"));
        String authNumber = asString(parsed.get("authNumber"));
        String stan = asString(parsed.get("stan"));
        Long amountEcho = parseCents(parsed.get("amount"));
        // Approved only when the platform says 0/00 AND we actually got the
        // two handles the rest of the flow depends on (code to show the
        // customer, authNumber to poll with).
        boolean approved = responseCode != null && responseCode == 0
                && notBlank(code) && notBlank(authNumber);
        if (responseCode != null && responseCode == 0 && !approved) {
            log.error("innbucks-api code generation said success but is missing code/authNumber — keys={}",
                    parsed.keySet());
        }
        return new CodeGenerationResult(approved, code, authNumber, stan, amountEcho,
                responseCode == null ? null : String.valueOf(responseCode), responseMsg);
    }

    private CodeStatusResult classifyStatus(String raw) {
        Map<String, Object> parsed = parseJson(raw);
        Integer responseCode = parseResponseCode(parsed.get("responseCode"));
        String responseMsg = firstString(parsed, "responseMsg", "responseDescription", "description");
        String rawStatus = asString(parsed.get("status"));
        if (responseCode == null || responseCode != 0) {
            return new CodeStatusResult(CodeStatusResult.Status.ERROR, rawStatus, responseMsg);
        }
        if (rawStatus == null) {
            return new CodeStatusResult(CodeStatusResult.Status.UNKNOWN, null, responseMsg);
        }
        // Doc vocabulary: New / Claimed / Paid / Expired / Timed Out.
        String normalised = rawStatus.replaceAll("[\\s_-]", "").toUpperCase(Locale.ROOT);
        CodeStatusResult.Status status = switch (normalised) {
            case "NEW", "PENDING" -> CodeStatusResult.Status.NEW;
            case "CLAIMED" -> CodeStatusResult.Status.CLAIMED;
            case "PAID" -> CodeStatusResult.Status.PAID;
            case "EXPIRED" -> CodeStatusResult.Status.EXPIRED;
            case "TIMEDOUT" -> CodeStatusResult.Status.TIMED_OUT;
            default -> CodeStatusResult.Status.UNKNOWN;
        };
        if (status == CodeStatusResult.Status.UNKNOWN) {
            log.warn("innbucks-api code query returned unrecognised status='{}' — treating as UNKNOWN", rawStatus);
        }
        return new CodeStatusResult(status, rawStatus, responseMsg);
    }

    /** responseCode arrives as number 0 or string "0"/"00"/"96" depending on the endpoint family. */
    private static Integer parseResponseCode(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Amounts in the merchant API are cents, often serialised as a string ("100"). */
    private static Long parseCents(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            String v = asString(map.get(key));
            if (notBlank(v)) return v;
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private Map<String, Object> parseJson(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
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

    /** Registers {@link InnbucksApiProperties} for binding. */
    @Configuration
    @EnableConfigurationProperties(InnbucksApiProperties.class)
    static class InnbucksApiPropertiesConfiguration { }
}
