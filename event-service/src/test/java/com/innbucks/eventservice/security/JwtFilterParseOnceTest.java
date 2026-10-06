package com.innbucks.eventservice.security;

import com.innbucks.eventservice.client.UserUuidLookupGateway;
import com.innbucks.eventservice.controller.EventController;
import com.innbucks.eventservice.service.EventService;
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
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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
 * <p>{@link JwtFilter} used to call a {@code JwtUtil.extractX(token)} helper per
 * claim, each re-parsing and re-verifying the token: 12 verifications for an
 * organizer's {@code GET /events/my} (13 with a published token version). It
 * now verifies once through {@link JwtUtil#parseClaims} and reads every claim
 * from that result; the controller reads {@code organizerUuid} from the
 * authentication's details.
 *
 * <p>Driven through the REAL Spring Security chain ({@link SecurityConfig},
 * including {@code @PreAuthorize}) and the real {@link EventController}, over a
 * mocked service and no database. {@link JwtUtil} is a Mockito spy, and every
 * String-form reader goes through {@code parseClaims} on it, so counting
 * {@code parseClaims} counts every parse.
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
    @Import({SecurityConfig.class, EventController.class})
    static class Ctx {
        @Bean JwtUtil jwtUtil() { return Mockito.spy(new JwtUtil()); }
        @Bean RevokedTokenDenylist revokedTokenDenylist() { return mock(RevokedTokenDenylist.class); }
        @Bean TokenVersionStore tokenVersionStore() { return mock(TokenVersionStore.class); }
        @Bean JwtFilter jwtFilter(JwtUtil u, RevokedTokenDenylist d, TokenVersionStore s) {
            return new JwtFilter(u, d, s);
        }
        @Bean MetricsScrapeAuthFilter metricsScrapeAuthFilter() { return new MetricsScrapeAuthFilter(""); }
        @Bean EventService eventService() { return mock(EventService.class); }
        @Bean UserUuidLookupGateway userUuidLookupGateway() { return mock(UserUuidLookupGateway.class); }
    }

    @Autowired WebApplicationContext context;
    @Autowired JwtUtil jwtUtil;
    @Autowired RevokedTokenDenylist denylist;
    @Autowired TokenVersionStore versions;
    @Autowired EventService eventService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        Mockito.reset(denylist, versions, eventService);
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
                .claim("country", "Zimbabwe")
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
    void myEvents_asAnOrganizer_parsesTheTokenExactlyOnce() throws Exception {
        UUID organizer = UUID.randomUUID();
        when(versions.currentVersion(organizer.toString())).thenReturn(2L);
        String token = organizerToken(organizer, 2L, "ZW");

        mvc.perform(get("/events/my").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(jwtUtil, times(1)).parseClaims(token);
        verify(jwtUtil, times(1)).parseClaims(anyString());
        // The organizer scope still reaches the service — from the one parse.
        verify(eventService).getMyEvents(eq(organizer), any(), any(), any(), anyInt(), anyInt(), anyString());
    }

    @Test
    void customerToken_isStillForbidden_byPreAuthorize_withOneParse() throws Exception {
        String token = Jwts.builder()
                .subject("+263772000001")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .claim("roles", List.of("CUSTOMER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();

        mvc.perform(get("/events/my").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        verify(jwtUtil, times(1)).parseClaims(anyString());
    }

    @Test
    void supersededToken_isStillRefused_withOneParse() throws Exception {
        UUID organizer = UUID.randomUUID();
        when(versions.currentVersion(organizer.toString())).thenReturn(5L);

        mvc.perform(get("/events/my").header("Authorization", "Bearer " + organizerToken(organizer, 4L, "ZW")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(
                        "{\"code\":\"TOKEN_REVOKED\",\"message\":\"Token has been revoked\",\"data\":null}"));

        verify(jwtUtil, times(1)).parseClaims(anyString());
    }

    @Test
    void wrongCellToken_isStill409_withOneParse() throws Exception {
        mvc.perform(get("/events/my").header("Authorization",
                        "Bearer " + organizerToken(UUID.randomUUID(), 1L, "KE")))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"errorCode\":\"wrong_cell\"")));

        verify(jwtUtil, times(1)).parseClaims(anyString());
    }

    @Test
    void expiredToken_isStillInvalidToken() throws Exception {
        String expired = Jwts.builder()
                .subject("organizer@example.com")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();

        mvc.perform(get("/events/my").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(
                        "{\"code\":\"INVALID_TOKEN\",\"message\":\"Token is invalid or expired\",\"data\":null}"));

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
