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
}
