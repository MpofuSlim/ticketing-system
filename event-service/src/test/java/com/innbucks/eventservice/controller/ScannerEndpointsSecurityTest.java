package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.dto.scanner.AgentSalesEventDTO;
import com.innbucks.eventservice.dto.scanner.AgentSalesResponseDTO;
import com.innbucks.eventservice.dto.scanner.CreateScannerRequestDTO;
import com.innbucks.eventservice.dto.scanner.GateInviteDTO;
import com.innbucks.eventservice.dto.scanner.GateInviteDecisionDTO;
import com.innbucks.eventservice.dto.scanner.GateResponseDTO;
import com.innbucks.eventservice.dto.scanner.ScannerAccessDTO;
import com.innbucks.eventservice.dto.scanner.ScannerResponseDTO;
import com.innbucks.eventservice.dto.scanner.SellerAccessDTO;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
                true, false, List.of(), List.of(), null, null, null);
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
    void addScanner_isTheOrganizersOnly_notStaffsOrACustomers() throws Exception {
        UUID organizer = UUID.randomUUID();
        String body = "{\"firstName\":\"Tendai\",\"lastName\":\"Moyo\",\"phoneNumber\":\"0771234567\"}";

        // The organizerUuid claim alone grants nothing: the role is the gate.
        // Platform staff read an organizer's scanners but never change them.
        mvc.perform(post("/event-organizer/scanners").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + staffToken("PRODUCT_OFFICER", UUID.randomUUID(), organizer)))
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

    // ------------------------------------------------------------ sales agents (V21)

    /** A Foundry portal session of a sales agent: email subject, no phone claim. */
    private static String portalAgentToken(UUID userUuid) {
        return Jwts.builder()
                .subject("tendai.moyo@gmail.com")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .claim("roles", List.of("CUSTOMER", "SALES_AGENT"))
                .claim("userUuid", userUuid.toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();
    }

    @Test
    void sales_withNoSession_is401_notThePublicEventsPermit() throws Exception {
        mvc.perform(get("/events/sales")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("401 UNAUTHORIZED"));
        verifyNoInteractions(scannerService);
    }

    @Test
    void sales_takesTheAccountFromTheToken_inTheAppOrThePortal_andIgnoresTheQuery() throws Exception {
        UUID appAccount = UUID.randomUUID();
        UUID portalAccount = UUID.randomUUID();
        AgentSalesEventDTO row = new AgentSalesEventDTO(SCANNER, EVENT, "Jazz on the Lawn", "Harare Gardens",
                LocalDateTime.of(2026, 11, 14, 13, 0), LocalDateTime.of(2026, 11, 14, 19, 0),
                "Harare Arena Events", 200, new BigDecimal("5.00"));
        when(scannerService.sales(any())).thenReturn(new AgentSalesResponseDTO(List.of(row)));

        mvc.perform(get("/events/sales").param("accountUserUuid", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + customerToken(appAccount, "+263771234567")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=30, private"))
                .andExpect(jsonPath("$.message").value("Your selling events"))
                .andExpect(jsonPath("$.data.events[0].agentId").value(SCANNER.toString()))
                .andExpect(jsonPath("$.data.events[0].ticketCap").value(200))
                .andExpect(jsonPath("$.data.events[0].commissionPercent").value(5.00));
        verify(scannerService).sales(appAccount);

        // A portal session has no phone: none is needed.
        mvc.perform(get("/events/sales").header("Authorization", "Bearer " + portalAgentToken(portalAccount)))
                .andExpect(status().isOk());
        verify(scannerService).sales(portalAccount);
    }

    @Test
    void gate_invitesCarryTheirRoles() throws Exception {
        UUID account = UUID.randomUUID();
        when(scannerService.gate(account, "+263771234567")).thenReturn(new GateResponseDTO(List.of(), List.of(
                new GateInviteDTO(SCANNER, "Harare Arena Events", "Tendai Moyo", null, List.of(), false, true,
                        List.of()))));

        mvc.perform(get("/events/gate").header("Authorization", "Bearer " + customerToken(account, "+263771234567")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invites[0].canScan").value(false))
                .andExpect(jsonPath("$.data.invites[0].canSell").value(true))
                .andExpect(jsonPath("$.data.invites[0].salesEvents").isEmpty());
    }

    @Test
    void sellerAccess_isTokenGated_alwaysAnswers200_andTakesBothIds() throws Exception {
        UUID account = UUID.randomUUID();
        mvc.perform(get("/events/internal/seller-access")
                        .param("accountUserUuid", account.toString()).param("eventId", EVENT.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/events/internal/seller-access").header("X-Internal-Token", "wrong")
                        .param("accountUserUuid", account.toString()).param("eventId", EVENT.toString()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(scannerService);

        UUID organizer = UUID.randomUUID();
        when(scannerService.sellerAccess(account, EVENT)).thenReturn(new SellerAccessDTO(true, SCANNER, organizer,
                "Tendai Moyo", 200, new BigDecimal("5.00")));
        mvc.perform(get("/events/internal/seller-access").header("X-Internal-Token", INTERNAL_TOKEN)
                        .param("accountUserUuid", account.toString()).param("eventId", EVENT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200 OK"))
                .andExpect(jsonPath("$.message").value("Seller access"))
                .andExpect(jsonPath("$.data.allowed").value(true))
                .andExpect(jsonPath("$.data.agentId").value(SCANNER.toString()))
                .andExpect(jsonPath("$.data.organizerUuid").value(organizer.toString()))
                .andExpect(jsonPath("$.data.ticketCap").value(200))
                .andExpect(jsonPath("$.data.commissionPercent").value(5.00));
    }

    @Test
    void sellerAccess_aMissingOrUnreadableId_isADenial_notA400() throws Exception {
        when(scannerService.sellerAccess(any(), any())).thenReturn(SellerAccessDTO.denied());

        mvc.perform(get("/events/internal/seller-access").header("X-Internal-Token", INTERNAL_TOKEN)
                        .param("accountUserUuid", "not-a-uuid").param("eventId", EVENT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(false))
                .andExpect(jsonPath("$.data.agentId").isEmpty())
                .andExpect(jsonPath("$.data.commissionPercent").isEmpty());
        verify(scannerService).sellerAccess(isNull(), eq(EVENT));

        mvc.perform(get("/events/internal/seller-access").header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(false));
    }

    @Test
    void sellerAccess_doesNotTakeAUserSession() throws Exception {
        mvc.perform(get("/events/internal/seller-access")
                        .param("accountUserUuid", UUID.randomUUID().toString()).param("eventId", EVENT.toString())
                        .header("Authorization", "Bearer " + staffToken("SUPER_ADMIN", UUID.randomUUID(), null)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(scannerService);
    }

    @Test
    void roles_isTheOrganizersOnly_andBothFieldsAreRequired() throws Exception {
        UUID organizer = UUID.randomUUID();
        String body = "{\"canScan\":true,\"canSell\":true}";
        mvc.perform(put("/event-organizer/scanners/{s}/roles", SCANNER).contentType(MediaType.APPLICATION_JSON)
                        .content(body).header("Authorization", "Bearer " + staffToken("SUPER_ADMIN", UUID.randomUUID(), null)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/event-organizer/scanners/{s}/roles", SCANNER).contentType(MediaType.APPLICATION_JSON)
                        .content(body).header("Authorization", "Bearer " + portalAgentToken(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(put("/event-organizer/scanners/{s}/roles", SCANNER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canScan\":true}")
                        .header("Authorization", "Bearer " + staffToken("EVENT_ORGANIZER", organizer, organizer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.canSell").value("canSell is required"));
        verifyNoInteractions(scannerService);

        when(scannerService.setRoles(organizer, "event_organizer@example.com", SCANNER, true, true))
                .thenReturn(scanner(organizer));
        mvc.perform(put("/event-organizer/scanners/{s}/roles", SCANNER).contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + staffToken("EVENT_ORGANIZER", organizer, organizer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Roles updated"))
                .andExpect(jsonPath("$.data.canScan").value(true))
                .andExpect(jsonPath("$.data.salesEvents").isArray());
    }

    @Test
    void salesEvent_terms_areValidated_beforeTheService_andNoBodyMeansDefaults() throws Exception {
        UUID organizer = UUID.randomUUID();
        String auth = "Bearer " + staffToken("EVENT_ORGANIZER", organizer, organizer);

        mvc.perform(put("/event-organizer/scanners/{s}/sales-events/{e}", SCANNER, EVENT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ticketCap\":0}")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.ticketCap").value("Ticket cap must be at least 1"));
        mvc.perform(put("/event-organizer/scanners/{s}/sales-events/{e}", SCANNER, EVENT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionPercent\":5.125}")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.commissionPercent").value("Commission can have at most 2 decimals"));
        mvc.perform(put("/event-organizer/scanners/{s}/sales-events/{e}", SCANNER, EVENT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commissionPercent\":100.5}")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.commissionPercent").value("Commission must be between 0 and 100"));
        verifyNoInteractions(scannerService);

        when(scannerService.assignSalesEvent(eq(organizer), any(), eq(SCANNER), eq(EVENT), any(), any()))
                .thenReturn(scanner(organizer));
        mvc.perform(put("/event-organizer/scanners/{s}/sales-events/{e}", SCANNER, EVENT)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Sales event assigned"));
        verify(scannerService).assignSalesEvent(organizer, "event_organizer@example.com", SCANNER, EVENT, null, null);

        mvc.perform(put("/event-organizer/scanners/{s}/sales-events/{e}", SCANNER, EVENT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ticketCap\":200,\"commissionPercent\":5.00}")
                        .header("Authorization", auth))
                .andExpect(status().isOk());
        verify(scannerService).assignSalesEvent(organizer, "event_organizer@example.com", SCANNER, EVENT, 200,
                new BigDecimal("5.00"));
    }

    @Test
    void salesEvent_writes_areTheOrganizersOnly_andRefusalsRenderTheirCode() throws Exception {
        mvc.perform(put("/event-organizer/scanners/{s}/sales-events/{e}", SCANNER, EVENT)
                        .header("Authorization", "Bearer " + staffToken("PRODUCT_MANAGER", UUID.randomUUID(), null)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/event-organizer/scanners/{s}/sales-events/{e}", SCANNER, EVENT)
                        .header("Authorization", "Bearer " + customerToken(UUID.randomUUID(), "+263771234567")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(scannerService);

        UUID organizer = UUID.randomUUID();
        when(scannerService.assignSalesEvent(eq(organizer), any(), eq(SCANNER), eq(EVENT), any(), any()))
                .thenThrow(new ApiProblemException(HttpStatus.CONFLICT, ScannerService.NOT_A_SALES_AGENT,
                        "This person doesn't sell tickets for you. Give them the Sell role first."));
        mvc.perform(put("/event-organizer/scanners/{s}/sales-events/{e}", SCANNER, EVENT)
                        .header("Authorization", "Bearer " + staffToken("EVENT_ORGANIZER", organizer, organizer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("not_a_sales_agent"));

        when(scannerService.unassignSalesEvent(organizer, "event_organizer@example.com", SCANNER, EVENT))
                .thenReturn(scanner(organizer));
        mvc.perform(delete("/event-organizer/scanners/{s}/sales-events/{e}", SCANNER, EVENT)
                        .header("Authorization", "Bearer " + staffToken("EVENT_ORGANIZER", organizer, organizer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Sales event unassigned"));
    }

    @Test
    void portalAccess_isTheOrganizersOnly_andPassesUserServicesRefusalThrough() throws Exception {
        String body = "{\"email\":\"tendai.moyo@gmail.com\"}";
        mvc.perform(put("/event-organizer/scanners/{s}/portal-access", SCANNER).contentType(MediaType.APPLICATION_JSON)
                        .content(body).header("Authorization", "Bearer " + staffToken("SUPER_ADMIN", UUID.randomUUID(), null)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(scannerService);

        UUID organizer = UUID.randomUUID();
        String auth = "Bearer " + staffToken("EVENT_ORGANIZER", organizer, organizer);
        when(scannerService.grantPortalAccess(organizer, "event_organizer@example.com", SCANNER,
                "tendai.moyo@gmail.com")).thenThrow(new ApiProblemException(HttpStatus.BAD_REQUEST,
                "email_domain_reserved",
                "InnBucks staff addresses can't be used here. Your administrator will invite you.",
                java.util.Map.of("field", "email")));
        mvc.perform(put("/event-organizer/scanners/{s}/portal-access", SCANNER).contentType(MediaType.APPLICATION_JSON)
                        .content(body).header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("email_domain_reserved"))
                .andExpect(jsonPath("$.data.field").value("email"));

        Mockito.reset(scannerService);
        when(scannerService.grantPortalAccess(any(), any(), any(), any()))
                .thenThrow(new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                        ScannerService.PORTAL_INVITE_UNAVAILABLE,
                        "We couldn't send the portal invite just now. Please try again."));
        mvc.perform(put("/event-organizer/scanners/{s}/portal-access", SCANNER).contentType(MediaType.APPLICATION_JSON)
                        .content(body).header("Authorization", auth))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("503 SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.errorCode").value("portal_invite_unavailable"));

        when(scannerService.revokePortalAccess(organizer, "event_organizer@example.com", SCANNER))
                .thenReturn(scanner(organizer));
        mvc.perform(delete("/event-organizer/scanners/{s}/portal-access", SCANNER).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Portal access removed"))
                .andExpect(jsonPath("$.data.portalEmail").isEmpty());
    }
}
