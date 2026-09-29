package com.innbucks.userservice.security;

import com.innbucks.userservice.config.MfaProperties;
import com.innbucks.userservice.exception.AccountInactiveException;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.repository.UserTokenState;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues + verifies the short-lived JWT handed back by step 1 of an MFA login.
 *
 * <p>{@link Purpose#LOGIN_MFA} — minted when the password was correct but a
 * TOTP code is still needed; presented to {@code POST /auth/login/mfa}.
 * {@link Purpose#ENROLLMENT} — minted when 2FA is required but the user
 * doesn't have a secret yet; presented to {@code /auth/mfa/enroll/start} +
 * {@code .../complete}. The purpose claim is enforced on verify so a token
 * minted for one step can't be reused on the other.
 *
 * <p><b>Bound to the session epoch.</b> Every token carries a {@code tv} claim —
 * the account's {@code users.token_version} when it was issued — and
 * {@link #verify} refuses it unless that still equals the LIVE version, and
 * refuses any token of a deactivated account outright. Two consequences, both
 * load-bearing:
 * <ul>
 *   <li>Anything that ends sessions (a deactivation, a role change, a password
 *       reset, an admin MFA reset, a newer login) bumps the version and so also
 *       kills every pending MFA challenge. Before the claim, a token minted
 *       before a deactivation stayed good for its whole TTL, reusable across
 *       retries, and minted a session at whatever version was current.</li>
 *   <li>A successful verify bumps the version before minting
 *       ({@code AuthService.completeLoginWithMfa}), which SPENDS the token: it
 *       cannot be replayed to mint a second session.</li>
 * </ul>
 * A token with no {@code tv} (issued before this rule) is refused like a stale
 * one; the TTL is minutes, so that costs at most one re-login during rollout.
 *
 * <p>Reuses the platform {@code jwt.secret} HS256 key — these are short-lived
 * (5 min by default) and never leave the controller-to-controller round-trip,
 * so a separate signing key was deemed over-engineering. TTL bounded by
 * {@link MfaProperties#getMfaTokenTtl()}.
 */
@Component
@Slf4j
public class MfaTokenService {

    public enum Purpose {
        /** Password OK, waiting for the TOTP / backup code. */
        LOGIN_MFA,
        /** Password OK + 2FA required + no secret yet — the user must enrol now. */
        ENROLLMENT
    }

    /** Name of the session-epoch claim. */
    static final String TOKEN_VERSION_CLAIM = "tv";

    /** The existing invalid-token answer, reused verbatim for a stale or unbound token. */
    static final String INVALID_OR_EXPIRED = "mfaToken is invalid or expired";

    /** A verified token: whose it is, and the session epoch it is bound to. */
    public record Subject(Long userId, long tokenVersion) {
    }

    private final SecretKey signingKey;
    private final MfaProperties properties;
    private final UserRepository userRepository;

    public MfaTokenService(@Value("${jwt.secret}") String jwtSecret, MfaProperties properties,
                           UserRepository userRepository) {
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.properties = properties;
        this.userRepository = userRepository;
    }

    /**
     * Mint a token for {@code userId}, bound to the account's CURRENT
     * {@code token_version} as the database holds it — inside the caller's
     * transaction when there is one, so a bump earlier in the same login is
     * already visible.
     *
     * @throws AccountInactiveException when the account is deactivated
     * @throws InvalidMfaTokenException when the account does not exist
     */
    public String issue(Long userId, Purpose purpose) {
        UserTokenState state = liveState(userId);
        if (!state.isActive()) {
            throw new AccountInactiveException();
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getMfaTokenTtl())))
                .claim("purpose", purpose.name())
                .claim("kind", "mfa")
                .claim(TOKEN_VERSION_CLAIM, state.version())
                .signWith(signingKey)
                .compact();
    }

    /**
     * Decode + verify signature, expiry, purpose AND binding. Returns the user
     * id; throws {@link InvalidMfaTokenException} on a bad or stale token and
     * {@link AccountInactiveException} (401) when the account has been
     * deactivated — checked first, so a deactivation between the password step
     * and this one reads as what it is rather than as an expired token.
     */
    public Long verify(String token, Purpose expected) {
        return verifySubject(token, expected).userId();
    }

    /** {@link #verify}, also returning the epoch the token is bound to — needed to spend it. */
    public Subject verifySubject(String token, Purpose expected) {
        if (token == null || token.isBlank()) {
            throw new InvalidMfaTokenException("mfaToken is missing");
        }
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException ex) {
            throw new InvalidMfaTokenException(INVALID_OR_EXPIRED);
        }
        if (!"mfa".equals(claims.get("kind", String.class))) {
            throw new InvalidMfaTokenException("Not an MFA token");
        }
        String actual = claims.get("purpose", String.class);
        if (actual == null || !actual.equals(expected.name())) {
            log.info("Wrong-purpose mfaToken expected={} actual={}", expected, actual);
            throw new InvalidMfaTokenException("mfaToken purpose mismatch");
        }
        Long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException ex) {
            throw new InvalidMfaTokenException("mfaToken subject is malformed");
        }
        Number bound = claims.get(TOKEN_VERSION_CLAIM, Number.class);
        UserTokenState state = liveState(userId);
        if (!state.isActive()) {
            log.info("mfaToken presented for a deactivated account userId={}", userId);
            throw new AccountInactiveException();
        }
        if (bound == null || bound.longValue() != state.version()) {
            // Spent by a successful verify, or ended by a bump since issue
            // (deactivation, role change, password reset, admin MFA reset, a
            // newer login) — or minted before the claim existed.
            log.info("Stale mfaToken refused userId={} bound={} live={}", userId, bound, state.version());
            throw new InvalidMfaTokenException(INVALID_OR_EXPIRED);
        }
        return new Subject(userId, state.version());
    }

    private UserTokenState liveState(Long userId) {
        return userRepository.findTokenStateById(userId)
                .orElseThrow(() -> new InvalidMfaTokenException(INVALID_OR_EXPIRED));
    }

    /** Thrown when an mfaToken fails verification. The controller maps it to 400. */
    public static class InvalidMfaTokenException extends RuntimeException {
        public InvalidMfaTokenException(String message) {
            super(message);
        }
    }
}
