package innbucks.paymentservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Verify-only JWT helper. payment-service does not mint tokens — user-service
 * is the sole issuer. This bean parses a bearer JWT signed with the shared
 * HS256 secret and exposes the claims payment-service cares about (today:
 * just the phoneNumber claim used for ownership checks on the public
 * /payments/transfer endpoint).
 *
 * <p>Keeps API surface minimal: no token generation, no role/tier extraction.
 * If a second authenticated endpoint lands here later, add only the claim
 * accessor it needs — don't port the full user-service JwtUtil.
 */
@Component
public class JwtUtil {

    private final SecretKey signingKey;

    public JwtUtil(@Value("${jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Extract the {@code phoneNumber} claim. Returns null on any failure
     * (bad signature, expired token, missing/blank claim, wrong type) — the
     * caller decides whether that's a 401 (no bearer / bad token) or a 400
     * (valid staff token with no phoneNumber claim).
     */
    public String extractPhoneNumber(String token) {
        Claims claims = parseOrNull(token);
        if (claims == null) return null;
        String phone = claims.get("phoneNumber", String.class);
        return (phone == null || phone.isBlank()) ? null : phone;
    }

    /** True iff the token's signature, expiry, and structure are all valid. */
    public boolean isTokenValid(String token) {
        return parseOrNull(token) != null;
    }

    private Claims parseOrNull(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            return null;
        }
    }
}
