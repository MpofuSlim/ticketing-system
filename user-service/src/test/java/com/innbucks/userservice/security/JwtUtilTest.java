package com.innbucks.userservice.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-test-test-test-test-test-test-test");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 86_400_000L);
    }

    @Test
    void generateToken_embedsSubjectAndRoleClaim() {
        String token = jwtUtil.generateToken("user@example.com", "EVENT_ORGANIZER", 4, true);

        assertNotNull(token);
        assertEquals("user@example.com", jwtUtil.extractEmail(token));
        assertTrue(jwtUtil.extractRoles(token).contains("EVENT_ORGANIZER"));
        assertEquals(4, jwtUtil.extractTier(token));
        assertEquals(Boolean.TRUE, jwtUtil.extractVerified(token));
    }

    @Test
    void generateToken_embedsTierAndVerifiedForCustomer() {
        String token = jwtUtil.generateToken("c@example.com", "CUSTOMER", 2, false);

        assertEquals(2, jwtUtil.extractTier(token));
        assertEquals(Boolean.FALSE, jwtUtil.extractVerified(token));
    }

    @Test
    void isTokenValid_returnsTrueForTokenIssuedByUs() {
        String token = jwtUtil.generateToken("user@example.com", "CUSTOMER", 1, false);
        assertTrue(jwtUtil.isTokenValid(token));
    }

    @Test
    void isTokenValid_returnsFalseForGarbage() {
        assertFalse(jwtUtil.isTokenValid("not-a-jwt"));
    }

    @Test
    void isTokenValid_returnsFalseForTokenSignedWithDifferentSecret() {
        JwtUtil other = new JwtUtil();
        ReflectionTestUtils.setField(other, "secret", "different-secret-different-secret-xxx");
        ReflectionTestUtils.setField(other, "expiration", 3_600_000L);
        String foreignToken = other.generateToken("user@example.com", "EVENT_ORGANIZER", 4, true);

        assertFalse(jwtUtil.isTokenValid(foreignToken));
    }

    @Test
    void isTokenValid_returnsFalseForExpiredToken() {
        ReflectionTestUtils.setField(jwtUtil, "expiration", -1_000L);
        String expired = jwtUtil.generateToken("user@example.com", "EVENT_ORGANIZER", 4, true);

        assertFalse(jwtUtil.isTokenValid(expired));
    }

    @Test
    void generateRefreshToken_marksTypeAndExposesSubject() {
        String refresh = jwtUtil.generateRefreshToken("user@example.com");

        assertNotNull(refresh);
        assertTrue(jwtUtil.isTokenValid(refresh));
        assertTrue(jwtUtil.isRefreshToken(refresh));
        assertEquals(JwtUtil.TOKEN_TYPE_REFRESH, jwtUtil.extractType(refresh));
        assertEquals("user@example.com", jwtUtil.extractEmail(refresh));
        // Refresh tokens are claim-light — roles are not embedded.
        assertTrue(jwtUtil.extractRoles(refresh).isEmpty());
    }

    @Test
    void accessToken_isNotMarkedAsRefresh() {
        String access = jwtUtil.generateToken("user@example.com", "CUSTOMER", 2, false);
        assertFalse(jwtUtil.isRefreshToken(access));
        assertNull(jwtUtil.extractType(access));
    }

    @Test
    void extractTokenVersion_roundTripsThroughThe12ArgOverload() {
        String token = jwtUtil.generateToken("u@example.com", java.util.List.of("CUSTOMER"),
                java.util.List.of(), 2, true, "0777000000",
                null, null, null, null, null, 42L);

        assertEquals(42L, jwtUtil.extractTokenVersion(token),
                "the 12-arg generateToken must stamp tokenVersion onto the JWT so " +
                        "JwtFilter can compare it to users.token_version");
    }

    @Test
    void extractTokenVersion_returnsZeroForLegacyTokensThatDontCarryTheClaim() {
        // The shorter overloads default tokenVersion to 0 via the legacy
        // 11-arg adapter. JwtFilter then compares 0 to users.token_version
        // (column defaults to 0), so freshly-migrated users still pass the
        // first request — they're invalidated only on the next login.
        String legacy = jwtUtil.generateToken("u@example.com", "CUSTOMER", 2, true);
        assertEquals(0L, jwtUtil.extractTokenVersion(legacy));
    }

    @Test
    void generateToken_stampsHomeCountryFromMsisdn() {
        // Step 1 of the multi-cell roadmap: every token minted with a
        // recognised MSISDN carries an ISO 3166-1 alpha-2 routing key. ZW
        // for +263, KE for +254 — the eventual edge will read this to send
        // each customer to their home cell.
        String zw = jwtUtil.generateToken("zw@example.com", "CUSTOMER", 2, true, "+263782606983");
        assertEquals("ZW", jwtUtil.extractHomeCountry(zw));

        String ke = jwtUtil.generateToken("ke@example.com", "CUSTOMER", 2, true, "+254712345678");
        assertEquals("KE", jwtUtil.extractHomeCountry(ke));
    }

    @Test
    void generateToken_omitsHomeCountryWhenPhoneIsAbsent() {
        // Staff / admin tokens authenticate by email, not phone, so they
        // have no MSISDN to derive from. The claim is omitted (null) — the
        // resolver never invents a default cell.
        String staff = jwtUtil.generateToken("admin@example.com", "SUPER_ADMIN", 4, true);
        assertNull(jwtUtil.extractHomeCountry(staff));
    }

    @Test
    void generateToken_omitsHomeCountryForUnknownPrefix() {
        // A customer registered with a phone outside the InnBucks markets
        // (here +1, North America) must NOT get a guessed homeCountry —
        // routing them to a wrong cell would be worse than routing nowhere.
        String foreign = jwtUtil.generateToken("foreign@example.com", "CUSTOMER", 2, true, "+15551234567");
        assertNull(jwtUtil.extractHomeCountry(foreign));
    }

    @Test
    void generateToken_roundTripsUserUuidAndOrganizerUuid() {
        // The two stable cross-service claims downstream services rely on
        // for caller identity and team scoping. Encoded as strings on the
        // wire (UUID#toString); extract* reads them back as java.util.UUID.
        java.util.UUID userUuid = java.util.UUID.randomUUID();
        java.util.UUID organizerUuid = java.util.UUID.randomUUID();

        String token = jwtUtil.generateToken("organizer@example.com",
                java.util.List.of("EVENT_ORGANIZER"),
                java.util.List.of("ticketing"),
                4, true, "+263771234567",
                null, null, null, null, null, 7L, "Zimbabwe",
                userUuid, organizerUuid);

        assertEquals(userUuid, jwtUtil.extractUserUuid(token));
        assertEquals(organizerUuid, jwtUtil.extractOrganizerUuid(token));
    }

    @Test
    void extractUserUuid_returnsNullForTokensMintedByLegacyOverloads() {
        // Pre-V20 callers (tests, third-party scripts) using the shorter
        // generateToken overloads don't pass UUIDs. The extractor returns
        // null rather than throwing so downstream code that's been
        // migrated can branch on it cleanly.
        String legacy = jwtUtil.generateToken("u@example.com", "CUSTOMER", 2, true);
        assertNull(jwtUtil.extractUserUuid(legacy));
        assertNull(jwtUtil.extractOrganizerUuid(legacy));
    }
}
