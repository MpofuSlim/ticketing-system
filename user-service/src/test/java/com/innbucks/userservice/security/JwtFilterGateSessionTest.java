package com.innbucks.userservice.security;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.service.TokenRevocationService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A gate sign-in's session (V57) reaches nothing in user-service. Above all not
 * {@code /auth/devices}, which lists and removes the phones signed in to the
 * number's InnBucks app: an SMS code must never be enough for that.
 */
class JwtFilterGateSessionTest {

    private JwtUtil jwtUtil;
    private JwtFilter filter;
    private TokenRevocationService revocation;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-test-test-test-test-test-test-test");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3_600_000L);
        revocation = mock(TokenRevocationService.class);
        when(revocation.sessionState(anyString(), anyLong())).thenReturn(TokenRevocationService.SessionState.CURRENT);
        filter = new JwtFilter(jwtUtil, null, revocation, mock(CellAffinityChecker.class));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET, /auth/devices",
            "DELETE, /auth/devices/3f2b9c1e",
            "POST, /auth/device/step-up/verify",
            "GET, /notifications",
            "GET, /organizations",
            "GET, /users/me"
    })
    @DisplayName("a gate session is 403 gate_session_only on every path the filter reads, before any session read")
    void gateSession_isRefused(String method, String path) throws Exception {
        String token = jwtUtil.generateToken("+263771234567", GateSession.ROLE, 1, false);
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("\"errorCode\":\"gate_session_only\"");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, never()).doFilter(any(), any());
        verify(revocation, never()).sessionState(anyString(), anyLong());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"GATE_SCANNER", "gate_scanner", " Gate_Scanner "})
    @DisplayName("the role name is reserved in any spelling")
    void roleName_isReserved(String name) {
        assertThat(GateSession.isReservedName(name)).isTrue();
        assertThat(GateSession.isReservedName("GATE_STAFF")).isFalse();
    }
}
