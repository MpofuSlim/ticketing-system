package com.innbucks.userservice.security;

import com.innbucks.userservice.config.MfaProperties;
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

    private final SecretKey signingKey;
    private final MfaProperties properties;

    public MfaTokenService(@Value("${jwt.secret}") String jwtSecret, MfaProperties properties) {
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.properties = properties;
    }

    public String issue(Long userId, Purpose purpose) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getMfaTokenTtl())))
                .claim("purpose", purpose.name())
                .claim("kind", "mfa")
                .signWith(signingKey)
                .compact();
    }

    /**
     * Decode + verify signature, expiry, and purpose. Returns the user id; throws
     * {@link InvalidMfaTokenException} on anything that fails. Callers should
     * surface a clean 4xx (the FE has to restart the login).
     */
    public Long verify(String token, Purpose expected) {
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
            throw new InvalidMfaTokenException("mfaToken is invalid or expired");
        }
        if (!"mfa".equals(claims.get("kind", String.class))) {
            throw new InvalidMfaTokenException("Not an MFA token");
        }
        String actual = claims.get("purpose", String.class);
        if (actual == null || !actual.equals(expected.name())) {
            log.info("Wrong-purpose mfaToken expected={} actual={}", expected, actual);
            throw new InvalidMfaTokenException("mfaToken purpose mismatch");
        }
        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException ex) {
            throw new InvalidMfaTokenException("mfaToken subject is malformed");
        }
    }

    /** Thrown when an mfaToken fails verification. The controller maps it to 400. */
    public static class InvalidMfaTokenException extends RuntimeException {
        public InvalidMfaTokenException(String message) {
            super(message);
        }
    }
}
