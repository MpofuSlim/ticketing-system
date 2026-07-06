package innbucks.paymentservice.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cross-service session-supersession store, read side (OWASP A07 / CWE-613).
 * user-service publishes the user's current {@code token_version} to the SHARED
 * Redis under {@code auth:tokenver:<userUuid> -> "<version>"} (a decimal string)
 * whenever a newer login / password change / account action supersedes older
 * sessions; this component lets {@link JwtFilter} reject a token whose
 * {@code tokenVersion} claim is now stale, rather than honouring it until the
 * short access-token TTL elapses. Mirrors {@link RevokedTokenDenylist}.
 *
 * <p>The key scheme MUST match the writer byte-for-byte:
 * {@code "auth:tokenver:" + userUuid} where {@code userUuid} is the canonical
 * {@code UUID.toString()} carried in the JWT's {@code userUuid} claim.
 *
 * <p><b>Fail-open</b>: this is defence-in-depth layered on top of the access
 * token's own short expiry — a Redis blip must not 401 every authenticated
 * request. So on a missing / blank userUuid, an absent key, an unreadable
 * value, or any Redis failure we log and return {@code null} ("no version to
 * enforce"), trusting the TTL as the backstop.
 */
@Component
@Slf4j
public class TokenVersionStore {

    /** Must match user-service's shared token-version key prefix. */
    private static final String SHARED_TOKEN_VERSION_PREFIX = "auth:tokenver:";

    private final StringRedisTemplate redis;

    public TokenVersionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return the user's current token_version from the shared store, or
     *         {@code null} when the userUuid is blank, no value is published,
     *         the value can't be parsed, or Redis is unreachable (fail-open).
     */
    public Long currentVersion(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(SHARED_TOKEN_VERSION_PREFIX + userUuid);
            return (raw == null || raw.isBlank()) ? null : Long.valueOf(raw.trim());
        } catch (RuntimeException ex) {
            // Fail open — see class javadoc. The access-token TTL is the backstop.
            log.warn("Shared token-version store lookup failed; allowing token through", ex);
            return null;
        }
    }
}
