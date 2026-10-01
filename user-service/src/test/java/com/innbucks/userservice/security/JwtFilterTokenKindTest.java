package com.innbucks.userservice.security;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.service.TokenRevocationService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Only an ACCESS token is a login in {@link JwtFilter}. This service mints
 * refresh tokens and phone-scoped OTP tokens with the same key, issuer and
 * audience as access tokens, so both are signature-valid — and both used to
 * authenticate: a refresh token as a 7-day login on every filtered path, a
 * roles-empty OTP token on every path that only requires "logged in".
 *
 * <p>Minted with the REAL {@link JwtUtil} methods, so a change to either
 * token's shape that makes it pass the filter again fails here.
 */
class JwtFilterTokenKindTest {

    private JwtUtil jwtUtil;
    private JwtFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-test-test-test-test-test-test-test");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 604_800_000L);
        TokenRevocationService revocation = mock(TokenRevocationService.class);
        when(revocation.sessionState(anyString(), anyLong()))
                .thenReturn(TokenRevocationService.SessionState.CURRENT);
        filter = new JwtFilter(jwtUtil, null, revocation, mock(CellAffinityChecker.class));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse run(String token, FilterChain chain) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/organizations");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, chain);
        return res;
    }

    private void assertRejected(String token) throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = run(token, chain);
        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString().contains("INVALID_TOKEN"), res.getContentAsString());
        verify(chain, never()).doFilter(any(), any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void accessToken_isAuthenticated() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = run(jwtUtil.generateToken("u@example.com", "CUSTOMER", 1, false), chain);
        assertEquals(200, res.getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(any(), any());
    }

    @Test
    void refreshToken_isRejected() throws Exception {
        String refresh = jwtUtil.generateRefreshToken("u@example.com");
        assertTrue(jwtUtil.isTokenValid(refresh), "a refresh token is signature-valid — the type is what refuses it");
        assertRejected(refresh);
    }

    @Test
    void otpPhoneToken_isRejected() throws Exception {
        String otp = jwtUtil.generateScopedPhoneToken("+263771234567",
                LoyaltySessionTokenIssuer.LOYALTY_OTP_SCOPE, 60_000L);
        assertTrue(jwtUtil.isTokenValid(otp));
        assertRejected(otp);
    }

    @Test
    void isAccessToken_classifiesEachKind() {
        assertTrue(jwtUtil.isAccessToken(jwtUtil.generateToken("u@example.com", "CUSTOMER", 1, false)));
        assertFalse(jwtUtil.isAccessToken(jwtUtil.generateRefreshToken("u@example.com")));
        assertFalse(jwtUtil.isAccessToken(jwtUtil.generateScopedPhoneToken("+263771234567", "loyalty-otp", 60_000L)));
        assertFalse(jwtUtil.isAccessToken("not-a-jwt"));
    }
}
