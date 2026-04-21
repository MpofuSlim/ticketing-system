package com.innbucks.eventservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.eventservice.dto.CreateEventRequestDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.Province;
import com.innbucks.eventservice.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:eventdb-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "eureka.client.enabled=false"
})
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
    void getAllEvents_isPublic_andReturnsLocalDateInDto() throws Exception {
        Event saved = eventRepository.save(Event.builder()
                .agentId("agent-1")
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
                .andExpect(jsonPath("$.data.content[0].dateTime", is("2030-04-18"))); // LocalDate in response
    }

    @Test
    void createEvent_requiresAuthentication() throws Exception {
        CreateEventRequestDTO req = new CreateEventRequestDTO();
        req.setTitle("Concert");
        req.setDescription("desc");
        req.setVenue("Bulawayo");
        req.setProvince(Province.BYO);
        req.setDateTime(LocalDateTime.now().plusDays(10));
        req.setTotalCapacity(50);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "agent-99", roles = "AGENT")
    void createEvent_withAgentRole_createsEvent() throws Exception {
        CreateEventRequestDTO req = new CreateEventRequestDTO();
        req.setTitle("Concert");
        req.setDescription("desc");
        req.setVenue("Bulawayo");
        req.setProvince(Province.BYO);
        req.setDateTime(LocalDateTime.now().plusDays(10));
        req.setTotalCapacity(50);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code", is("201 CREATED")))
                .andExpect(jsonPath("$.data.agentId", is("agent-99")))
                .andExpect(jsonPath("$.data.title", is("Concert")))
                .andExpect(jsonPath("$.data.availableTickets", is(50)));
    }

    @Test
    void h2ConsoleRoot_redirectsToTrailingSlash() throws Exception {
        mockMvc.perform(get("/h2-console"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/h2-console/"));
    }

    @Test
    void getEventsByProvince_onlyReturnsActiveUpcomingEvents_numberedFromOne() throws Exception {
        // 3 upcoming events in HRE — earliest first, expected as eventNo 1..3
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

        // Past event — must be filtered out
        eventRepository.save(eventBuilder()
                .title("Past")
                .province(Province.HRE)
                .dateTime(LocalDateTime.now().minusDays(1))
                .build());

        // Inactive upcoming event — must be filtered out
        eventRepository.save(eventBuilder()
                .title("Inactive")
                .province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(15))
                .active(false)
                .build());

        // Different province — must be filtered out
        eventRepository.save(eventBuilder()
                .title("Other province")
                .province(Province.BYO)
                .dateTime(LocalDateTime.now().plusDays(5))
                .build());

        mockMvc.perform(get("/events/by-province")
                        .param("province", "HRE")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].title", is("First")))
                .andExpect(jsonPath("$.content[0].eventNo", is(1)))
                .andExpect(jsonPath("$.content[1].title", is("Second")))
                .andExpect(jsonPath("$.content[1].eventNo", is(2)))
                .andExpect(jsonPath("$.content[2].title", is("Third")))
                .andExpect(jsonPath("$.content[2].eventNo", is(3)));
    }

    private static Event.EventBuilder eventBuilder() {
        return Event.builder()
                .agentId("agent-1")
                .description("desc")
                .venue("Venue")
                .totalCapacity(100)
                .availableTickets(100)
                .deleted(false)
                .active(true);
    }
}
