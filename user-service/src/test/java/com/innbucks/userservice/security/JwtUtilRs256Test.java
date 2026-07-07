package com.innbucks.userservice.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the OWASP A02 stage-1 RS256/JWKS dual-verify contract on the sole minter
 * (user-service). The migration must be able to:
 *   1) keep signing + verifying HS256 with zero behaviour change (default),
 *   2) mint AND verify RS256 once RSA keys + jwt.signing-algorithm=RS256 are set,
 *   3) verify BOTH HS256 and RS256 during the mixed-fleet window (a verifier that
 *      holds the HS secret + the RS public key accepts either alg), and
 *   4) fail fast on RS256 signing misconfiguration.
 *
 * The JWT claims/wire format are identical across algs — only the signature
 * changes — so there is no FE impact; these tests also assert the claims still
 * round-trip under RS256.
 */
class JwtUtilRs256Test {

    private static final String HS_SECRET = "test-test-test-test-test-test-test-test";
    private static String publicPem;
    private static String privatePem;

    @BeforeAll
    static void generateRsaKeypair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        // getEncoded(): public = X.509 SubjectPublicKeyInfo, private = PKCS#8 —
        // exactly what JwtUtil's X509EncodedKeySpec / PKCS8EncodedKeySpec expect.
        publicPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(kp.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        privatePem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    /** Default: HS256 sign + verify, no RSA keys configured. */
    private JwtUtil hs256Util() {
        JwtUtil u = new JwtUtil();
        ReflectionTestUtils.setField(u, "secret", HS_SECRET);
        ReflectionTestUtils.setField(u, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(u, "refreshExpiration", 86_400_000L);
        return u;
    }

    /** Flips minting to RS256 (needs both keys); also self-verifies RS256. */
    private JwtUtil rs256SigningUtil() {
        JwtUtil u = hs256Util();
        ReflectionTestUtils.setField(u, "publicKeyPem", publicPem);
        ReflectionTestUtils.setField(u, "privateKeyPem", privatePem);
        ReflectionTestUtils.setField(u, "signingAlgorithm", "RS256");
        return u;
    }

    /** Mid-migration verifier: HS secret + RS public key, still signs HS256. */
    private JwtUtil dualVerifyUtil() {
        JwtUtil u = hs256Util();
        ReflectionTestUtils.setField(u, "publicKeyPem", publicPem);
        return u;
    }

    private static String algHeader(String jwt) {
        String headerJson = new String(
                Base64.getUrlDecoder().decode(jwt.substring(0, jwt.indexOf('.'))),
                StandardCharsets.UTF_8);
        // crude but sufficient: header is a tiny flat JSON object
        int i = headerJson.indexOf("\"alg\"");
        int c = headerJson.indexOf(':', i);
        int q1 = headerJson.indexOf('"', c);
        int q2 = headerJson.indexOf('"', q1 + 1);
        return headerJson.substring(q1 + 1, q2);
    }

    @Test
    void hs256_signAndVerify_unchangedByDefault() {
        JwtUtil u = hs256Util();
        String token = u.generateToken("user@example.com", "CUSTOMER", 2, true);
        assertEquals("HS256", algHeader(token), "default mint must still be HS256");
        assertTrue(u.isTokenValid(token));
        assertEquals("user@example.com", u.extractEmail(token));
    }

    @Test
    void rs256_mintAndVerify_roundTripsClaims() {
        JwtUtil u = rs256SigningUtil();
        String token = u.generateToken("organizer@example.com",
                java.util.List.of("EVENT_ORGANIZER"), java.util.List.of("ticketing"),
                4, true, "+263771234567", null, null, null, null, null, 9L);
        assertEquals("RS256", algHeader(token), "signing-algorithm=RS256 must mint RS256");
        assertTrue(u.isTokenValid(token), "the minter must verify its own RS256 token");
        assertEquals("organizer@example.com", u.extractEmail(token));
        assertTrue(u.extractRoles(token).contains("EVENT_ORGANIZER"));
        assertEquals(9L, u.extractTokenVersion(token));
    }

    @Test
    void dualVerify_acceptsBothHs256AndRs256() {
        String hsToken = hs256Util().generateToken("hs@example.com", "CUSTOMER", 1, true);
        String rsToken = rs256SigningUtil().generateToken("rs@example.com", "CUSTOMER", 1, true);

        JwtUtil verifier = dualVerifyUtil();
        assertTrue(verifier.isTokenValid(hsToken), "dual-verifier must accept HS256");
        assertTrue(verifier.isTokenValid(rsToken), "dual-verifier must accept RS256");
        assertEquals("hs@example.com", verifier.extractEmail(hsToken));
        assertEquals("rs@example.com", verifier.extractEmail(rsToken));
    }

    @Test
    void hsOnlyVerifier_rejectsRs256Token() {
        // A service NOT yet given the RS public key must reject RS-signed tokens
        // (not silently accept them) — this is why the public key has to be
        // deployed fleet-wide BEFORE the mint flip.
        String rsToken = rs256SigningUtil().generateToken("rs@example.com", "CUSTOMER", 1, true);
        assertFalse(hs256Util().isTokenValid(rsToken));
    }

    @Test
    void rs256Signing_withoutPrivateKey_failsFast() {
        JwtUtil u = hs256Util();
        ReflectionTestUtils.setField(u, "publicKeyPem", publicPem);
        ReflectionTestUtils.setField(u, "signingAlgorithm", "RS256"); // no private key
        assertThrows(IllegalStateException.class,
                () -> u.generateToken("x@example.com", "CUSTOMER", 1, true));
    }
}
