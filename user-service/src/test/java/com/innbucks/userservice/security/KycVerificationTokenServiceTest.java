package com.innbucks.userservice.security;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-unit coverage of the KYC-upgrade verification token: a forged, expired,
 * wrong-purpose, wrong-issuer/audience, or access-token-shaped token must be
 * rejected, and a freshly-issued one must round-trip back to its bound phone.
 * No Spring context — surgical to the token contract, runs in milliseconds.
 */
class KycVerificationTokenServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234";
    private static final String PHONE = "+263771234567";

    private final KycVerificationTokenService service = new KycVerificationTokenService(SECRET);

    private static String mint(String secret, String subject, String purpose,
                               String issuer, String audience, long ttlMs) {
        JwtBuilder b = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(subject)
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttlMs))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256);
        if (purpose != null) {
            b.claim("purpose", purpose);
        }
        return b.compact();
    }

    @Test
    void issueThenVerify_roundTripsToTheBoundPhone() {
        String token = service.issue(PHONE);

        VerifiedKycToken verified = service.verify(token);

        assertEquals(PHONE, verified.phoneNumber());
        assertNotNull(verified.jti());
        assertTrue(verified.expiresAt().isAfter(Instant.now()),
                "freshly-issued token must not already be expired");
    }

    @Test
    void verify_rejectsNullOrBlank() {
        assertEquals(KycVerificationTokenException.Reason.MISSING,
                assertThrows(KycVerificationTokenException.class, () -> service.verify(null)).getReason());
        assertEquals(KycVerificationTokenException.Reason.MISSING,
                assertThrows(KycVerificationTokenException.class, () -> service.verify("   ")).getReason());
    }

    @Test
    void verify_rejectsForgedSignature_signedWithDifferentSecret() {
        String forged = mint("a-totally-different-secret-aaaaaaaaaaaaaaa", PHONE,
                KycVerificationTokenService.PURPOSE, "innbucks-ticketing", "innbucks-app", 60_000);

        assertEquals(KycVerificationTokenException.Reason.INVALID,
                assertThrows(KycVerificationTokenException.class, () -> service.verify(forged)).getReason());
    }

    @Test
    void verify_rejectsExpiredToken() {
        String expired = mint(SECRET, PHONE, KycVerificationTokenService.PURPOSE,
                "innbucks-ticketing", "innbucks-app", -1_000);

        assertEquals(KycVerificationTokenException.Reason.EXPIRED,
                assertThrows(KycVerificationTokenException.class, () -> service.verify(expired)).getReason());
    }

    @Test
    void verify_rejectsWrongPurpose() {
        String wrongPurpose = mint(SECRET, PHONE, "password-reset",
                "innbucks-ticketing", "innbucks-app", 60_000);

        assertEquals(KycVerificationTokenException.Reason.PURPOSE_MISMATCH,
                assertThrows(KycVerificationTokenException.class, () -> service.verify(wrongPurpose)).getReason());
    }

    @Test
    void verify_rejectsAccessTokenShapedToken_withNoPurposeClaim() {
        // An access/refresh token shares the HS256 secret and carries the right
        // iss/aud — but no purpose claim. It must NOT be usable to drive a KYC
        // upgrade.
        String accessLike = mint(SECRET, PHONE, null,
                "innbucks-ticketing", "innbucks-app", 60_000);

        assertEquals(KycVerificationTokenException.Reason.PURPOSE_MISMATCH,
                assertThrows(KycVerificationTokenException.class, () -> service.verify(accessLike)).getReason());
    }

    @Test
    void verify_rejectsForeignIssuer() {
        String foreignIssuer = mint(SECRET, PHONE, KycVerificationTokenService.PURPOSE,
                "someone-else", "innbucks-app", 60_000);

        assertEquals(KycVerificationTokenException.Reason.INVALID,
                assertThrows(KycVerificationTokenException.class, () -> service.verify(foreignIssuer)).getReason());
    }

    @Test
    void verify_rejectsForeignAudience() {
        String foreignAudience = mint(SECRET, PHONE, KycVerificationTokenService.PURPOSE,
                "innbucks-ticketing", "some-other-app", 60_000);

        assertEquals(KycVerificationTokenException.Reason.INVALID,
                assertThrows(KycVerificationTokenException.class, () -> service.verify(foreignAudience)).getReason());
    }
}
