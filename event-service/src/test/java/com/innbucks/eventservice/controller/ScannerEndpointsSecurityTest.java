package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.dto.scanner.CreateScannerRequestDTO;
import com.innbucks.eventservice.dto.scanner.GateInviteDecisionDTO;
import com.innbucks.eventservice.dto.scanner.GateResponseDTO;
import com.innbucks.eventservice.dto.scanner.ScannerAccessDTO;
import com.innbucks.eventservice.dto.scanner.ScannerResponseDTO;
import com.innbucks.eventservice.exception.ApiProblemException;
import com.innbucks.eventservice.exception.GlobalExceptionHandler;
import com.innbucks.eventservice.security.JwtFilter;
import com.innbucks.eventservice.security.JwtUtil;
import com.innbucks.eventservice.security.MetricsScrapeAuthFilter;
import com.innbucks.eventservice.security.RevokedTokenDenylist;
import com.innbucks.eventservice.security.SecurityConfig;
import com.innbucks.eventservice.security.TokenVersionStore;
import com.innbucks.eventservice.service.ScannerService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The super-app scanner endpoints through the REAL security chain: a real
 * minted JWT, the real {@link JwtFilter} and {@link SecurityConfig} (with
 * {@code @PreAuthorize}), the real controllers, over a mocked service.
 *
 * <p>A real token, not an injected Authentication, because the phone these
 * endpoints act on comes from the token's {@code phoneNumber} claim through the
 * filter: an injected Authentication would pass with the claim never read.
 */
@SpringJUnitWebConfig(ScannerEndpointsSecurityTest.Ctx.class)
@TestPropertySource(properties = {
        "jwt.secret=" + ScannerEndpointsSecurityTest.SECRET,
        "innbucks.internal-api-token=" + ScannerEndpointsSecurityTest.INTERNAL_TOKEN,
        "innbucks.country=ZW"
})
class ScannerEndpointsSecurityTest {

    static final String SECRET = "test-secret-test-secret-test-secret-1234";
    static final String INTERNAL_TOKEN = "test-internal-token";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private static final UUID SCANNER = UUID.fromString("4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e");
    private static final UUID EVENT = UUID.fromString("5772554b-9c1d-4e2f-a3b4-c5d6e7f8a9b0");

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, ScannerController.class, GateController.class, GlobalExceptionHandler.class})
    static class Ctx {
        @Bean JwtUtil jwtUtil() { return new JwtUtil(); }
        @Bean RevokedTokenDenylist revokedTokenDenylist() { return mock(RevokedTokenDenylist.class); }
        @Bean TokenVersionStore tokenVersionStore() { return mock(TokenVersionStore.class); }
        @Bean JwtFilter jwtFilter(JwtUtil u, RevokedTokenDenylist d, TokenVersionStore s) {
            return new JwtFilter(u, d, s);
        }
        @Bean MetricsScrapeAuthFilter metricsScrapeAuthFilter() { return new MetricsScrapeAuthFilter(""); }
        @Bean ScannerService scannerService() { return mock(ScannerService.class); }
    }

    @Autowired WebApplicationContext context;
    @Autowired ScannerService scannerService;
    @Autowired RevokedTokenDenylist denylist;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        Mockito.reset(scannerService, denylist);
        when(denylist.isRevoked(anyString())).thenReturn(false);
    }

    /** A super-app session: what /auth/exchange mints (CUSTOMER, the phone as subject and claim). */
    private static String customerToken(UUID userUuid, String phone) {
        var builder = Jwts.builder()
                .subject(phone == null ? "customer@example.com" : phone)
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .claim("roles", List.of("CUSTOMER"))
                .claim("tier", 1)
                .claim("userUuid", userUuid.toString());
        if (phone != null) {
            builder.claim("phoneNumber", phone);
        }
        return builder.issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();
    }

    private static String staffToken(String role, UUID userUuid, UUID organizerUuid) {
        var builder = Jwts.builder()
                .subject(role.toLowerCase() + "@example.com")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .claim("roles", List.of(role))
                .claim("userUuid", userUuid.toString());
        if (organizerUuid != null) {
            builder.claim("organizerUuid", organizerUuid.toString());
        }
        return builder.issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();
    }

    private static ScannerResponseDTO scanner(UUID organizer) {
        return new ScannerResponseDTO(SCANNER, organizer, "Tendai", "Moyo", "+263771234567", "INVITED",
                List.of(), null);
    }

    // ------------------------------------------------------------------ gate

    @Test
    void gate_withNoSession_is401_notThePublicEventsPermit() throws Exception {
        mvc.perform(get("/events/gate")).andExpect(status().isUnauthorized());
        verifyNoInteractions(scannerService);
    }

    @Test
    void gate_takesThePhoneAndAccountFromTheToken() throws Exception {
        UUID account = UUID.randomUUID();
        when(scannerService.gate(account, "+263771234567")).thenReturn(new GateResponseDTO(List.of(), List.of()));

        mvc.perform(get("/events/gate")
                        .header("Authorization", "Bearer " + customerToken(account, "+263771234567")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=30, private"))
                .andExpect(jsonPath("$.data.events").isEmpty())
                .andExpect(jsonPath("$.data.invites").isEmpty());

        verify(scannerService).gate(account, "+263771234567");
    }

    @Test
    void gate_ignoresAPhoneInTheQuery() throws Exception {
        UUID account = UUID.randomUUID();
        when(scannerService.gate(any(), any())).thenReturn(new GateResponseDTO(List.of(), List.of()));

        mvc.perform(get("/events/gate").param("phoneNumber", "+263779999999")
                        .header("Authorization", "Bearer " + customerToken(account, "+263771234567")))
                .andExpect(status().isOk());

        verify(scannerService).gate(account, "+263771234567");
    }

    @Test
    void gate_withATokenWithoutAPhone_passesNull_forTheServicesRefusal() throws Exception {
        UUID account = UUID.randomUUID();
        when(scannerService.gate(account, null)).thenThrow(new ApiProblemException(HttpStatus.BAD_REQUEST,
                ScannerService.PHONE_REQUIRED, "Sign in with the InnBucks app to scan tickets."));

        mvc.perform(get("/events/gate").header("Authorization", "Bearer " + customerToken(account, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("phone_required"));
    }

    @Test
    void acceptAndDecline_needASession_andUseTheTokensPhone() throws Exception {
        mvc.perform(post("/events/gate/invites/{id}/accept", SCANNER)).andExpect(status().isUnauthorized());

        UUID account = UUID.randomUUID();
        when(scannerService.accept(account, "+263771234567", SCANNER))
                .thenReturn(new GateInviteDecisionDTO(SCANNER, "ACTIVE", "Harare Arena Events"));
        when(scannerService.decline(account, "+263771234567", SCANNER))
                .thenReturn(new GateInviteDecisionDTO(SCANNER, "DECLINED", "Harare Arena Events"));
        String token = customerToken(account, "+263771234567");

        mvc.perform(post("/events/gate/invites/{id}/accept", SCANNER).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mvc.perform(post("/events/gate/invites/{id}/decline", SCANNER).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"));
    }

    // ------------------------------------------------------------- organizer

    @Test
    void addScanner_isTheOrganizersOnly_notATeamMembersOrACustomers() throws Exception {
        UUID organizer = UUID.randomUUID();
        String body = "{\"firstName\":\"Tendai\",\"lastName\":\"Moyo\",\"phoneNumber\":\"0771234567\"}";

        // A team member's token carries organizerUuid too: the role is the gate.
        mvc.perform(post("/event-organizer/scanners").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + staffToken("TEAM_MEMBER", UUID.randomUUID(), organizer)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/event-organizer/scanners").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + customerToken(UUID.randomUUID(), "+263771234567")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(scannerService);

        when(scannerService.add(eq(organizer), eq("event_organizer@example.com"), any(CreateScannerRequestDTO.class)))
                .thenReturn(scanner(organizer));
        mvc.perform(post("/event-organizer/scanners").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + staffToken("EVENT_ORGANIZER", organizer, organizer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("INVITED"));
    }

    @Test
    void addScanner_withoutAName_is400_beforeTheService() throws Exception {
        UUID organizer = UUID.randomUUID();
        mvc.perform(post("/event-organizer/scanners").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"\",\"lastName\":\"Moyo\",\"phoneNumber\":\"0771234567\"}")
                        .header("Authorization", "Bearer " + staffToken("EVENT_ORGANIZER", organizer, organizer)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(scannerService);
    }

    @Test
    void assign_clash_rendersItsErrorCode() throws Exception {
        UUID organizer = UUID.randomUUID();
        when(scannerService.assign(organizer, "event_organizer@example.com", SCANNER, EVENT))
                .thenThrow(new ApiProblemException(HttpStatus.CONFLICT, ScannerService.SCHEDULE_CLASH,
                        "This number is already assigned to another event at that time."));

        mvc.perform(put("/event-organizer/scanners/{s}/events/{e}", SCANNER, EVENT)
                        .header("Authorization", "Bearer " + staffToken("EVENT_ORGANIZER", organizer, organizer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409 CONFLICT"))
                .andExpect(jsonPath("$.data.errorCode").value("scanner_schedule_clash"));
    }

    @Test
    void list_platformStaffReadEveryOrganizers_anOrganizerOnlyTheirOwn() throws Exception {
        UUID organizer = UUID.randomUUID();
        when(scannerService.list(any())).thenReturn(List.of());

        mvc.perform(get("/event-organizer/scanners")
                        .header("Authorization", "Bearer " + staffToken("SUPER_ADMIN", UUID.randomUUID(), null)))
                .andExpect(status().isOk());
        verify(scannerService).list(null);

        // An organizer asking for another organizer's list still gets their own.
        mvc.perform(get("/event-organizer/scanners").param("organizerUuid", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + staffToken("EVENT_ORGANIZER", organizer, organizer)))
                .andExpect(status().isOk());
        verify(scannerService).list(organizer);
    }

    @Test
    void platformStaff_cannotWrite() throws Exception {
        mvc.perform(delete("/event-organizer/scanners/{s}", SCANNER)
                        .header("Authorization", "Bearer " + staffToken("SUPER_ADMIN", UUID.randomUUID(), null)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(scannerService);
    }

    // --------------------------------------------------------------- internal

    @Test
    void scannerAccess_isTokenGated_andReachesTheControllerWithoutASession() throws Exception {
        UUID account = UUID.randomUUID();
        mvc.perform(get("/events/internal/scanner-access")
                        .param("userUuid", account.toString()).param("eventId", EVENT.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/events/internal/scanner-access").header("X-Internal-Token", "wrong")
                        .param("userUuid", account.toString()).param("eventId", EVENT.toString()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(scannerService);

        when(scannerService.access(account, EVENT))
                .thenReturn(new ScannerAccessDTO(true, SCANNER, UUID.randomUUID(), "Tendai Moyo"));
        mvc.perform(get("/events/internal/scanner-access").header("X-Internal-Token", INTERNAL_TOKEN)
                        .param("userUuid", account.toString()).param("eventId", EVENT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(true))
                .andExpect(jsonPath("$.data.displayName").value("Tendai Moyo"));
    }
}
