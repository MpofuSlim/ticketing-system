package com.innbucks.seatservice.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    // OWASP A02 stage-1 RS256/JWKS migration (dual-verify). Optional RSA public
    // key: when set, this service verifies BOTH HS256 and RS256 tokens (selected
    // by the token's own `alg` header). When unset it behaves exactly as before
    // (HS256 only). This is a verifier — it never mints, so no private key here.
    @Value("${jwt.public-key:}")
    private String publicKeyPem;

    private PublicKey rsaPublicKey;
    private Locator<Key> keyLocator;
    private volatile boolean keysReady;

    // @PostConstruct validates config at boot under Spring; ensureKeyMaterial()
    // also runs lazily on first use so plain unit tests that `new JwtUtil()` +
    // set fields via reflection (no Spring lifecycle) still work. Idempotent.
    @PostConstruct
    void initKeyMaterial() {
        ensureKeyMaterial();
    }

    private void ensureKeyMaterial() {
        if (keysReady) {
            return;
        }
        synchronized (this) {
            if (keysReady) {
                return;
            }
            SecretKey hmacKey = getSigningKey();
            if (publicKeyPem != null && !publicKeyPem.isBlank()) {
                this.rsaPublicKey = parseRsaPublicKey(publicKeyPem);
            }
            this.keyLocator = (Header header) -> {
                if (header instanceof ProtectedHeader ph) {
                    String alg = ph.getAlgorithm();
                    if ("RS256".equals(alg) || "RS384".equals(alg) || "RS512".equals(alg)) {
                        if (rsaPublicKey == null) {
                            throw new io.jsonwebtoken.security.SignatureException(
                                    "RS-signed token presented but no jwt.public-key is configured");
                        }
                        return rsaPublicKey;
                    }
                }
                return hmacKey;
            };
            this.keysReady = true;
        }
    }

    private static PublicKey parseRsaPublicKey(String pem) {
        try {
            // A PEM kept on one line in an env file or a k8s Secret carries its line
            // breaks as literal two-character "\n" escapes. A backslash is not Base64, so
            // left in, it failed the boot; drop the escapes before the armour and whitespace.
            byte[] der = Base64.getDecoder().decode(
                    pem.replace("\\n", "").replace("\\r", "")
                            .replaceAll("-----BEGIN [^-]+-----", "")
                            .replaceAll("-----END [^-]+-----", "")
                            .replaceAll("\\s", ""));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid jwt.public-key (expected PKCS#8/X.509 PEM)", e);
        }
    }

    /** Fixed JWT issuer (iss) required on every token this service verifies.
     *  Minted by user-service; a token lacking it (or with a different value)
     *  is rejected even when the shared HS256 signature checks out. */
    public static final String TOKEN_ISSUER = "innbucks-ticketing";

    /** Fixed JWT audience (aud) required on every token this service verifies. */
    public static final String TOKEN_AUDIENCE = "innbucks-app";

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // --- Readers ------------------------------------------------------------
    // Every claim reader exists twice: once over an already-verified Claims
    // (what JwtFilter uses — it verifies the token ONCE per request through
    // parseClaims and reads every claim from that one result), and once over a
    // raw token (verifies again, for callers outside the filter). The String
    // form delegates to the Claims form, so the two can never disagree; each
    // keeps exactly the null / exception behaviour it always had.

    public String extractEmail(String token) {
        return extractEmail(getClaims(token));
    }

    public String extractEmail(Claims claims) {
        return claims.getSubject();
    }

    public List<String> extractRoles(String token) {
        return extractRoles(getClaims(token));
    }

    public List<String> extractRoles(Claims claims) {
        return stringList(claims.get("roles"));
    }

    public List<String> extractServices(String token) {
        return extractServices(getClaims(token));
    }

    public List<String> extractServices(Claims claims) {
        return stringList(claims.get("services"));
    }

    private static List<String> stringList(Object raw) {
        if (raw instanceof Collection<?> c) {
            return c.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    public String extractRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    public Integer extractTier(String token) {
        return extractTier(getClaims(token));
    }

    public Integer extractTier(Claims claims) {
        return claims.get("tier", Integer.class);
    }

    public Boolean extractVerified(String token) {
        return extractVerified(getClaims(token));
    }

    public Boolean extractVerified(Claims claims) {
        return claims.get("verified", Boolean.class);
    }

    public String extractPhoneNumber(String token) {
        return extractPhoneNumber(getClaims(token));
    }

    public String extractPhoneNumber(Claims claims) {
        return claims.get("phoneNumber", String.class);
    }

    public boolean isTokenValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    /**
     * True when the JWT carries the {@code mustChangePassword} claim. The
     * filter uses this to gate every authenticated request — a user who
     * hasn't rotated their temp password may not call any endpoint in this
     * service. Returns false for absent / unparseable claims.
     */
    public boolean extractMustChangePassword(String token) {
        try {
            return extractMustChangePassword(getClaims(token));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean extractMustChangePassword(Claims claims) {
        try {
            Boolean v = claims.get("mustChangePassword", Boolean.class);
            return Boolean.TRUE.equals(v);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Stable owning-organizer pointer (matches {@code users.user_uuid} in
     * user-service). Populated on EVENT_ORGANIZER and TEAM_MEMBER tokens;
     * null on customer tokens, legacy tokens, and any parse failure. The
     * seat-category ownership check compares this against each event's
     * {@code tenantUserUuid} now that email-as-tenantId is gone (event-service
     * V7 / PR #259).
     */
    public UUID extractOrganizerUuid(String token) {
        return extractUuidClaim(token, "organizerUuid");
    }

    public UUID extractOrganizerUuid(Claims claims) {
        return uuidClaim(claims, "organizerUuid");
    }

    /** Stable cross-service identifier of the caller (user_uuid). Null on
     *  legacy tokens minted before user-service's V20 or on any parse failure.
     *  Used to key the shared session-supersession lookup
     *  ({@code auth:tokenver:<userUuid>}). */
    public UUID extractUserUuid(String token) {
        return extractUuidClaim(token, "userUuid");
    }

    public UUID extractUserUuid(Claims claims) {
        return uuidClaim(claims, "userUuid");
    }

    private UUID extractUuidClaim(String token, String name) {
        try {
            return uuidClaim(getClaims(token), name);
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID uuidClaim(Claims claims, String name) {
        try {
            String raw = claims.get(name, String.class);
            return (raw == null || raw.isBlank()) ? null : UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Per-user session epoch from the {@code tokenVersion} claim (OWASP A07 /
     * CWE-613). {@link JwtFilter} compares it against the fleet-current value
     * published to shared Redis ({@code auth:tokenver:<userUuid>}) to reject
     * tokens superseded by a newer login / password change. Returns
     * {@code null} when the claim is absent or unparseable — a legacy token
     * without the claim carries no version to enforce, so the filter fails
     * open rather than 401ing it.
     */
    public Long extractTokenVersion(String token) {
        try {
            return extractTokenVersion(getClaims(token));
        } catch (Exception e) {
            return null;
        }
    }

    public Long extractTokenVersion(Claims claims) {
        try {
            Object raw = claims.get("tokenVersion");
            if (raw instanceof Number n) return n.longValue();
            if (raw == null) return null;
            return Long.parseLong(raw.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract the {@code homeCountry} claim (ISO 3166-1 alpha-2, e.g.
     * {@code ZW}) — the customer's MSISDN-derived routing key set by
     * user-service's JwtUtil at mint time. Returns null on any failure or
     * when the claim is absent (legacy tokens, staff tokens without an
     * MSISDN, customers whose phone prefix isn't a known InnBucks market).
     * JwtFilter pushes it into MDC for the request's lifetime.
     */
    public String extractHomeCountry(String token) {
        try {
            return extractHomeCountry(getClaims(token));
        } catch (Exception e) {
            return null;
        }
    }

    public String extractHomeCountry(Claims claims) {
        try {
            String home = claims.get("homeCountry", String.class);
            return (home == null || home.isBlank()) ? null : home;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Verifies the token — signature under the key its own {@code alg} header
     * selects ({@link #keyLocator}: RS* → the configured public key, else the
     * HS256 secret), {@code iss}, {@code aud} and expiry — and returns its
     * claims. Throws {@link JwtException} (or {@link IllegalArgumentException}
     * for a blank token) when any of that fails.
     *
     * <p>This is the ONE place a token is parsed. {@link JwtFilter} calls it
     * once per request and reads every claim from the result; the String
     * readers above call it each time they are used, so they are for callers
     * outside the filter only. {@code JwtFilterParseOnceTest} counts calls
     * here to pin "exactly once per request".
     */
    public Claims parseClaims(String token) {
        ensureKeyMaterial();
        return Jwts.parser()
                .keyLocator(keyLocator)
                .requireIssuer(TOKEN_ISSUER)
                .requireAudience(TOKEN_AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Claims getClaims(String token) {
        return parseClaims(token);
    }
}
