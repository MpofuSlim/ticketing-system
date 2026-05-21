package com.innbucks.eventservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.eventservice.dto.CreateEventRequestDTO;
import com.innbucks.eventservice.dto.LocationDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.Province;
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

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:eventdb-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
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
                .province(Province.HRE)
                .dateTime(LocalDateTime.of(2030, 4, 18, 10, 30))
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
                .andExpect(jsonPath("$.data.content[0].dateTime", is("2030-04-18T10:30:00"))); // LocalDateTime in response
    }

    @Test
    void createEvent_requiresAuthentication() throws Exception {
        MockMultipartFile eventPart = new MockMultipartFile(
                "event", "event.json", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(sampleRequest("Concert", Province.BYO)));

        mockMvc.perform(multipart("/events").file(eventPart))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "tenant-99", roles = "EVENT_ORGANIZER")
    void createEvent_withTenantRole_createsEvent() throws Exception {
        MockMultipartFile eventPart = new MockMultipartFile(
                "event", "event.json", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(sampleRequest("Concert", Province.BYO)));

        mockMvc.perform(multipart("/events").file(eventPart))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code", is("201 CREATED")))
                .andExpect(jsonPath("$.data.tenantId", is("tenant-99")))
                .andExpect(jsonPath("$.data.title", is("Concert")))
                .andExpect(jsonPath("$.data.availableTickets", is(50)))
                .andExpect(jsonPath("$.data.location.latitude", is(-17.8252)))
                .andExpect(jsonPath("$.data.location.longitude", is(31.0335)))
                .andExpect(jsonPath("$.data.bannerUrl", nullValue()));
    }

    @Test
    @WithMockUser(username = "tenant-99", roles = "EVENT_ORGANIZER")
    void createEvent_withBanner_storesBytesAndReturnsBannerUrl() throws Exception {
        MockMultipartFile eventPart = new MockMultipartFile(
                "event", "event.json", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(sampleRequest("Concert With Banner", Province.HRE)));

        // ~200 KB payload — guards against the column-type regression where
        // Hibernate generated a 32 KB varbinary on H2 and truncated real images.
        byte[] pngBytes = new byte[200 * 1024];
        pngBytes[0] = (byte) 0x89; pngBytes[1] = 0x50; pngBytes[2] = 0x4E; pngBytes[3] = 0x47;
        pngBytes[4] = 0x0D; pngBytes[5] = 0x0A; pngBytes[6] = 0x1A; pngBytes[7] = 0x0A;
        MockMultipartFile bannerPart = new MockMultipartFile(
                "eventBanner", "banner.png", MediaType.IMAGE_PNG_VALUE, pngBytes);

        String body = mockMvc.perform(multipart("/events").file(eventPart).file(bannerPart))
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
                .province(Province.HRE)
                .dateTime(LocalDateTime.of(2030, 1, 1, 10, 0))
                .totalCapacity(100)
                .availableTickets(100)
                .deleted(false)
                .build());

        String body = "{\"title\":\"Updated Title\",\"venue\":\"New Venue\",\"dateTime\":\"2031-06-15T19:00:00.000Z\"}";

        mockMvc.perform(put("/events/" + saved.getEventId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title", is("Updated Title")))
                .andExpect(jsonPath("$.data.venue", is("New Venue")))
                .andExpect(jsonPath("$.data.dateTime", is("2031-06-15T19:00:00")));

        Event reloaded = eventRepository.findById(saved.getEventId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("Updated Title", reloaded.getTitle());
        org.junit.jupiter.api.Assertions.assertEquals("New Venue", reloaded.getVenue());
        org.junit.jupiter.api.Assertions.assertEquals(
                LocalDateTime.of(2031, 6, 15, 19, 0), reloaded.getDateTime());
    }

    private static CreateEventRequestDTO sampleRequest(String title, Province province) {
        CreateEventRequestDTO req = new CreateEventRequestDTO();
        req.setTitle(title);
        req.setDescription("desc");
        req.setVenue("Bulawayo");
        req.setProvince(province);
        req.setLocation(LocationDTO.builder().latitude(-17.8252).longitude(31.0335).build());
        req.setDateTime(LocalDateTime.now().plusDays(10));
        req.setTotalCapacity(50);
        return req;
    }

    @Test
    void getEventsByProvince_onlyReturnsActiveUpcomingEvents_numberedFromOne() throws Exception {
        eventRepository.save(eventBuilder()
                .title("Third")
                .province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(30))
                .build());
        eventRepository.save(eventBuilder()
                .title("First")
                .province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(10))
                .build());
        eventRepository.save(eventBuilder()
                .title("Second")
                .province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(20))
                .build());

        eventRepository.save(eventBuilder()
                .title("Past")
                .province(Province.HRE)
                .dateTime(LocalDateTime.now().minusDays(1))
                .build());

        eventRepository.save(eventBuilder()
                .title("Inactive")
                .province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(15))
                .active(false)
                .build());

        eventRepository.save(eventBuilder()
                .title("Other province")
                .province(Province.BYO)
                .dateTime(LocalDateTime.now().plusDays(5))
                .build());

        mockMvc.perform(get("/events/by-province")
                        .param("province", "HRE")
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

    private static Event.EventBuilder eventBuilder() {
        return Event.builder()
                .tenantId("tenant-1")
                .description("desc")
                .venue("Venue")
                .totalCapacity(100)
                .availableTickets(100)
                .deleted(false)
                .active(true);
    }

    // Test config inherits `innbucks.internal-api-token` default from
    // application.yaml: change-me-change-me-change-me-change-me.
    private static final String VALID_INTERNAL_TOKEN = "change-me-change-me-change-me-change-me";

    @Test
    void consumeAvailability_withoutInternalToken_returns401_andDoesNotMutate() throws Exception {
        Event saved = eventRepository.save(eventBuilder()
                .title("Concert")
                .province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(7))
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
                .province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(7))
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
                .province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(7))
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
}
