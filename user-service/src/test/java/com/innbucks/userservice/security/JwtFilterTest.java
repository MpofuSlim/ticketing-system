package com.innbucks.userservice.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import com.innbucks.userservice.cells.CellAffinityChecker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JwtFilterTest {

    private JwtUtil jwtUtil;
    private JwtFilter filter;
    private com.innbucks.userservice.service.TokenRevocationService tokenRevocationService;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-test-test-test-test-test-test-test");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3_600_000L);
        tokenRevocationService = mock(com.innbucks.userservice.service.TokenRevocationService.class);
        // Default to "version is current" so tests that don't care about the
        // single-session gate don't have to stub it — the dedicated test
        // for SESSION_SUPERSEDED overrides this to false.
        when(tokenRevocationService.isTokenVersionCurrent(anyString(), anyLong())).thenReturn(true);
        // CellAffinityChecker no-op mock — these tests cover the auth / session
        // paths, not cell routing (CellAffinityCheckerTest does that). Default
        // doNothing() means every token looks like a local-cell token.
        CellAffinityChecker affinity = mock(CellAffinityChecker.class);
        filter = new JwtFilter(jwtUtil, tokenRevocationService, affinity);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validBearerToken_populatesSecurityContext() throws Exception {
        String token = jwtUtil.generateToken("user@example.com", "EVENT_ORGANIZER", 4, true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/agents/me");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("user@example.com", auth.getName());
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_EVENT_ORGANIZER")));
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("TIER_4")));
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("VERIFIED")));
        verify(chain).doFilter(req, res);
    }

    @Test
    void tier2Customer_grantsTier1AndTier2_notTier3() throws Exception {
        String token = jwtUtil.generateToken("c@example.com", "CUSTOMER", 2, false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/customers/me");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("TIER_1")));
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("TIER_2")));
        assertFalse(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("TIER_3")));
        assertFalse(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("VERIFIED")));
    }

    @Test
    void missingAuthHeader_leavesContextEmpty() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/agents/me");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(req, res);
    }

    @Test
    void revokedToken_returns401_andHaltsChain() throws Exception {
        // Hardened behaviour: a present-but-revoked Bearer token is rejected at
        // the filter with 401 TOKEN_REVOKED. The chain does NOT continue (old
        // code let it through unauthenticated, hiding the cause).
        String token = jwtUtil.generateToken("u@example.com", "CUSTOMER", 2, false);
        when(tokenRevocationService.isRevoked(token)).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/agents/me");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString().contains("TOKEN_REVOKED"));
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void invalidToken_returns401_andHaltsChain() throws Exception {
        // Hardened behaviour: tampered / malformed Bearer tokens get a clean
        // 401 INVALID_TOKEN immediately so clients know to refresh rather than
        // being told "you forgot a token" later in the chain.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/agents/me");
        req.addHeader("Authorization", "Bearer garbage.token.here");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString().contains("INVALID_TOKEN"));
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void shouldNotFilter_returnsTrueForExcludedPaths() {
        for (String path : new String[]{"/auth/login", "/auth/register",
                "/swagger-ui/index.html", "/v3/api-docs", "/error"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            assertTrue(filter.shouldNotFilter(req), "expected skip for " + path);
        }
    }

    @Test
    void shouldNotFilter_returnsFalseForBusinessPaths() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/agents/me");
        assertFalse(filter.shouldNotFilter(req));
    }

    @Test
    void staleTokenVersion_returns401SessionSuperseded_andHaltsChain() throws Exception {
        // Pin the single-active-session gate: a token whose tokenVersion claim
        // is behind the user's current users.token_version (because a newer
        // login bumped it) must be rejected at the filter with 401
        // SESSION_SUPERSEDED. Without this, a previously-logged-in device
        // would keep working in user-service after the customer logged in
        // on a new device.
        String token = jwtUtil.generateToken("u@example.com", "CUSTOMER", 2, false);
        when(tokenRevocationService.isTokenVersionCurrent(anyString(), anyLong())).thenReturn(false);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/agents/me");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString().contains("SESSION_SUPERSEDED"),
                "response must surface SESSION_SUPERSEDED so FE can distinguish from INVALID_TOKEN / TOKEN_REVOKED");
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void wrongCellJwt_writes409WithHomeCountryAndUrl_clearsSecurityContext() throws Exception {
        // Stub the affinity check to throw a typed WrongCellException with the
        // home URL set. Asserts the writer shape (FE-facing JSON contract) and
        // that the SecurityContext is cleared so a foreign JWT can never act
        // on this cell.
        var throwingAffinity = mock(com.innbucks.userservice.cells.CellAffinityChecker.class);
        doThrow(new com.innbucks.userservice.cells.WrongCellException("KE", "https://api-ke.innbucks.com"))
                .when(throwingAffinity).requireDomesticCountry(any());
        var spyFilter = new JwtFilter(jwtUtil, tokenRevocationService, throwingAffinity);

        String token = jwtUtil.generateToken("user@example.com", "CUSTOMER", 1, true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/customers/me");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        spyFilter.doFilterInternal(req, res, chain);

        assertEquals(409, res.getStatus());
        assertTrue(res.getContentAsString().contains("wrong_cell"));
        assertTrue(res.getContentAsString().contains("\"homeCountry\":\"KE\""));
        assertTrue(res.getContentAsString().contains("\"homeBaseUrl\":\"https://api-ke.innbucks.com\""));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain, never()).doFilter(req, res);
    }
}
