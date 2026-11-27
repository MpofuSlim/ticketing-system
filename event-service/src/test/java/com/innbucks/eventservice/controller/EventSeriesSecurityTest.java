package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.dto.ReviewStatus;
import com.innbucks.eventservice.dto.series.CreateSeriesRequestDTO;
import com.innbucks.eventservice.dto.series.EventSeriesResponseDTO;
import com.innbucks.eventservice.dto.series.FuturePriceRequestDTO;
import com.innbucks.eventservice.dto.series.SeriesChangeDTO;
import com.innbucks.eventservice.dto.series.SeriesScheduleRequestDTO;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.exception.GlobalExceptionHandler;
import com.innbucks.eventservice.security.JwtFilter;
import com.innbucks.eventservice.security.JwtUtil;
import com.innbucks.eventservice.security.MetricsScrapeAuthFilter;
import com.innbucks.eventservice.security.RevokedTokenDenylist;
import com.innbucks.eventservice.security.SecurityConfig;
import com.innbucks.eventservice.security.TokenVersionStore;
import com.innbucks.eventservice.service.EventSeriesService;
import com.innbucks.eventservice.service.SeriesDatesService;
import com.innbucks.eventservice.service.SeriesEditService;
import com.innbucks.eventservice.dto.series.SeriesMonthDTO;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The series endpoints through the REAL security chain (TIC-70): a real minted
 * JWT, the real {@link JwtFilter} and {@link SecurityConfig}, over a mocked
 * service. The reads sit under the public {@code GET /events/**} permit, so a
 * dropped matcher would answer them anonymously; these tests are what notices.
 */
@SpringJUnitWebConfig(EventSeriesSecurityTest.Ctx.class)
@TestPropertySource(properties = {
        "jwt.secret=" + EventSeriesSecurityTest.SECRET,
        "innbucks.internal-api-token=test-internal-token",
        "innbucks.country=ZW"
})
class EventSeriesSecurityTest {

    static final String SECRET = "test-secret-test-secret-test-secret-1234";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    private static final UUID SERIES = UUID.fromString("5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f");

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, EventSeriesController.class, SeriesDatesController.class,
            SeriesInternalController.class,
            GlobalExceptionHandler.class})
    static class Ctx {
        @Bean JwtUtil jwtUtil() { return new JwtUtil(); }
        @Bean RevokedTokenDenylist revokedTokenDenylist() { return mock(RevokedTokenDenylist.class); }
        @Bean TokenVersionStore tokenVersionStore() { return mock(TokenVersionStore.class); }
        @Bean JwtFilter jwtFilter(JwtUtil u, RevokedTokenDenylist d, TokenVersionStore s) {
            return new JwtFilter(u, d, s);
        }
        @Bean MetricsScrapeAuthFilter metricsScrapeAuthFilter() { return new MetricsScrapeAuthFilter(""); }
        @Bean EventSeriesService seriesService() { return mock(EventSeriesService.class); }
        @Bean SeriesEditService editService() { return mock(SeriesEditService.class); }
        @Bean SeriesDatesService datesService() { return mock(SeriesDatesService.class); }
        // What Spring Boot registers in the application: @Validated's @Min/@Max on the paging params.
        @Bean static MethodValidationPostProcessor methodValidationPostProcessor() {
            return new MethodValidationPostProcessor();
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired EventSeriesService seriesService;
    @Autowired SeriesEditService editService;
    @Autowired SeriesDatesService datesService;
    @Autowired RevokedTokenDenylist denylist;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        Mockito.reset(seriesService, editService, datesService, denylist);
        when(denylist.isRevoked(anyString())).thenReturn(false);
    }

    private static String token(String role, UUID userUuid, UUID organizerUuid) {
        var builder = Jwts.builder()
                .subject(role.toLowerCase() + "@example.com")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .claim("roles", List.of(role))
                .claim("userUuid", userUuid.toString())
                .claim("country", "ZW")
                .claim("phoneNumber", "+263771234567");
        if (organizerUuid != null) {
            builder.claim("organizerUuid", organizerUuid.toString());
        }
        return builder.issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();
    }

    private static String bearer(String role, UUID organizer) {
        return "Bearer " + token(role, organizer == null ? UUID.randomUUID() : organizer, organizer);
    }

    private static EventSeriesResponseDTO series(EventSeries.Status status, ReviewStatus review) {
        return EventSeriesResponseDTO.builder().seriesId(SERIES).status(status).reviewStatus(review).build();
    }

    private static final String BODY = """
            {"title":"Friday Comedy Night","venue":"Theatre in the Park","category":"COMEDY",
             "location":{"latitude":-17.82,"longitude":31.05},"totalCapacity":120,
             "daysOfWeek":["FRIDAY"],"startTimes":["19:00"],"durationMinutes":120,"startDate":"2026-12-04"}""";

    @Test
    void theReportLookup_isTheInternalTokens_withNoSession() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID date = UUID.randomUUID();
        when(seriesService.dateIds(SERIES)).thenReturn(
                new com.innbucks.eventservice.dto.series.SeriesDateIdsDTO(SERIES, true, owner, List.of(date)));

        mvc.perform(get("/events/internal/series-dates").param("seriesId", SERIES.toString())
                        .header("X-Internal-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Series dates resolved"))
                .andExpect(jsonPath("$.data.found").value(true))
                .andExpect(jsonPath("$.data.tenantUserUuid").value(owner.toString()))
                .andExpect(jsonPath("$.data.eventIds[0]").value(date.toString()));

        mvc.perform(get("/events/internal/series-dates").param("seriesId", SERIES.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/events/internal/series-dates").param("seriesId", SERIES.toString())
                        .header("X-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());
        // A signed-in organizer's bearer is not the internal token.
        mvc.perform(get("/events/internal/series-dates").param("seriesId", SERIES.toString())
                        .header("Authorization", bearer("EVENT_ORGANIZER", owner)))
                .andExpect(status().isUnauthorized());
        verify(seriesService, Mockito.times(1)).dateIds(SERIES);
    }

    @Test
    void theReads_needASession_despiteThePublicEventsPermit() throws Exception {
        mvc.perform(get("/events/series")).andExpect(status().isUnauthorized());
        mvc.perform(get("/events/series/" + SERIES)).andExpect(status().isUnauthorized());
        verifyNoInteractions(seriesService);
    }

    @Test
    void theBuyersDates_arePublic_whileTheSeriesItselfStaysSignedIn() throws Exception {
        when(datesService.month(SERIES, "2026-12")).thenReturn(SeriesMonthDTO.builder()
                .seriesId(SERIES).month("2026-12").months(List.of("2026-12")).dates(List.of()).build());

        mvc.perform(get("/events/series/" + SERIES + "/dates").param("month", "2026-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Series dates retrieved"))
                .andExpect(jsonPath("$.data.month").value("2026-12"));
        mvc.perform(get("/events/series/" + SERIES + "/dates").param("month", "2026-12")
                        .header("Authorization", bearer("CUSTOMER", null)))
                .andExpect(status().isOk());

        // The permit is that one path: the series detail beside it still needs a session.
        mvc.perform(get("/events/series/" + SERIES)).andExpect(status().isUnauthorized());
        mvc.perform(get("/events/series/" + SERIES + "/dates/extra")).andExpect(status().isUnauthorized());
        verifyNoInteractions(seriesService);
    }

    @Test
    void theBuyersDates_staysClosedToAGateSession() throws Exception {
        mvc.perform(get("/events/series/" + SERIES + "/dates").header("Authorization", bearer("GATE_SCANNER", null)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(datesService);
    }

    @Test
    void aCustomer_orAGateSession_cannotReadOrCreate() throws Exception {
        mvc.perform(get("/events/series").header("Authorization", bearer("CUSTOMER", null)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/events/series/" + SERIES).header("Authorization", bearer("GATE_SCANNER", null)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/events/series").header("Authorization", bearer("CUSTOMER", null))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(seriesService);
    }

    @Test
    void anOrganizer_creates_withTheOrganizerAndCountryFromTheToken() throws Exception {
        UUID organizer = UUID.randomUUID();
        when(seriesService.create(eq(organizer), eq("ZW"), any(CreateSeriesRequestDTO.class)))
                .thenReturn(series(EventSeries.Status.DRAFT, ReviewStatus.DRAFT));

        mvc.perform(post("/events/series").header("Authorization", bearer("EVENT_ORGANIZER", organizer))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Series created"))
                .andExpect(jsonPath("$.data.seriesId").value(SERIES.toString()));

        ArgumentCaptor<CreateSeriesRequestDTO> request = ArgumentCaptor.forClass(CreateSeriesRequestDTO.class);
        verify(seriesService).create(eq(organizer), eq("ZW"), request.capture());
        assertThat(request.getValue().getStartTimes()).containsExactly("19:00");
    }

    @Test
    void aMissingField_isABadRequest_beforeTheService() throws Exception {
        mvc.perform(post("/events/series").header("Authorization", bearer("EVENT_ORGANIZER", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Friday\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(seriesService);
    }

    @Test
    void platformStaff_readEveryOrganizersSeries() throws Exception {
        when(seriesService.list(any(), eq(true), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/events/series").header("Authorization", bearer("PRODUCT_OFFICER", null)))
                .andExpect(status().isOk());

        verify(seriesService).list(any(), eq(true), eq(0), eq(20));
    }

    @Test
    void publishing_isRefusedToTheReadOnlyOfficer_andTellsTheOrganizerWhenItWasSubmitted() throws Exception {
        mvc.perform(post("/events/series/" + SERIES + "/publish")
                        .header("Authorization", bearer("PRODUCT_OFFICER", null)))
                .andExpect(status().isForbidden());

        UUID organizer = UUID.randomUUID();
        when(seriesService.publish(eq(organizer), eq("ROLE_EVENT_ORGANIZER"), eq(false), eq(SERIES)))
                .thenReturn(series(EventSeries.Status.DRAFT, ReviewStatus.SUBMITTED));
        mvc.perform(post("/events/series/" + SERIES + "/publish")
                        .header("Authorization", bearer("EVENT_ORGANIZER", organizer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Series submitted for review"));

        when(seriesService.publish(any(), anyString(), anyBoolean(), eq(SERIES)))
                .thenReturn(series(EventSeries.Status.ACTIVE, ReviewStatus.APPROVED));
        mvc.perform(post("/events/series/" + SERIES + "/publish")
                        .header("Authorization", bearer("SUPER_ADMIN", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Series published"));
        verify(seriesService).publish(any(), eq("ROLE_SUPER_ADMIN"), eq(true), eq(SERIES));
    }

    @Test
    void aPageTooBig_isRefused() throws Exception {
        mvc.perform(get("/events/series").param("size", "101")
                        .header("Authorization", bearer("EVENT_ORGANIZER", UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        Mockito.verify(seriesService, Mockito.never()).list(any(), anyBoolean(), anyInt(), anyInt());
    }

    // ---- Changing a series (TIC-71) --------------------------------------------

    private static final String SCHEDULE = """
            {"daysOfWeek":["SATURDAY"],"startTimes":["19:00"],"durationMinutes":120,"startDate":"2026-12-04"}""";

    private static SeriesChangeDTO changed() {
        return SeriesChangeDTO.builder().series(series(EventSeries.Status.ACTIVE, ReviewStatus.APPROVED))
                .dates(List.of()).build();
    }

    @Test
    void theEdits_areRefusedWithoutASession_toCustomers_andToTheReadOnlyOfficer() throws Exception {
        String[][] posts = {{"/pause"}, {"/resume"}, {"/end"}, {"/dates/" + UUID.randomUUID() + "/skip"}};
        for (String[] path : posts) {
            mvc.perform(post("/events/series/" + SERIES + path[0])).andExpect(status().isUnauthorized());
            mvc.perform(post("/events/series/" + SERIES + path[0]).header("Authorization", bearer("CUSTOMER", null)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/events/series/" + SERIES + path[0])
                            .header("Authorization", bearer("PRODUCT_OFFICER", null)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(put("/events/series/" + SERIES + "/schedule").header("Authorization", bearer("PRODUCT_OFFICER", null))
                        .contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))
                .andExpect(status().isForbidden());
        mvc.perform(put("/events/series/" + SERIES + "/future-prices")
                        .header("Authorization", bearer("GATE_SCANNER", null))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categoryName\":\"VIP\",\"price\":150}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(editService);
    }

    @Test
    void anOrganizersScheduleEdit_reachesTheServiceWithTheOrganizerFromTheToken() throws Exception {
        UUID organizer = UUID.randomUUID();
        when(editService.updateSchedule(eq(organizer), eq(false), eq(SERIES), any(SeriesScheduleRequestDTO.class)))
                .thenReturn(changed());

        mvc.perform(put("/events/series/" + SERIES + "/schedule")
                        .header("Authorization", bearer("EVENT_ORGANIZER", organizer))
                        .contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Schedule changed"))
                .andExpect(jsonPath("$.data.series.seriesId").value(SERIES.toString()))
                .andExpect(jsonPath("$.data.dates").isArray());
    }

    @Test
    void aScheduleWithoutItsRequiredFields_isABadRequest_beforeTheService() throws Exception {
        mvc.perform(put("/events/series/" + SERIES + "/schedule")
                        .header("Authorization", bearer("EVENT_ORGANIZER", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"daysOfWeek\":[\"FRIDAY\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/events/series/" + SERIES + "/future-prices")
                        .header("Authorization", bearer("EVENT_ORGANIZER", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categoryName\":\"VIP\",\"price\":1.005}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.price").value("Use at most 2 decimals."));
        verifyNoInteractions(editService);
    }

    @Test
    void aPriceChange_recordsTheOrganizersUuid_orAStaffMembersEmail_asTheActor() throws Exception {
        UUID organizer = UUID.randomUUID();
        when(editService.setFuturePrice(any(), anyBoolean(), anyString(), eq(SERIES), any(FuturePriceRequestDTO.class)))
                .thenReturn(changed());

        mvc.perform(put("/events/series/" + SERIES + "/future-prices")
                        .header("Authorization", bearer("EVENT_ORGANIZER", organizer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categoryName\":\"VIP\",\"price\":150}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Price changed"));
        verify(editService).setFuturePrice(eq(organizer), eq(false), eq(organizer.toString()), eq(SERIES),
                any(FuturePriceRequestDTO.class));

        mvc.perform(put("/events/series/" + SERIES + "/future-prices")
                        .header("Authorization", bearer("PRODUCT_MANAGER", null))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categoryName\":\"VIP\",\"price\":150}"))
                .andExpect(status().isOk());
        verify(editService).setFuturePrice(eq(null), eq(true), eq("product_manager@example.com"), eq(SERIES),
                any(FuturePriceRequestDTO.class));
    }

    @Test
    void pauseResumeEndAndSkip_answerWithTheirOwnWords() throws Exception {
        UUID organizer = UUID.randomUUID();
        UUID date = UUID.randomUUID();
        when(editService.pause(organizer, false, SERIES)).thenReturn(changed());
        when(editService.resume(organizer, false, SERIES)).thenReturn(changed());
        when(editService.end(organizer, false, SERIES)).thenReturn(changed());
        when(editService.skipDate(organizer, false, SERIES, date)).thenReturn(changed());
        String auth = bearer("EVENT_ORGANIZER", organizer);

        mvc.perform(post("/events/series/" + SERIES + "/pause").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Series paused"));
        mvc.perform(post("/events/series/" + SERIES + "/resume").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Series resumed"));
        mvc.perform(post("/events/series/" + SERIES + "/end").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Series ended"));
        mvc.perform(post("/events/series/" + SERIES + "/dates/" + date + "/skip").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Date skipped"));
    }
}
