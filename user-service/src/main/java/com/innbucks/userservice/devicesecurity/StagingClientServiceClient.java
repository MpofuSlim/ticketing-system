package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.client.SingleFlightTokenCache;
import com.innbucks.userservice.client.SingleFlightTokenCache.CachedToken;
import com.innbucks.userservice.config.CorrelationIdPropagatingInterceptor;
import com.innbucks.userservice.config.PooledHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Staging's {@code POST /auth/client-service} — the step DTX now stands in front
 * of (contract §5.1). DTX is the only holder of this credential (§3 rule 2), so
 * the only way to a client-service token is through DTX's device checks.
 *
 * <p>Wire shape, as the contract transcribes it:
 * <pre>
 * POST {staging}/auth/client-service
 * X-Api-Key: &lt;staging api key&gt;
 * { "username": "...", "password": "..." }   →   { "accessToken": "…" }
 * </pre>
 *
 * <p><b>The token is cached and shared.</b> It is a company-level credential, not
 * a per-customer one, so DTX fetches it once and reuses it until shortly before
 * it expires (§5.1: "DTX may reuse the staging token server-side for as long as
 * staging allows"). The expiry is read from the token's own {@code exp} when it
 * is a JWT, else from an {@code expiresIn} field, else the configured fallback.
 *
 * <p>Never logs the token, the credential or the response body.
 */
@Slf4j
public class StagingClientServiceClient {

    static final String PATH = "/auth/client-service";
    private static final Pattern EXP = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)");

    /** A usable client-service token and the instant (UTC wall clock) it stops working. */
    public record ClientServiceToken(String accessToken, LocalDateTime expiresAt) {
        @Override
        public String toString() {
            return "ClientServiceToken[expiresAt=" + expiresAt + "]";
        }
    }

    /** Staging could not be reached or refused us. The app says "please try again shortly". */
    public static class StagingUnavailableException extends RuntimeException {
        public StagingUnavailableException(String message) {
            super(message);
        }

        public StagingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RestClient restClient;
    private final DeviceSecurityProperties.Staging config;
    private final Clock clock;
    /**
     * Single-flight, never {@code synchronized}: a slow staging login must not
     * block sign-ins that can use the current token (CLAUDE.md, "Upstream token
     * caches are single-flight, never synchronized").
     */
    private final SingleFlightTokenCache<ClientServiceToken> tokens;

    public StagingClientServiceClient(DeviceSecurityProperties.Staging config, Clock clock,
                                      PooledHttpClient pooledHttpClient) {
        this(buildRestClient(config, pooledHttpClient), config, clock);
    }

    StagingClientServiceClient(RestClient restClient, DeviceSecurityProperties.Staging config, Clock clock) {
        this.restClient = restClient;
        this.config = config;
        this.clock = clock;
        this.tokens = new SingleFlightTokenCache<>("Staging client-service", this::login,
                SingleFlightTokenCache.waitBound(config.getConnectTimeoutMs(), config.getReadTimeoutMs()),
                StagingUnavailableException::new, clock);
    }

    /**
     * The module's shared pool with this client's own timeouts, and the
     * User-Agent staging has always seen from us — it is a partner edge
     * (CLAUDE.md, "Outbound HTTP clients are pooled").
     */
    static RestClient buildRestClient(DeviceSecurityProperties.Staging config, PooledHttpClient pooledHttpClient) {
        return RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .requestFactory(pooledHttpClient.requestFactory(config.getConnectTimeoutMs(), config.getReadTimeoutMs()))
                .defaultHeader(HttpHeaders.USER_AGENT, PooledHttpClient.URL_CONNECTION_USER_AGENT)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
    }

    /** Whether the credential is present at all. Blank = the TOKEN decision cannot be honoured (503). */
    public boolean isConfigured() {
        return notBlank(config.getBaseUrl()) && notBlank(config.getApiKey())
                && notBlank(config.getUsername()) && notBlank(config.getPassword());
    }

    /**
     * A token with at least {@code refresh-margin} of life left — the cached one,
     * or a fresh one. One login is in flight at a time, so a burst of sign-ins at
     * expiry costs staging one call, not hundreds; and it is never run under a
     * lock, so a slow staging login holds up only the sign-ins that have no
     * usable token, each for at most connect + read timeout.
     */
    public ClientServiceToken token() {
        if (!isConfigured()) {
            throw new StagingUnavailableException("staging client-service credential is not provisioned");
        }
        return tokens.get();
    }

    /** Drops the cached token, e.g. after staging rejected it. */
    public void invalidate() {
        tokens.invalidate();
    }

    /**
     * The refresh starts at TWO margins before expiry and the token is handed
     * out until ONE margin before it. The second margin is the
     * stale-while-refreshing window: sign-ins that arrive while one of them
     * refreshes keep the current token, and none is ever handed a token with
     * less than {@code refresh-margin} of life left — the promise the margin
     * exists for (the app uses it after we return it).
     */
    private CachedToken<ClientServiceToken> login() {
        ClientServiceToken next = fetch();
        Instant expiresAt = next.expiresAt().toInstant(ZoneOffset.UTC);
        Duration margin = config.getRefreshMargin();
        return new CachedToken<>(next, expiresAt.minus(margin.multipliedBy(2)), expiresAt.minus(margin));
    }

    @SuppressWarnings("unchecked")
    private ClientServiceToken fetch() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", config.getUsername());
        body.put("password", config.getPassword());
        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri(PATH)
                    .header("X-Api-Key", config.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            log.error("Staging /auth/client-service refused DTX: HTTP {} (credential or api key wrong, or DTX not "
                    + "allow-listed)", e.getStatusCode().value());
            throw new StagingUnavailableException("staging refused the client-service call: HTTP "
                    + e.getStatusCode().value(), e);
        } catch (RuntimeException e) {
            log.warn("Staging /auth/client-service unreachable: {}", e.getClass().getSimpleName());
            throw new StagingUnavailableException("staging is unreachable", e);
        }
        Object token = response == null ? null : response.get("accessToken");
        if (!(token instanceof String accessToken) || accessToken.isBlank()) {
            // A 2xx without a token is infrastructure (a proxy page, a reshaped
            // envelope), never a token: refuse it rather than hand the app garbage.
            log.error("Staging /auth/client-service answered 2xx without an accessToken; keys={}",
                    response == null ? "none" : response.keySet());
            throw new StagingUnavailableException("staging answered without an accessToken");
        }
        LocalDateTime expiresAt = expiryOf(accessToken, response.get("expiresIn"));
        log.info("Staging client-service token refreshed; expiresAt={}Z", expiresAt);
        return new ClientServiceToken(accessToken, expiresAt);
    }

    /** exp from the JWT payload if it is one, else expiresIn (seconds), else the fallback TTL. */
    LocalDateTime expiryOf(String accessToken, Object expiresIn) {
        LocalDateTime now = LocalDateTime.now(clock);
        String[] parts = accessToken.split("\\.");
        if (parts.length == 3) {
            try {
                String payload = new String(Base64.getUrlDecoder().decode(pad(parts[1])), StandardCharsets.UTF_8);
                Matcher m = EXP.matcher(payload);
                if (m.find()) {
                    LocalDateTime exp = LocalDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(m.group(1))),
                            ZoneOffset.UTC);
                    if (exp.isAfter(now)) return exp;
                }
            } catch (IllegalArgumentException ignored) {
                // Not a JWT after all — fall through.
            }
        }
        if (expiresIn instanceof Number n && n.longValue() > 0) {
            return now.plusSeconds(n.longValue());
        }
        return now.plus(config.getFallbackTokenTtl());
    }

    private static String pad(String b64) {
        int rem = b64.length() % 4;
        return rem == 0 ? b64 : b64 + "=".repeat(4 - rem);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
