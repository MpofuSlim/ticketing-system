package innbucks.paymentservice.security;

import innbucks.paymentservice.config.SecurityConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The access token is parsed and signature-verified EXACTLY ONCE per request.
 *
 * <p>{@link JwtFilter} used to re-parse and re-verify the token for every claim
 * it read: 5 verifications per authenticated {@code /payments/**} request (6
 * with a published token version). It now verifies once through
 * {@link JwtUtil#parseClaims}. Driven through the REAL Spring Security chain
 * ({@link SecurityConfig}) to a probe controller that echoes the principal the
 * filter set; {@link JwtUtil} is a Mockito spy, and every String-form reader
 * goes through {@code parseClaims} on it.
 */
@SpringJUnitWebConfig(JwtFilterParseOnceTest.Ctx.class)
class JwtFilterParseOnceTest {

    static final String SECRET = "test-secret-test-secret-test-secret-1234";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    @RestController
    static class Probe {
        @GetMapping("/payments/probe")
        String probe(Authentication auth) {
            return auth.getName();
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, Probe.class})
    static class Ctx {
        @Bean JwtUtil jwtUtil() { return Mockito.spy(new JwtUtil(SECRET)); }
        @Bean RevokedTokenDenylist revokedTokenDenylist() { return mock(RevokedTokenDenylist.class); }
        @Bean TokenVersionStore tokenVersionStore() { return mock(TokenVersionStore.class); }
        @Bean JwtFilter jwtFilter(JwtUtil u, RevokedTokenDenylist d, TokenVersionStore s) {
            return new JwtFilter(u, d, s);
        }
        @Bean MetricsScrapeAuthFilter metricsScrapeAuthFilter() { return new MetricsScrapeAuthFilter(""); }
    }

    @Autowired WebApplicationContext context;
    @Autowired JwtUtil jwtUtil;
    @Autowired RevokedTokenDenylist denylist;
    @Autowired TokenVersionStore versions;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        Mockito.reset(denylist, versions);
        clearInvocations(jwtUtil);
        when(denylist.isRevoked(anyString())).thenReturn(false);
    }

    private static String customerToken(UUID userUuid, long tokenVersion, String phone, String homeCountry) {
        var b = Jwts.builder()
                .subject("+263772000003")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .claim("userUuid", userUuid.toString())
                .claim("tokenVersion", tokenVersion)
                .claim("homeCountry", homeCountry)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000));
        if (phone != null) b.claim("phoneNumber", phone);
        return b.signWith(KEY, Jwts.SIG.HS256).compact();
    }

    @Test
    void aCustomerRequest_parsesTheTokenExactlyOnce() throws Exception {
        UUID user = UUID.randomUUID();
        when(versions.currentVersion(user.toString())).thenReturn(1L);
        String token = customerToken(user, 1L, "+263772000003", "ZW");

        mvc.perform(get("/payments/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("+263772000003"));

        verify(jwtUtil, times(1)).parseClaims(token);
        verify(jwtUtil, times(1)).parseClaims(anyString());
    }

    @Test
    void aTokenWithNoPhone_isStillInvalidToken_withOneParse() throws Exception {
        mvc.perform(get("/payments/probe").header("Authorization",
                        "Bearer " + customerToken(UUID.randomUUID(), 1L, null, "ZW")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(
                        "{\"code\":\"INVALID_TOKEN\",\"message\":\"Token has no phoneNumber claim\",\"data\":null}"));

        verify(jwtUtil, times(1)).parseClaims(anyString());
    }

    @Test
    void supersededToken_isStillRefused_withOneParse() throws Exception {
        UUID user = UUID.randomUUID();
        when(versions.currentVersion(user.toString())).thenReturn(5L);

        mvc.perform(get("/payments/probe").header("Authorization",
                        "Bearer " + customerToken(user, 4L, "+263772000003", "ZW")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(
                        "{\"code\":\"TOKEN_REVOKED\",\"message\":\"Token has been revoked\",\"data\":null}"));

        verify(jwtUtil, times(1)).parseClaims(anyString());
    }

    @Test
    void wrongCellToken_isStill409_withOneParse() throws Exception {
        mvc.perform(get("/payments/probe").header("Authorization",
                        "Bearer " + customerToken(UUID.randomUUID(), 1L, "+263772000003", "KE")))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"errorCode\":\"wrong_cell\"")));

        verify(jwtUtil, times(1)).parseClaims(anyString());
    }

    @Test
    void aBlankBearer_isStillInvalidToken() throws Exception {
        mvc.perform(get("/payments/probe").header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(
                        "{\"code\":\"INVALID_TOKEN\",\"message\":\"Token is invalid or expired\",\"data\":null}"));
    }

    /** The hook is real: each String-form reader is a parse, and is counted. */
    @Test
    void theCounterSeesEveryStringFormParse() {
        String token = customerToken(UUID.randomUUID(), 1L, "+263772000003", "ZW");
        jwtUtil.extractPhoneNumber(token);
        jwtUtil.extractUserUuid(token);
        jwtUtil.isTokenValid(token);
        verify(jwtUtil, times(3)).parseClaims(token);
    }
}
