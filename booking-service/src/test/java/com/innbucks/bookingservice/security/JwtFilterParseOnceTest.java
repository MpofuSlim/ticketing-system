package com.innbucks.bookingservice.security;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.config.SecurityConfig;
import com.innbucks.bookingservice.controller.BookingController;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.service.BookingService;
import com.innbucks.bookingservice.service.EventChangeNotificationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The access token is parsed and signature-verified EXACTLY ONCE per request.
 *
 * <p>{@link JwtFilter} used to call a {@code JwtUtil.extractX(token)} helper per
 * claim, and every one of them re-parsed and re-verified the token: 14 HMAC/RSA
 * verifications for one {@code POST /bookings} (15 when Redis held a published
 * token version). The filter now verifies once through
 * {@link JwtUtil#parseClaims} and reads every claim from that result; the
 * controller reads its claims (phone, display name) from {@link JwtAuthDetails}.
 *
 * <p>Driven through the REAL Spring Security chain ({@link SecurityConfig}) and
 * the real {@link BookingController}, over mocked services and no database.
 * {@link JwtUtil} is a Mockito spy: every String-form reader goes through
 * {@code parseClaims} on the spy, so counting {@code parseClaims} counts every
 * parse, whoever makes it — {@link #theCounterSeesEveryStringFormParse} proves
 * the hook would catch a regression.
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
    @Import({SecurityConfig.class, BookingController.class})
    static class Ctx {
        @Bean JwtUtil jwtUtil() { return Mockito.spy(new JwtUtil()); }
        @Bean RevokedTokenDenylist revokedTokenDenylist() { return mock(RevokedTokenDenylist.class); }
        @Bean TokenVersionStore tokenVersionStore() { return mock(TokenVersionStore.class); }
        @Bean JwtFilter jwtFilter(JwtUtil u, RevokedTokenDenylist d, TokenVersionStore s) {
            return new JwtFilter(u, d, s);
        }
        @Bean MetricsScrapeAuthFilter metricsScrapeAuthFilter() { return new MetricsScrapeAuthFilter(""); }
        @Bean BookingService bookingService() { return mock(BookingService.class); }
        @Bean UserServiceClient userServiceClient() { return mock(UserServiceClient.class); }
        @Bean EventChangeNotificationService eventChangeNotificationService() {
            return mock(EventChangeNotificationService.class);
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired JwtUtil jwtUtil;
    @Autowired RevokedTokenDenylist denylist;
    @Autowired TokenVersionStore versions;
    @Autowired BookingService bookingService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        Mockito.reset(denylist, versions, bookingService);
        clearInvocations(jwtUtil);
        when(denylist.isRevoked(anyString())).thenReturn(false);
    }

    private static String customerToken(UUID userUuid, long tokenVersion, String homeCountry) {
        return Jwts.builder()
                .subject("+263772000001")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .claim("roles", List.of("CUSTOMER"))
                .claim("services", List.of("ticketing"))
                .claim("tier", 2)
                .claim("verified", true)
                .claim("phoneNumber", "+263772000001")
                .claim("firstName", "Tendai")
                .claim("lastName", "Moyo")
                .claim("userUuid", userUuid.toString())
                .claim("tokenVersion", tokenVersion)
                .claim("homeCountry", homeCountry)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();
    }

    private static final String BOOKING = """
            {"eventId":"8d3a1f0e-2c4b-4e59-9a7d-1b2c3d4e5f60",
             "seats":[{"categoryId":"6f1e2d3c-4b5a-4968-8776-655443322110"}]}
            """;

    @Test
    void createBooking_withACustomerToken_parsesTheTokenExactlyOnce() throws Exception {
        UUID user = UUID.randomUUID();
        // A published version, so the supersession check reads tokenVersion too —
        // the worst case before this change (15 parses).
        when(versions.currentVersion(user.toString())).thenReturn(3L);
        String token = customerToken(user, 3L, "ZW");

        mvc.perform(post("/bookings").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(BOOKING))
                .andExpect(status().isCreated());

        verify(jwtUtil, times(1)).parseClaims(token);
        verify(jwtUtil, times(1)).parseClaims(anyString());

        // ...and the claims still reach the controller, from the one parse: the
        // JWT phone and the JWT display-name fallback (no name in the body).
        ArgumentCaptor<CreateBookingRequestDTO> req = ArgumentCaptor.forClass(CreateBookingRequestDTO.class);
        verify(bookingService).createBooking(eq("+263772000001"), eq("+263772000001"), req.capture());
        assertThat(req.getValue().getCustomerName()).isEqualTo("Tendai Moyo");
    }

    @Test
    void supersededToken_isStillRefused_withOneParse() throws Exception {
        UUID user = UUID.randomUUID();
        when(versions.currentVersion(user.toString())).thenReturn(4L);
        String token = customerToken(user, 3L, "ZW");

        mvc.perform(post("/bookings").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(BOOKING))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(
                        "{\"code\":\"TOKEN_REVOKED\",\"message\":\"Token has been revoked\",\"data\":null}"));

        verify(jwtUtil, times(1)).parseClaims(anyString());
        verify(bookingService, Mockito.never()).createBooking(any(), any(), any());
    }

    @Test
    void wrongCellToken_isStill409_withOneParse() throws Exception {
        UUID user = UUID.randomUUID();
        String token = customerToken(user, 1L, "KE");

        mvc.perform(post("/bookings").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(BOOKING))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"errorCode\":\"wrong_cell\"")));

        verify(jwtUtil, times(1)).parseClaims(anyString());
    }

    @Test
    void tamperedToken_isStillInvalidToken() throws Exception {
        String token = customerToken(UUID.randomUUID(), 1L, "ZW");
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        mvc.perform(post("/bookings").header("Authorization", "Bearer " + tampered)
                        .contentType("application/json").content(BOOKING))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(
                        "{\"code\":\"INVALID_TOKEN\",\"message\":\"Token is invalid or expired\",\"data\":null}"));

        verify(jwtUtil, times(1)).parseClaims(anyString());
    }

    /** The hook is real: each String-form reader is a parse, and is counted. */
    @Test
    void theCounterSeesEveryStringFormParse() {
        String token = customerToken(UUID.randomUUID(), 1L, "ZW");
        jwtUtil.extractEmail(token);
        jwtUtil.extractRoles(token);
        jwtUtil.extractUserUuid(token);
        jwtUtil.isTokenValid(token);
        verify(jwtUtil, times(4)).parseClaims(token);
    }
}
