package com.innbucks.seatservice.security;

import com.innbucks.seatservice.config.SecurityConfig;
import com.innbucks.seatservice.controller.SeatCategoryController;
import com.innbucks.seatservice.service.SeatCategoryAnalyticsService;
import com.innbucks.seatservice.service.SeatCategoryService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * it read: 11 verifications for an organizer's
 * {@code GET /seat-categories/analytics} (12 with a published token version).
 * It now verifies once through {@link JwtUtil#parseClaims}. Driven through the
 * REAL Spring Security chain ({@link SecurityConfig}, including
 * {@code @PreAuthorize}) and the real {@link SeatCategoryController}, over
 * mocked services; {@link JwtUtil} is a Mockito spy, and every String-form
 * reader goes through {@code parseClaims} on it.
 */
@SpringJUnitWebConfig(JwtFilterParseOnceTest.Ctx.class)
@TestPropertySource(properties = {
        "jwt.secret=" + JwtFilterParseOnceTest.SECRET,
        "innbucks.internal-api-token=test-internal-token",
        "innbucks.country=ZW"
})
class JwtFilterParseOnceTest {

    static final String SECRET = "test-secret-test-secret-test-secret-1234";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, SeatCategoryController.class})
    static class Ctx {
        @Bean JwtUtil jwtUtil() { return Mockito.spy(new JwtUtil()); }
        @Bean RevokedTokenDenylist revokedTokenDenylist() { return mock(RevokedTokenDenylist.class); }
        @Bean TokenVersionStore tokenVersionStore() { return mock(TokenVersionStore.class); }
        @Bean JwtFilter jwtFilter(JwtUtil u, RevokedTokenDenylist d, TokenVersionStore s) {
            return new JwtFilter(u, d, s);
        }
        @Bean MetricsScrapeAuthFilter metricsScrapeAuthFilter() { return new MetricsScrapeAuthFilter(""); }
        @Bean SeatCategoryService seatCategoryService() { return mock(SeatCategoryService.class); }
        @Bean SeatCategoryAnalyticsService seatCategoryAnalyticsService() {
            return mock(SeatCategoryAnalyticsService.class);
        }
        // The service mocks still get their @Autowired setters called, and
        // SeatCategoryService builds its read/write TransactionTemplates from
        // one (#684). Nothing here runs a transaction.
        @Bean PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired JwtUtil jwtUtil;
    @Autowired RevokedTokenDenylist denylist;
    @Autowired TokenVersionStore versions;
    @Autowired SeatCategoryAnalyticsService analytics;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        Mockito.reset(denylist, versions, analytics);
        clearInvocations(jwtUtil);
        when(denylist.isRevoked(anyString())).thenReturn(false);
    }

    private static String organizerToken(UUID userUuid, long tokenVersion, String homeCountry) {
        return Jwts.builder()
                .subject("organizer@example.com")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .claim("roles", List.of("EVENT_ORGANIZER"))
                .claim("services", List.of("ticketing"))
                .claim("tier", 4)
                .claim("verified", true)
                .claim("phoneNumber", "+263772000002")
                .claim("userUuid", userUuid.toString())
                .claim("organizerUuid", userUuid.toString())
                .claim("tokenVersion", tokenVersion)
                .claim("homeCountry", homeCountry)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();
    }

    @Test
    void analytics_asAnOrganizer_parsesTheTokenExactlyOnce() throws Exception {
        UUID organizer = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        when(versions.currentVersion(organizer.toString())).thenReturn(2L);
        String token = organizerToken(organizer, 2L, "ZW");

        mvc.perform(get("/seat-categories/analytics").param("eventId", event.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(jwtUtil, times(1)).parseClaims(token);
        verify(jwtUtil, times(1)).parseClaims(anyString());
        // The organizer scope still reaches the service — from the one parse.
        verify(analytics).getEventAnalytics(eq(event), eq(organizer), eq("organizer@example.com"),
                eq(false), anyInt(), anyInt(), eq("Bearer " + token));
    }

    @Test
    void supersededToken_isStillRefused_withOneParse() throws Exception {
        UUID organizer = UUID.randomUUID();
        when(versions.currentVersion(organizer.toString())).thenReturn(5L);

        mvc.perform(get("/seat-categories/analytics").param("eventId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + organizerToken(organizer, 4L, "ZW")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(
                        "{\"code\":\"TOKEN_REVOKED\",\"message\":\"Token has been revoked\",\"data\":null}"));

        verify(jwtUtil, times(1)).parseClaims(anyString());
    }

    @Test
    void wrongCellToken_isStill409_withOneParse() throws Exception {
        mvc.perform(get("/seat-categories/analytics").param("eventId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + organizerToken(UUID.randomUUID(), 1L, "KE")))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"errorCode\":\"wrong_cell\"")));

        verify(jwtUtil, times(1)).parseClaims(anyString());
    }

    /** The hook is real: each String-form reader is a parse, and is counted. */
    @Test
    void theCounterSeesEveryStringFormParse() {
        String token = organizerToken(UUID.randomUUID(), 1L, "ZW");
        jwtUtil.extractEmail(token);
        jwtUtil.extractOrganizerUuid(token);
        jwtUtil.isTokenValid(token);
        verify(jwtUtil, times(3)).parseClaims(token);
    }
}
