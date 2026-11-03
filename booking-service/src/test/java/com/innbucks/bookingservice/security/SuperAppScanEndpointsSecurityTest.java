package com.innbucks.bookingservice.security;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.config.MarketTimeZone;
import com.innbucks.bookingservice.config.SecurityConfig;
import com.innbucks.bookingservice.controller.ScanReportController;
import com.innbucks.bookingservice.controller.TicketScanController;
import com.innbucks.bookingservice.dto.GateLookupResponseDTO;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
import com.innbucks.bookingservice.dto.scan.ScannerStatsDTO;
import com.innbucks.bookingservice.service.GateLookupService;
import com.innbucks.bookingservice.service.ScanReportService;
import com.innbucks.bookingservice.service.TicketScanService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * A super-app session (CUSTOMER only, the {@code /auth/exchange} token shape)
 * reaches the scan, the gate lookup and the scanner's own reports, through the
 * REAL security chain with a REAL signed token: an injected Authentication
 * would hide a role the method security does not admit. A role outside the
 * three is still refused, and the scan hands the request's {@code eventId} to
 * the service.
 */
@SpringJUnitWebConfig(SuperAppScanEndpointsSecurityTest.Ctx.class)
@TestPropertySource(properties = {
        "jwt.secret=" + SuperAppScanEndpointsSecurityTest.SECRET,
        "innbucks.internal-api-token=test-internal-token",
        "innbucks.country=ZW"
})
class SuperAppScanEndpointsSecurityTest {

    static final String SECRET = "test-secret-test-secret-test-secret-1234";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, TicketScanController.class, ScanReportController.class,
            com.innbucks.bookingservice.exception.GlobalExceptionHandler.class})
    static class Ctx {
        @Bean JwtUtil jwtUtil() { return new JwtUtil(); }
        @Bean RevokedTokenDenylist revokedTokenDenylist() { return mock(RevokedTokenDenylist.class); }
        @Bean TokenVersionStore tokenVersionStore() { return mock(TokenVersionStore.class); }
        @Bean JwtFilter jwtFilter(JwtUtil u, RevokedTokenDenylist d, TokenVersionStore s) {
            return new JwtFilter(u, d, s);
        }
        @Bean MetricsScrapeAuthFilter metricsScrapeAuthFilter() { return new MetricsScrapeAuthFilter(""); }
        // The mocked services keep their @Autowired transaction-manager setter.
        @Bean org.springframework.transaction.PlatformTransactionManager transactionManager() {
            return mock(org.springframework.transaction.PlatformTransactionManager.class);
        }
        @Bean TicketScanService ticketScanService() { return mock(TicketScanService.class); }
        @Bean GateLookupService gateLookupService() { return mock(GateLookupService.class); }
        @Bean ScanReportService scanReportService() { return mock(ScanReportService.class); }
        @Bean MarketTimeZone marketTimeZone() { return new MarketTimeZone("ZW"); }
        @Bean @SuppressWarnings("unchecked")
        ObjectProvider<EventServiceClient> eventClientProvider() { return mock(ObjectProvider.class); }
    }

    @Autowired WebApplicationContext context;
    @Autowired RevokedTokenDenylist denylist;
    @Autowired TicketScanService scanService;
    @Autowired GateLookupService lookupService;
    @Autowired ScanReportService reportService;

    private final UUID userUuid = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        Mockito.reset(scanService, lookupService, reportService);
        when(denylist.isRevoked(anyString())).thenReturn(false);
    }

    private String token(String... roles) {
        return Jwts.builder()
                .subject("+263772000111")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .claim("roles", List.of(roles))
                .claim("phoneNumber", "+263772000111")
                .claim("userUuid", userUuid.toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();
    }

    @Test
    void scan_withASuperAppToken_reachesTheServiceWithTheEvent() throws Exception {
        when(scanService.scan(eq("20261030-7KQ4M-9XZ2B"), eq(eventId), any()))
                .thenReturn(ScanTicketResponseDTO.builder().status(ScanTicketResponseDTO.Status.ALLOWED).build());

        mvc.perform(post("/tickets/scan")
                        .header("Authorization", "Bearer " + token("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketNumber\":\"20261030-7KQ4M-9XZ2B\",\"eventId\":\"" + eventId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ALLOWED"));
    }

    @Test
    void lookup_withASuperAppToken_reachesTheServiceWithTheEvent() throws Exception {
        when(lookupService.lookup("INN-20261030-3C8849", eventId))
                .thenReturn(GateLookupResponseDTO.builder().status(GateLookupResponseDTO.Status.WRONG_EVENT).build());

        mvc.perform(post("/tickets/lookup")
                        .header("Authorization", "Bearer " + token("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmationNumber\":\"INN-20261030-3C8849\",\"eventId\":\"" + eventId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WRONG_EVENT"));
    }

    @Test
    void myStats_forOneEvent_withASuperAppToken_sendsNoEmailOrName_andNeedsNoWindow() throws Exception {
        when(reportService.myStatsAtEvent(eq(userUuid), isNull(), isNull(), eq(eventId), isNull(), isNull()))
                .thenReturn(new ScannerStatsDTO(userUuid, null, "Rudo Sibanda", null, null, 3,
                        Map.of("ALLOWED", 3L), eventId));

        mvc.perform(get("/scans/me/stats").param("eventId", eventId.toString())
                        .header("Authorization", "Bearer " + token("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scannerDisplayName").value("Rudo Sibanda"))
                .andExpect(jsonPath("$.data.eventId").value(eventId.toString()));
    }

    @Test
    void myScans_forOneEvent_withASuperAppToken_reachesTheService() throws Exception {
        mvc.perform(get("/scans/me").param("eventId", eventId.toString())
                        .header("Authorization", "Bearer " + token("CUSTOMER")))
                .andExpect(status().isOk());
        verify(reportService).listMyScansAtEvent(eq(userUuid), eq(eventId), isNull(), isNull(), eq(0), eq(20));
    }

    @Test
    void myScans_toWithoutFrom_forOneEvent_is400() throws Exception {
        mvc.perform(get("/scans/me").param("eventId", eventId.toString()).param("to", "2026-10-30T00:00:00Z")
                        .header("Authorization", "Bearer " + token("CUSTOMER")))
                .andExpect(status().isBadRequest());
        verify(reportService, never()).listMyScansAtEvent(any(), any(), any(), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    void aRoleOutsideTheThree_isStillRefused() throws Exception {
        mvc.perform(post("/tickets/scan")
                        .header("Authorization", "Bearer " + token("SHOP_USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketNumber\":\"20261030-7KQ4M-9XZ2B\",\"eventId\":\"" + eventId + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/scans/me/stats").param("eventId", eventId.toString())
                        .header("Authorization", "Bearer " + token("SHOP_USER")))
                .andExpect(status().isForbidden());
        verify(scanService, never()).scan(anyString(), any(), any());
    }

    @Test
    void noToken_isUnauthorized() throws Exception {
        mvc.perform(post("/tickets/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketNumber\":\"20261030-7KQ4M-9XZ2B\",\"eventId\":\"" + eventId + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
