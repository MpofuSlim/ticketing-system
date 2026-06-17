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

    /**
     * Extract the customer's KYC tier from the {@code tier} claim. Returns
     * null when the token is invalid, when the claim is missing (staff
     * tokens — MERCHANT_ADMIN / SHOP_ADMIN — don't carry it), or when the
     * value can't be parsed as an integer. Callers that gate on
     * "tier &gt;= 2" should treat null as a rejection: tier-1 customers
     * have no Oradian record so they can't transfer, and staff tokens
     * shouldn't be hitting customer money endpoints in the first place.
     */
    public Integer extractTier(String token) {
        Claims claims = parseOrNull(token);
        if (claims == null) return null;
        return claims.get("tier", Integer.class);
    }

    /**
     * Extract the {@code homeCountry} claim (ISO 3166-1 alpha-2, e.g.
     * {@code ZW}) — the customer's MSISDN-derived routing key set by
     * user-service's JwtUtil at mint time. Returns null on any failure or
     * when the claim is absent (legacy tokens minted before the step-1
     * change; staff tokens with no MSISDN; customers whose phone prefix
     * isn't a known InnBucks market). JwtFilter pushes the value into MDC
     * for the request's lifetime so logs carry the customer's country
     * alongside the deployment country.
     */
    public String extractHomeCountry(String token) {
        Claims claims = parseOrNull(token);
        if (claims == null) return null;
        String home = claims.get("homeCountry", String.class);
        return (home == null || home.isBlank()) ? null : home;
    }

    /** True iff the token's signature, expiry, and structure are all valid. */
    public boolean isTokenValid(String token) {
        return parseOrNull(token) != null;
    }

    /**
     * True when the JWT carries the {@code mustChangePassword} claim. The
     * filter uses this to gate every authenticated request — a user who
     * hasn't rotated their temp password may not call any endpoint in this
     * service. Returns false for absent / unparseable claims.
     */
    public boolean extractMustChangePassword(String token) {
        Claims c = parseOrNull(token);
        if (c == null) return false;
        try {
            Boolean v = c.get("mustChangePassword", Boolean.class);
            return Boolean.TRUE.equals(v);
        } catch (Exception e) {
            return false;
        }
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
