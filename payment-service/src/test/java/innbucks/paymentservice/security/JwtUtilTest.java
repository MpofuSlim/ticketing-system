package innbucks.paymentservice.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private static final String SECRET = "test-test-test-test-test-test-test-test";

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static String mintToken(String secret, Map<String, ?> claims, long ttlMs) {
        return Jwts.builder()
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttlMs))
                .signWith(key(secret), Jwts.SIG.HS256)
                .compact();
    }

    @Test
    void extractPhoneNumber_returnsClaim_whenTokenIsValidAndSignedWithMatchingSecret() {
        JwtUtil util = new JwtUtil(SECRET);
        String token = mintToken(SECRET, Map.of("phoneNumber", "+263771234567"), 60_000);

        assertTrue(util.isTokenValid(token));
        assertEquals("+263771234567", util.extractPhoneNumber(token));
    }

    @Test
    void extractPhoneNumber_returnsNull_whenTokenIsSignedWithDifferentSecret() {
        JwtUtil util = new JwtUtil(SECRET);
        String token = mintToken("other-secret-other-secret-other-secret-x",
                Map.of("phoneNumber", "+263771234567"), 60_000);

        assertFalse(util.isTokenValid(token));
        assertNull(util.extractPhoneNumber(token));
    }

    @Test
    void extractPhoneNumber_returnsNull_whenTokenHasNoPhoneNumberClaim() {
        JwtUtil util = new JwtUtil(SECRET);
        String token = mintToken(SECRET, Map.of("roles", "MERCHANT_ADMIN"), 60_000);

        assertTrue(util.isTokenValid(token));
        assertNull(util.extractPhoneNumber(token));
    }

    @Test
    void extractPhoneNumber_returnsNull_whenTokenIsExpired() {
        JwtUtil util = new JwtUtil(SECRET);
        String token = mintToken(SECRET, Map.of("phoneNumber", "+263771234567"), -1_000);

        assertFalse(util.isTokenValid(token));
        assertNull(util.extractPhoneNumber(token));
    }

    @Test
    void extractPhoneNumber_returnsNull_whenTokenIsBlankOrMalformed() {
        JwtUtil util = new JwtUtil(SECRET);

        assertFalse(util.isTokenValid(null));
        assertFalse(util.isTokenValid(""));
        assertFalse(util.isTokenValid("not-a-jwt"));
        assertNull(util.extractPhoneNumber("not-a-jwt"));
    }

    @Test
    void extractPhoneNumber_returnsNull_whenClaimIsBlankString() {
        JwtUtil util = new JwtUtil(SECRET);
        String token = mintToken(SECRET, Map.of("phoneNumber", ""), 60_000);

        assertTrue(util.isTokenValid(token));
        assertNull(util.extractPhoneNumber(token));
    }
}
