package innbucks.paymentservice.security;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Only an ACCESS token is a login in {@link JwtFilter}. user-service signs
 * refresh, MFA and phone-scoped OTP/loyalty tokens with the same key, issuer
 * and audience, so each of those is signature-valid here — and each used to
 * authenticate: a refresh token as a 7-day login, a roles-empty OTP token on
 * every endpoint that only requires "logged in". All are now refused with the
 * same 401 {@code INVALID_TOKEN} as an expired token, and the chain stops.
 */
class JwtFilterTokenKindTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private JwtFilter filter;
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET);
        RevokedTokenDenylist denylist = mock(RevokedTokenDenylist.class);
        when(denylist.isRevoked(anyString())).thenReturn(false);
        filter = new JwtFilter(jwtUtil, denylist, mock(TokenVersionStore.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static String mint(Consumer<JwtBuilder> claims) {
        JwtBuilder builder = Jwts.builder()
                .subject("+263771234567")
                .claim("phoneNumber", "+263771234567")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000));
        claims.accept(builder);
        return builder.signWith(KEY, Jwts.SIG.HS256).compact();
    }

    private MockFilterChain invoke(String token, MockHttpServletResponse res) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/payments");
        req.addHeader("Authorization", "Bearer " + token);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return chain;
    }

    private void assertRejected(String token) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = invoke(token, res);
        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString().contains("INVALID_TOKEN"), res.getContentAsString());
        assertNull(chain.getRequest(), "the chain must not run for a non-access token");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void accessToken_withRoles_isAuthenticated() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = invoke(mint(b -> b.claim("roles", List.of("CUSTOMER"))), res);
        assertEquals(200, res.getStatus());
        assertNotNull(chain.getRequest(), "an access token proceeds down the chain");
    }

    @Test
    void accessToken_markedTypeAccess_isAuthenticated() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = invoke(mint(b -> b.claim("roles", List.of("CUSTOMER")).claim("type", "access")), res);
        assertEquals(200, res.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void refreshToken_isRejected() throws Exception {
        // Exactly what user-service's generateRefreshToken mints: no roles, type=refresh.
        assertRejected(mint(b -> b.claim("type", "refresh")));
    }

    @Test
    void refreshToken_isRejected_evenIfItCarriedRoles() throws Exception {
        // The type claim alone decides: a refresh token is never a login.
        assertRejected(mint(b -> b.claim("type", "refresh").claim("roles", List.of("SUPER_ADMIN"))));
    }

    @Test
    void mfaStepToken_isRejected() throws Exception {
        assertRejected(mint(b -> b.claim("kind", "mfa").claim("purpose", "LOGIN").claim("roles", List.of("CUSTOMER"))));
    }

    @Test
    void phoneScopedOtpToken_withEmptyRoles_isRejected() throws Exception {
        // user-service's OTP verify token and loyalty's session token: roles []
        // on purpose, so they are inert outside loyalty.
        assertRejected(mint(b -> b.claim("roles", List.of()).claim("services", List.of("loyalty-otp"))));
        assertRejected(mint(b -> b.claim("roles", List.of()).claim("services", List.of("loyalty-session"))));
    }

    @Test
    void tokenWithNoRolesClaim_isRejected() throws Exception {
        assertRejected(mint(b -> { }));
    }

    @Test
    void isAccessToken_isFalseForAnUnparseableToken() {
        assertFalse(jwtUtil.isAccessToken("not-a-jwt"));
    }
}
