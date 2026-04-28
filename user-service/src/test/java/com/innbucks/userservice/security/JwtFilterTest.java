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

import static org.junit.jupiter.api.Assertions.*;
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
        filter = new JwtFilter(jwtUtil, tokenRevocationService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validBearerToken_populatesSecurityContext() throws Exception {
        String token = jwtUtil.generateToken("user@example.com", "TENANT", 4, true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/agents/me");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("user@example.com", auth.getName());
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_TENANT")));
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
    void revokedToken_leavesContextEmpty_andStillChains() throws Exception {
        String token = jwtUtil.generateToken("u@example.com", "CUSTOMER", 2, false);
        when(tokenRevocationService.isRevoked(token)).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/agents/me");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(req, res);
    }

    @Test
    void invalidToken_leavesContextEmpty_andStillChains() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/agents/me");
        req.addHeader("Authorization", "Bearer garbage.token.here");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(req, res);
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
}
