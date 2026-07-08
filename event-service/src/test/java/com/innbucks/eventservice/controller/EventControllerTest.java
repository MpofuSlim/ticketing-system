package com.innbucks.eventservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.eventservice.client.UserUuidLookupGateway;
import com.innbucks.eventservice.dto.CreateEventRequestDTO;
import com.innbucks.eventservice.dto.LocationDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.security.AuthDetailsKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@ActiveProfiles("test")
@Import(EventControllerTest.MockGatewayConfig.class)
class EventControllerTest {

    MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EventRepository eventRepository;
    @Autowired WebApplicationContext wac;

    // UserUuidLookupGateway makes a real S2S HTTP call to user-service for the
    // team-member assigned-events lookup; in this MockMvc slice we drive it with
    // a Mockito mock so the team-member tests can pin exactly which events the
    // organizer "assigned". @Primary + @TestConfiguration override the real
    // @Component bean (the Boot-4-reliable pattern; @MockitoBean is flaky here).
    @Autowired UserUuidLookupGateway userUuidLookupGateway;

    @TestConfiguration
    static class MockGatewayConfig {
        @Bean
        @Primary
        UserUuidLookupGateway mockUserUuidLookupGateway() {
            return Mockito.mock(UserUuidLookupGateway.class);
        }
    }

    // Stable per-organizer UUIDs replacing the legacy "tenant-1"/"tenant-99"/
    // "tenant-A"/"tenant-B" strings. organizerUuid is now the JWT-stamped
    // owning-organizer identifier (previously the principal email did double
    // duty as the tenantId).
    private static final UUID ORGANIZER_1 = UUID.randomUUID();
    private static final UUID ORGANIZER_99 = UUID.randomUUID();
    private static final UUID ORGANIZER_A = UUID.randomUUID();
    private static final UUID ORGANIZER_B = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(wac).apply(springSecurity()).build();
        eventRepository.deleteAll();
        // Reset the shared mock between tests so an assigned-events stub from
        // one team-member case can't bleed into another; default (unstubbed)
        // returns an empty list = no assignments.
        Mockito.reset(userUuidLookupGateway);
    }

    /**
     * Builds an Authentication that mimics what {@link
     * com.innbucks.eventservice.security.JwtFilter} produces: principal name,
     * granted authorities, plus the JWT UUID claims on the details map. Needed
     * because {@link WithMockUser} only sets principal/authorities — controllers
     * resolve the owning organizer's uuid via
     * {@link com.innbucks.eventservice.security.AuthenticatedCaller#organizerUuid}
     * which reads {@link AuthDetailsKeys#ORGANIZER_UUID} off the details map.
     */
    private static RequestPostProcessor jwtAuth(String principal, UUID organizerUuid, String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        Map<String, Object> details = new LinkedHashMap<>();
        if (organizerUuid != null) {
            details.put(AuthDetailsKeys.ORGANIZER_UUID, organizerUuid);
        }
        token.setDetails(details);
        return authentication(token);
    }

    /**
     * Like {@link #jwtAuth(String, UUID, String...)} but also stamps the
     * caller's own {@code userUuid} claim — the value
     * {@code AuthenticatedCaller.userUuid} reads for the TEAM_MEMBER
     * assigned-events lookup. For a TEAM_MEMBER, {@code organizerUuid} is the
     * parent organizer's uuid (the ownership filter) and {@code userUuid} is
     * the member's own id (the assignment key), so the two differ.
     */
    private static RequestPostProcessor jwtAuthWithUser(
            String principal, UUID organizerUuid, UUID userUuid, String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        Map<String, Object> details = new LinkedHashMap<>();
        if (organizerUuid != null) {
            details.put(AuthDetailsKeys.ORGANIZER_UUID, organizerUuid);
        }
        if (userUuid != null) {
            details.put(AuthDetailsKeys.USER_UUID, userUuid);
        }
        token.setDetails(details);
        return authentication(token);
    }

    @Test
    void getAllEvents_isPublic_andReturnsLocalDateTimeInDto() throws Exception {
        Event saved = eventRepository.save(Event.builder()
                .tenantUserUuid(ORGANIZER_1)
                .title("Test Event")
                .description("desc")
                .venue("Harare")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.of(2030, 4, 18, 10, 30))
                .endDateTime(LocalDateTime.of(2030, 4, 18, 12, 30))
                .totalCapacity(100)
                .availableTickets(100)
                .deleted(false)
                .build());

        mockMvc.perform(get("/events")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is("200 OK")))
                .andExpect(jsonPath("$.data.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data.content[0].eventId", is(saved.getEventId().toString())))
                .andExpect(jsonPath("$.data.content[0].country", is("Zimbabwe")))
                .andExpect(jsonPath("$.data.content[0].category", is("CONCERT")))
                .andExpect(jsonPath("$.data.content[0].startDateTime", is("2030-04-18T10:30:00")))
                .andExpect(jsonPath("$.data.content[0].endDateTime", is("2030-04-18T12:30:00")));
    }

    @Test
    void createEvent_requiresAuthentication() throws Exception {
        MockMultipartFile eventPart = new MockMultipartFile(
                "event", "event.json", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(sampleRequest("Concert", EventCategory.CONCERT)));

        mockMvc.perform(multipart("/events").file(eventPart))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createEvent_withTenantRole_createsEvent() throws Exception {
        MockMultipartFile eventPart = new MockMultipartFile(
                "event", "event.json", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(sampleRequest("Concert", EventCategory.COMEDY)));

        // Auth post-processor stands in for JwtFilter: it sets the principal,
        // EVENT_ORGANIZER role, and (crucially) the organizerUuid on the auth
        // details map so the controller can resolve the owning tenantUserUuid.
        // jwtCountry is the request attribute the controller reads in place of
        // the JWT country claim under @WithMockUser-style auth.
        mockMvc.perform(multipart("/events").file(eventPart)
                        .with(jwtAuth("tenant-99", ORGANIZER_99, "EVENT_ORGANIZER"))
                        .requestAttr("jwtCountry", "Zimbabwe"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code", is("201 CREATED")))
                .andExpect(jsonPath("$.data.tenantUserUuid", is(ORGANIZER_99.toString())))
                .andExpect(jsonPath("$.data.title", is("Concert")))
                .andExpect(jsonPath("$.data.country", is("Zimbabwe")))
                .andExpect(jsonPath("$.data.category", is("COMEDY")))
                .andExpect(jsonPath("$.data.availableTickets", is(50)))
                .andExpect(jsonPath("$.data.location.latitude", is(-17.8252)))
                .andExpect(jsonPath("$.data.location.longitude", is(31.0335)))
                .andExpect(jsonPath("$.data.bannerUrl", nullValue()));
    }

    @Test
    void createEvent_withoutCountryClaim_isRejected() throws Exception {
        MockMultipartFile eventPart = new MockMultipartFile(
                "event", "event.json", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(sampleRequest("No Country Concert", EventCategory.SPORT)));

        // No jwtCountry attribute → controller has no country to stamp → 400.
        mockMvc.perform(multipart("/events").file(eventPart)
                        .with(jwtAuth("tenant-99", ORGANIZER_99, "EVENT_ORGANIZER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsStringIgnoringCase("country")));
    }

    @Test
    void createEvent_withBanner_storesBytesAndReturnsBannerUrl() throws Exception {
        MockMultipartFile eventPart = new MockMultipartFile(
                "event", "event.json", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(sampleRequest("Concert With Banner", EventCategory.CONCERT)));

        // ~200 KB payload — guards against the column-type regression where
        // Hibernate generated a 32 KB varbinary on H2 and truncated real images.
        byte[] pngBytes = new byte[200 * 1024];
        pngBytes[0] = (byte) 0x89; pngBytes[1] = 0x50; pngBytes[2] = 0x4E; pngBytes[3] = 0x47;
        pngBytes[4] = 0x0D; pngBytes[5] = 0x0A; pngBytes[6] = 0x1A; pngBytes[7] = 0x0A;
        MockMultipartFile bannerPart = new MockMultipartFile(
                "eventBanner", "banner.png", MediaType.IMAGE_PNG_VALUE, pngBytes);

        String body = mockMvc.perform(multipart("/events").file(eventPart).file(bannerPart)
                        .with(jwtAuth("tenant-99", ORGANIZER_99, "EVENT_ORGANIZER"))
                        .requestAttr("jwtCountry", "Zimbabwe"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bannerUrl", containsString("/banner")))
                .andReturn().getResponse().getContentAsString();

        String eventId = objectMapper.readTree(body).path("data").path("eventId").asText();

        // The event is created as a draft (active=false, pending approval), so the
        // by-id + banner reads are the OWNER's preview — authenticate as the
        // creating organizer. (Anonymous access to an unpublished event is 404 now.)
        mockMvc.perform(get("/events/" + eventId + "/banner")
                        .with(jwtAuth("tenant-99", ORGANIZER_99, "EVENT_ORGANIZER")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(pngBytes));

        mockMvc.perform(get("/events/" + eventId)
                        .with(jwtAuth("tenant-99", ORGANIZER_99, "EVENT_ORGANIZER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bannerUrl", containsString("/banner")));
    }

    @Test
    void updateEvent_persistsChangesToDatabase() throws Exception {
        Event saved = eventRepository.save(Event.builder()
                .tenantUserUuid(ORGANIZER_1)
                .title("Original Title")
                .description("Original")
                .venue("Old Venue")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.of(2030, 1, 1, 10, 0))
                .endDateTime(LocalDateTime.of(2030, 1, 1, 12, 0))
                .totalCapacity(100)
                .availableTickets(100)
                .deleted(false)
                .build());

        String body = "{\"title\":\"Updated Title\",\"venue\":\"New Venue\","
                + "\"startDateTime\":\"2031-06-15T19:00:00.000Z\",\"endDateTime\":\"2031-06-15T22:00:00.000Z\"}";

        mockMvc.perform(put("/events/" + saved.getEventId())
                        .with(jwtAuth("tenant-1", ORGANIZER_1, "EVENT_ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title", is("Updated Title")))
                .andExpect(jsonPath("$.data.venue", is("New Venue")))
                .andExpect(jsonPath("$.data.startDateTime", is("2031-06-15T19:00:00")))
                .andExpect(jsonPath("$.data.endDateTime", is("2031-06-15T22:00:00")));

        Event reloaded = eventRepository.findById(saved.getEventId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("Updated Title", reloaded.getTitle());
        org.junit.jupiter.api.Assertions.assertEquals("New Venue", reloaded.getVenue());
        org.junit.jupiter.api.Assertions.assertEquals(
                LocalDateTime.of(2031, 6, 15, 19, 0), reloaded.getStartDateTime());
        org.junit.jupiter.api.Assertions.assertEquals(
                LocalDateTime.of(2031, 6, 15, 22, 0), reloaded.getEndDateTime());
    }

    private static CreateEventRequestDTO sampleRequest(String title, EventCategory category) {
        CreateEventRequestDTO req = new CreateEventRequestDTO();
        req.setTitle(title);
        req.setDescription("desc");
        req.setVenue("Bulawayo");
        req.setCategory(category);
        req.setLocation(LocationDTO.builder().latitude(-17.8252).longitude(31.0335).build());
        req.setStartDateTime(LocalDateTime.now().plusDays(10));
        req.setEndDateTime(LocalDateTime.now().plusDays(10).plusHours(3));
        req.setTotalCapacity(50);
        return req;
    }

    @Test
    void getEventsByCountry_onlyReturnsActiveUpcomingEvents_numberedFromOne() throws Exception {
        eventRepository.save(eventBuilder()
                .title("Third")
                .startDateTime(LocalDateTime.now().plusDays(30))
                .endDateTime(LocalDateTime.now().plusDays(30).plusHours(2))
                .build());
        eventRepository.save(eventBuilder()
                .title("First")
                .startDateTime(LocalDateTime.now().plusDays(10))
                .endDateTime(LocalDateTime.now().plusDays(10).plusHours(2))
                .build());
        eventRepository.save(eventBuilder()
                .title("Second")
                .startDateTime(LocalDateTime.now().plusDays(20))
                .endDateTime(LocalDateTime.now().plusDays(20).plusHours(2))
                .build());

        eventRepository.save(eventBuilder()
                .title("Past")
                .startDateTime(LocalDateTime.now().minusDays(1))
                .endDateTime(LocalDateTime.now().minusDays(1).plusHours(2))
                .build());

        eventRepository.save(eventBuilder()
                .title("Inactive")
                .startDateTime(LocalDateTime.now().plusDays(15))
                .endDateTime(LocalDateTime.now().plusDays(15).plusHours(2))
                .active(false)
                .build());

        eventRepository.save(eventBuilder()
                .title("Other country")
                .country("Zambia")
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2))
                .build());

        // Lowercase param exercises the case-insensitive country match.
        mockMvc.perform(get("/events/by-country")
                        .param("country", "zimbabwe")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is("200 OK")))
                .andExpect(jsonPath("$.data.content", hasSize(3)))
                .andExpect(jsonPath("$.data.content[0].title", is("First")))
                .andExpect(jsonPath("$.data.content[0].eventNo", is(1)))
                .andExpect(jsonPath("$.data.content[1].title", is("Second")))
                .andExpect(jsonPath("$.data.content[1].eventNo", is(2)))
                .andExpect(jsonPath("$.data.content[2].title", is("Third")))
                .andExpect(jsonPath("$.data.content[2].eventNo", is(3)));
    }

    @Test
    void getActiveEvents_filtersByCountryAndCategory() throws Exception {
        eventRepository.save(eventBuilder().title("ZW Concert")
                .country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(3))
                .endDateTime(LocalDateTime.now().plusDays(3).plusHours(2))
                .build());
        eventRepository.save(eventBuilder().title("ZW Marathon")
                .country("Zimbabwe").category(EventCategory.MARATHON)
                .startDateTime(LocalDateTime.now().plusDays(4))
                .endDateTime(LocalDateTime.now().plusDays(4).plusHours(2))
                .build());
        eventRepository.save(eventBuilder().title("ZM Concert")
                .country("Zambia").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2))
                .build());

        // Country filter only (case-insensitive) → the two Zimbabwe events.
        mockMvc.perform(get("/events/active").param("country", "zimbabwe")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(2)));

        // Category filter only → the two CONCERT events.
        mockMvc.perform(get("/events/active").param("category", "CONCERT")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(2)));

        // Both filters together → only the Zimbabwe concert.
        mockMvc.perform(get("/events/active")
                        .param("country", "Zimbabwe").param("category", "CONCERT")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].title", is("ZW Concert")))
                .andExpect(jsonPath("$.data.content[0].country", is("Zimbabwe")))
                .andExpect(jsonPath("$.data.content[0].category", is("CONCERT")));
    }

    private static Event.EventBuilder eventBuilder() {
        return Event.builder()
                .tenantUserUuid(ORGANIZER_1)
                .description("desc")
                .venue("Venue")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .totalCapacity(100)
                .availableTickets(100)
                .deleted(false)
                .active(true);
    }

    // Test config inherits `innbucks.internal-api-token` default from
    // application.yaml: change-me-internal-token-change-me-internal-token
    // (unified fleet-wide so cross-service S2S works without the env var).
    private static final String VALID_INTERNAL_TOKEN = "change-me-internal-token-change-me-internal-token";

    @Test
    void consumeAvailability_withoutInternalToken_returns401_andDoesNotMutate() throws Exception {
        Event saved = eventRepository.save(eventBuilder()
                .title("Concert")
                .startDateTime(LocalDateTime.now().plusDays(7))
                .endDateTime(LocalDateTime.now().plusDays(7).plusHours(2))
                .totalCapacity(100)
                .availableTickets(100)
                .build());

        mockMvc.perform(patch("/events/{id}/availability/consume", saved.getEventId())
                        .param("count", "5"))
                .andExpect(status().isUnauthorized());

        // Capacity must not have changed.
        Event reloaded = eventRepository.findById(saved.getEventId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(100, reloaded.getAvailableTickets());
    }

    @Test
    void consumeAvailability_withWrongInternalToken_returns401_andDoesNotMutate() throws Exception {
        Event saved = eventRepository.save(eventBuilder()
                .title("Concert")
                .startDateTime(LocalDateTime.now().plusDays(7))
                .endDateTime(LocalDateTime.now().plusDays(7).plusHours(2))
                .totalCapacity(100)
                .availableTickets(100)
                .build());

        mockMvc.perform(patch("/events/{id}/availability/consume", saved.getEventId())
                        .param("count", "5")
                        .header("X-Internal-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());

        Event reloaded = eventRepository.findById(saved.getEventId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(100, reloaded.getAvailableTickets());
    }

    @Test
    void consumeAvailability_withValidInternalToken_returns200_andDecrements() throws Exception {
        Event saved = eventRepository.save(eventBuilder()
                .title("Concert")
                .startDateTime(LocalDateTime.now().plusDays(7))
                .endDateTime(LocalDateTime.now().plusDays(7).plusHours(2))
                .totalCapacity(100)
                .availableTickets(100)
                .build());

        mockMvc.perform(patch("/events/{id}/availability/consume", saved.getEventId())
                        .param("count", "5")
                        .header("X-Internal-Token", VALID_INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is("200 OK")))
                .andExpect(jsonPath("$.data.availableTickets", is(95)));

        Event reloaded = eventRepository.findById(saved.getEventId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(95, reloaded.getAvailableTickets());
    }

    @Test
    @WithMockUser(username = "admin@innbucks.co.zw", roles = "SUPER_ADMIN")
    void getEventsByOrganizer_asSuperAdmin_returnsOnlyThatOrganizersEvents() throws Exception {
        eventRepository.save(eventBuilder().tenantUserUuid(ORGANIZER_A).title("A Concert")
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2)).build());
        eventRepository.save(eventBuilder().tenantUserUuid(ORGANIZER_A).title("A Marathon")
                .startDateTime(LocalDateTime.now().plusDays(6))
                .endDateTime(LocalDateTime.now().plusDays(6).plusHours(2)).build());
        eventRepository.save(eventBuilder().tenantUserUuid(ORGANIZER_B).title("B Concert")
                .startDateTime(LocalDateTime.now().plusDays(7))
                .endDateTime(LocalDateTime.now().plusDays(7).plusHours(2)).build());

        mockMvc.perform(get("/events/by-organizer").param("organizerUuid", ORGANIZER_A.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is("200 OK")))
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.content[*].tenantUserUuid", everyItem(is(ORGANIZER_A.toString()))));
    }

    @Test
    @WithMockUser(username = "admin@innbucks.co.zw", roles = "SUPER_ADMIN")
    void getEventsByOrganizer_unknownOrganizer_returnsEmptyPage() throws Exception {
        eventRepository.save(eventBuilder().tenantUserUuid(ORGANIZER_A).title("A Concert")
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2)).build());

        mockMvc.perform(get("/events/by-organizer").param("organizerUuid", UUID.randomUUID().toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    @Test
    @WithMockUser(username = "admin@innbucks.co.zw", roles = "SUPER_ADMIN")
    void getEventsByOrganizer_malformedOrganizerUuid_returns400() throws Exception {
        // A non-UUID value can't bind to the @RequestParam UUID — Spring's
        // type conversion rejects it before the controller runs. The 400
        // here proves the param is typed (UUID, not String) — protection
        // against the legacy free-form "tenant-A" query value.
        mockMvc.perform(get("/events/by-organizer").param("organizerUuid", "not-a-uuid")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "tenant-99", roles = "EVENT_ORGANIZER")
    void getEventsByOrganizer_asEventOrganizer_isForbidden() throws Exception {
        mockMvc.perform(get("/events/by-organizer").param("organizerUuid", ORGANIZER_A.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void getEventsByOrganizer_unauthenticated_isDenied() throws Exception {
        mockMvc.perform(get("/events/by-organizer").param("organizerUuid", ORGANIZER_A.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void releaseAvailability_withoutInternalToken_returns401_andDoesNotMutate() throws Exception {
        Event saved = eventRepository.save(eventBuilder()
                .title("Concert")
                .startDateTime(LocalDateTime.now().plusDays(7))
                .endDateTime(LocalDateTime.now().plusDays(7).plusHours(2))
                .totalCapacity(100)
                .availableTickets(90)
                .build());

        mockMvc.perform(patch("/events/{id}/availability/release", saved.getEventId())
                        .param("count", "5"))
                .andExpect(status().isUnauthorized());

        Event reloaded = eventRepository.findById(saved.getEventId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(90, reloaded.getAvailableTickets());
    }

    @Test
    void releaseAvailability_withValidToken_returns200_andRestoresCapacity() throws Exception {
        // Simulate the state after a confirmed booking decremented availability:
        // total=100, available=90 (10 tickets currently held by a confirmed booking).
        Event saved = eventRepository.save(eventBuilder()
                .title("Concert")
                .startDateTime(LocalDateTime.now().plusDays(7))
                .endDateTime(LocalDateTime.now().plusDays(7).plusHours(2))
                .totalCapacity(100)
                .availableTickets(90)
                .build());

        mockMvc.perform(patch("/events/{id}/availability/release", saved.getEventId())
                        .param("count", "10")
                        .header("X-Internal-Token", VALID_INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is("200 OK")))
                .andExpect(jsonPath("$.data.availableTickets", is(100)));

        Event reloaded = eventRepository.findById(saved.getEventId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(100, reloaded.getAvailableTickets());
    }

    @Test
    void releaseAvailability_refusesToOverflowTotalCapacity() throws Exception {
        // Available is already at totalCapacity. A buggy / replayed release MUST NOT
        // push it higher — the SQL clamp rejects, the service throws, no mutation.
        Event saved = eventRepository.save(eventBuilder()
                .title("Concert")
                .startDateTime(LocalDateTime.now().plusDays(7))
                .endDateTime(LocalDateTime.now().plusDays(7).plusHours(2))
                .totalCapacity(100)
                .availableTickets(100)
                .build());

        // Typed-exception rollout: an over-cap release is now a state conflict
        // (409), not a malformed request (400). The endpoint is internal-only
        // (booking-service caller) so this isn't FE-visible; previous shape
        // came from GlobalExceptionHandler's generic RuntimeException fallback.
        mockMvc.perform(patch("/events/{id}/availability/release", saved.getEventId())
                        .param("count", "5")
                        .header("X-Internal-Token", VALID_INTERNAL_TOKEN))
                .andExpect(status().isConflict());

        Event reloaded = eventRepository.findById(saved.getEventId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(100, reloaded.getAvailableTickets());
    }

    // ------------------------------------------------------------------
    // TEAM_MEMBER scoping on the broad catalog endpoints (the broken-access-
    // control fix). Before the fix a TEAM_MEMBER matched neither the
    // organizer-only nor the admin branch and fell through to the PUBLIC
    // listing — seeing EVERY organizer's events. These pin that a team member
    // now sees ONLY the events their organizer explicitly assigned to them,
    // and that no assignments => empty page (deny-by-default).
    // ------------------------------------------------------------------

    // The organizer that "owns" the events and assigns a subset to its member.
    private static final UUID TEAM_ORGANIZER = UUID.randomUUID();
    // The team member's own userUuid (the assignment key, distinct from the
    // organizerUuid which is the parent-organizer ownership filter).
    private static final UUID TEAM_MEMBER_UUID = UUID.randomUUID();

    private Event saveTeamEvent(String title, boolean active, java.time.LocalDateTime start) {
        return eventRepository.save(Event.builder()
                .tenantUserUuid(TEAM_ORGANIZER)
                .title(title)
                .description("desc")
                .venue("Venue")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(start)
                .endDateTime(start.plusHours(2))
                .totalCapacity(100)
                .availableTickets(100)
                .deleted(false)
                .active(active)
                .build());
    }

    @Test
    void getAllEvents_asTeamMember_returnsOnlyAssignedEvents() throws Exception {
        Event assigned = saveTeamEvent("Assigned Concert", true, LocalDateTime.now().plusDays(5));
        Event other = saveTeamEvent("Unassigned Concert", true, LocalDateTime.now().plusDays(6));

        // Organizer assigned ONLY the first event to this team member.
        Mockito.when(userUuidLookupGateway.assignedEventIdsFor(TEAM_MEMBER_UUID))
                .thenReturn(List.of(assigned.getEventId()));

        mockMvc.perform(get("/events")
                        .with(jwtAuthWithUser("member-1", TEAM_ORGANIZER, TEAM_MEMBER_UUID, "TEAM_MEMBER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].eventId", is(assigned.getEventId().toString())))
                .andExpect(jsonPath("$.data.content[*].eventId",
                        not(hasItem(other.getEventId().toString()))));
    }

    @Test
    void getAllEvents_asTeamMember_withNoAssignments_returnsEmptyPage() throws Exception {
        // Two events exist, but the member is assigned to none → deny-by-default.
        saveTeamEvent("Concert A", true, LocalDateTime.now().plusDays(5));
        saveTeamEvent("Concert B", true, LocalDateTime.now().plusDays(6));
        Mockito.when(userUuidLookupGateway.assignedEventIdsFor(TEAM_MEMBER_UUID))
                .thenReturn(List.of());

        mockMvc.perform(get("/events")
                        .with(jwtAuthWithUser("member-1", TEAM_ORGANIZER, TEAM_MEMBER_UUID, "TEAM_MEMBER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    @Test
    void getActiveEvents_asTeamMember_returnsOnlyAssignedActiveEvents() throws Exception {
        Event assigned = saveTeamEvent("Assigned Active", true, LocalDateTime.now().plusDays(5));
        Event other = saveTeamEvent("Unassigned Active", true, LocalDateTime.now().plusDays(6));

        Mockito.when(userUuidLookupGateway.assignedEventIdsFor(TEAM_MEMBER_UUID))
                .thenReturn(List.of(assigned.getEventId()));

        mockMvc.perform(get("/events/active")
                        .with(jwtAuthWithUser("member-1", TEAM_ORGANIZER, TEAM_MEMBER_UUID, "TEAM_MEMBER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].eventId", is(assigned.getEventId().toString())))
                .andExpect(jsonPath("$.data.content[*].eventId",
                        not(hasItem(other.getEventId().toString()))));
    }

    @Test
    void getActiveEvents_asTeamMember_withNoAssignments_returnsEmptyPage() throws Exception {
        saveTeamEvent("Active A", true, LocalDateTime.now().plusDays(5));
        Mockito.when(userUuidLookupGateway.assignedEventIdsFor(TEAM_MEMBER_UUID))
                .thenReturn(List.of());

        mockMvc.perform(get("/events/active")
                        .with(jwtAuthWithUser("member-1", TEAM_ORGANIZER, TEAM_MEMBER_UUID, "TEAM_MEMBER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    @Test
    void getInactiveEvents_asTeamMember_returnsOnlyAssignedInactiveEvents() throws Exception {
        Event assignedInactive = saveTeamEvent("Assigned Inactive", false, LocalDateTime.now().plusDays(5));
        Event otherInactive = saveTeamEvent("Unassigned Inactive", false, LocalDateTime.now().plusDays(6));
        // An assigned but ACTIVE event must NOT appear in the inactive listing.
        Event assignedActive = saveTeamEvent("Assigned Active", true, LocalDateTime.now().plusDays(7));

        Mockito.when(userUuidLookupGateway.assignedEventIdsFor(TEAM_MEMBER_UUID))
                .thenReturn(List.of(assignedInactive.getEventId(), assignedActive.getEventId()));

        mockMvc.perform(get("/events/inactive")
                        .with(jwtAuthWithUser("member-1", TEAM_ORGANIZER, TEAM_MEMBER_UUID, "TEAM_MEMBER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].eventId", is(assignedInactive.getEventId().toString())))
                .andExpect(jsonPath("$.data.content[*].eventId",
                        not(hasItem(otherInactive.getEventId().toString()))));
    }

    @Test
    void getInactiveEvents_asTeamMember_withNoAssignments_returnsEmptyPage() throws Exception {
        saveTeamEvent("Inactive A", false, LocalDateTime.now().plusDays(5));
        Mockito.when(userUuidLookupGateway.assignedEventIdsFor(TEAM_MEMBER_UUID))
                .thenReturn(List.of());

        mockMvc.perform(get("/events/inactive")
                        .with(jwtAuthWithUser("member-1", TEAM_ORGANIZER, TEAM_MEMBER_UUID, "TEAM_MEMBER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    @Test
    void getAllEvents_asTeamMember_gatewayOutage_failsClosedToEmptyPage() throws Exception {
        // user-service down → the gateway returns an empty list (fail CLOSED).
        // The team member must see NO events, never fall through to the public
        // catalog (which would leak every organizer's events).
        saveTeamEvent("Concert A", true, LocalDateTime.now().plusDays(5));
        Mockito.when(userUuidLookupGateway.assignedEventIdsFor(TEAM_MEMBER_UUID))
                .thenReturn(List.of());

        mockMvc.perform(get("/events")
                        .with(jwtAuthWithUser("member-1", TEAM_ORGANIZER, TEAM_MEMBER_UUID, "TEAM_MEMBER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)));
    }
}
