package com.innbucks.eventservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.eventservice.dto.CreateEventRequestDTO;
import com.innbucks.eventservice.dto.LocationDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@ActiveProfiles("test")
class EventControllerTest {

    MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EventRepository eventRepository;
    @Autowired WebApplicationContext wac;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(wac).apply(springSecurity()).build();
        eventRepository.deleteAll();
    }

    @Test
    void getAllEvents_isPublic_andReturnsLocalDateTimeInDto() throws Exception {
        Event saved = eventRepository.save(Event.builder()
                .tenantId("tenant-1")
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
    @WithMockUser(username = "tenant-99", roles = "EVENT_ORGANIZER")
    void createEvent_withTenantRole_createsEvent() throws Exception {
        MockMultipartFile eventPart = new MockMultipartFile(
                "event", "event.json", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(sampleRequest("Concert", EventCategory.COMEDY)));

        // @WithMockUser bypasses JwtFilter, so the country normally stamped from
        // the token is injected here as the request attribute the controller reads.
        mockMvc.perform(multipart("/events").file(eventPart)
                        .requestAttr("jwtCountry", "Zimbabwe"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code", is("201 CREATED")))
                .andExpect(jsonPath("$.data.tenantId", is("tenant-99")))
                .andExpect(jsonPath("$.data.title", is("Concert")))
                .andExpect(jsonPath("$.data.country", is("Zimbabwe")))
                .andExpect(jsonPath("$.data.category", is("COMEDY")))
                .andExpect(jsonPath("$.data.availableTickets", is(50)))
                .andExpect(jsonPath("$.data.location.latitude", is(-17.8252)))
                .andExpect(jsonPath("$.data.location.longitude", is(31.0335)))
                .andExpect(jsonPath("$.data.bannerUrl", nullValue()));
    }

    @Test
    @WithMockUser(username = "tenant-99", roles = "EVENT_ORGANIZER")
    void createEvent_withoutCountryClaim_isRejected() throws Exception {
        MockMultipartFile eventPart = new MockMultipartFile(
                "event", "event.json", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(sampleRequest("No Country Concert", EventCategory.SPORT)));

        // No jwtCountry attribute → controller has no country to stamp → 400.
        mockMvc.perform(multipart("/events").file(eventPart))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsStringIgnoringCase("country")));
    }

    @Test
    @WithMockUser(username = "tenant-99", roles = "EVENT_ORGANIZER")
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
                        .requestAttr("jwtCountry", "Zimbabwe"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bannerUrl", containsString("/banner")))
                .andReturn().getResponse().getContentAsString();

        String eventId = objectMapper.readTree(body).path("data").path("eventId").asText();

        mockMvc.perform(get("/events/" + eventId + "/banner"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(pngBytes));

        mockMvc.perform(get("/events/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bannerUrl", containsString("/banner")));
    }

    @Test
    @WithMockUser(username = "tenant-1", roles = "EVENT_ORGANIZER")
    void updateEvent_persistsChangesToDatabase() throws Exception {
        Event saved = eventRepository.save(Event.builder()
                .tenantId("tenant-1")
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
                .tenantId("tenant-1")
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

    // ---- GET /events/by-tenant (SUPER_ADMIN-only listing of a given tenant's events) ----

    @Test
    @WithMockUser(username = "admin@innbucks.co.zw", roles = "SUPER_ADMIN")
    void getEventsByTenant_asSuperAdmin_returnsOnlyThatTenantsEvents() throws Exception {
        eventRepository.save(eventBuilder().tenantId("tenant-A").title("A Concert")
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2)).build());
        eventRepository.save(eventBuilder().tenantId("tenant-A").title("A Marathon")
                .startDateTime(LocalDateTime.now().plusDays(6))
                .endDateTime(LocalDateTime.now().plusDays(6).plusHours(2)).build());
        eventRepository.save(eventBuilder().tenantId("tenant-B").title("B Concert")
                .startDateTime(LocalDateTime.now().plusDays(7))
                .endDateTime(LocalDateTime.now().plusDays(7).plusHours(2)).build());

        mockMvc.perform(get("/events/by-tenant").param("tenantId", "tenant-A")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is("200 OK")))
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.content[*].tenantId", everyItem(is("tenant-A"))));
    }

    @Test
    @WithMockUser(username = "admin@innbucks.co.zw", roles = "SUPER_ADMIN")
    void getEventsByTenant_unknownTenant_returnsEmptyPage() throws Exception {
        eventRepository.save(eventBuilder().tenantId("tenant-A").title("A Concert")
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2)).build());

        mockMvc.perform(get("/events/by-tenant").param("tenantId", "ghost-tenant")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    @Test
    @WithMockUser(username = "admin@innbucks.co.zw", roles = "SUPER_ADMIN")
    void getEventsByTenant_blankTenantId_returns400() throws Exception {
        mockMvc.perform(get("/events/by-tenant").param("tenantId", "   ")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsStringIgnoringCase("tenantId")));
    }

    @Test
    @WithMockUser(username = "tenant-99", roles = "EVENT_ORGANIZER")
    void getEventsByTenant_asEventOrganizer_isForbidden() throws Exception {
        mockMvc.perform(get("/events/by-tenant").param("tenantId", "tenant-A")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void getEventsByTenant_unauthenticated_isDenied() throws Exception {
        mockMvc.perform(get("/events/by-tenant").param("tenantId", "tenant-A")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError());
    }

    // ---- PATCH /events/{id}/availability/release (audit #3 — restore consumed capacity) ----

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
}
