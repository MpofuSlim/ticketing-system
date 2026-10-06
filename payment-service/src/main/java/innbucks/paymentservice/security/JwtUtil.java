package innbucks.paymentservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.ProtectedHeader;
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
import java.util.UUID;

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

    /** Fixed JWT issuer (iss) required on every token this service verifies.
     *  Minted by user-service; a token lacking it (or with a different value)
     *  is rejected even when the shared HS256 signature checks out. */
    public static final String TOKEN_ISSUER = "innbucks-ticketing";

    /** Fixed JWT audience (aud) required on every token this service verifies. */
    public static final String TOKEN_AUDIENCE = "innbucks-app";

    private final SecretKey signingKey;

    public JwtUtil(@Value("${jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

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
    // also runs lazily on first use so plain unit tests that `new JwtUtil(secret)`
    // (no Spring lifecycle) still work. Idempotent.
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
            SecretKey hmacKey = signingKey;
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

    // Every claim reader exists twice: once over an already-verified Claims
    // (what JwtFilter uses — it verifies the token ONCE per request through
    // parseClaims and reads every claim from that one result), and once over a
    // raw token (verifies again, for callers outside the filter). The String
    // form delegates to the Claims form, so the two can never disagree.

    /**
     * Extract the {@code phoneNumber} claim. Returns null on any failure
     * (bad signature, expired token, missing/blank claim, wrong type) — the
     * caller decides whether that's a 401 (no bearer / bad token) or a 400
     * (valid staff token with no phoneNumber claim).
     */
    public String extractPhoneNumber(String token) {
        Claims claims = parseOrNull(token);
        if (claims == null) return null;
        return extractPhoneNumber(claims);
    }

    public String extractPhoneNumber(Claims claims) {
        String phone = claims.get("phoneNumber", String.class);
        return (phone == null || phone.isBlank()) ? null : phone;
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
        return extractHomeCountry(claims);
    }

    public String extractHomeCountry(Claims claims) {
        String home = claims.get("homeCountry", String.class);
        return (home == null || home.isBlank()) ? null : home;
    }

    /**
     * Stable cross-service identifier of the caller, read from the
     * {@code userUuid} claim. Returns null on any failure or when the claim is
     * absent (legacy tokens) or not a valid UUID. Used to key the shared
     * session-supersession lookup ({@code auth:tokenver:<userUuid>}).
     */
    public UUID extractUserUuid(String token) {
        Claims claims = parseOrNull(token);
        if (claims == null) return null;
        return extractUserUuid(claims);
    }

    public UUID extractUserUuid(Claims claims) {
        String raw = claims.get("userUuid", String.class);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Per-user session epoch from the {@code tokenVersion} claim (OWASP A07 /
     * CWE-613). {@link JwtFilter} compares it against the fleet-current value
     * published to shared Redis ({@code auth:tokenver:<userUuid>}) to reject
     * tokens superseded by a newer login / password change. Returns
     * {@code null} when the token is invalid, or the claim is absent or
     * unparseable — a legacy token without the claim carries no version to
     * enforce, so the filter fails open rather than 401ing it.
     */
    public Long extractTokenVersion(String token) {
        Claims claims = parseOrNull(token);
        if (claims == null) return null;
        return extractTokenVersion(claims);
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
        return extractMustChangePassword(c);
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
     * Verifies the token — signature under the key its own {@code alg} header
     * selects ({@link #keyLocator}: RS* → the configured public key, else the
     * HS256 secret), {@code iss}, {@code aud} and expiry — and returns its
     * claims. Throws {@link JwtException}, or {@link IllegalArgumentException}
     * for a null / blank token, when any of that fails.
     *
     * <p>This is the ONE place a token is parsed. {@link JwtFilter} calls it
     * once per request and reads every claim from the result; the String
     * readers above call it each time they are used, so they are for callers
     * outside the filter only. {@code JwtFilterParseOnceTest} counts calls
     * here to pin "exactly once per request".
     */
    public Claims parseClaims(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("blank token");
        }
        ensureKeyMaterial();
        return Jwts.parser()
                .keyLocator(keyLocator)
                .requireIssuer(TOKEN_ISSUER)
                .requireAudience(TOKEN_AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Claims parseOrNull(String token) {
        try {
            return parseClaims(token);
        } catch (JwtException | IllegalArgumentException ex) {
            return null;
        }
    }
}
