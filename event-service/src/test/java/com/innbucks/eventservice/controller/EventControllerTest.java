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
import java.util.UUID;

import static org.hamcrest.Matchers.*;
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
        mockMvc = webAppContextSetup(wac).build();
        eventRepository.deleteAll();
    }

    @Test
    void getAllEvents_isPublic_andReturnsLocalDateInDto() throws Exception {
        Event saved = eventRepository.save(Event.builder()
                .eventId(UUID.randomUUID())
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
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].eventId", is(saved.getEventId().toString())))
                .andExpect(jsonPath("$.content[0].dateTime", is("2030-04-18"))); // LocalDate in response
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
                .andExpect(jsonPath("$.agentId", is("agent-99")))
                .andExpect(jsonPath("$.title", is("Concert")))
                .andExpect(jsonPath("$.availableTickets", is(50)));
    }

    @Test
    void h2ConsoleRoot_redirectsToTrailingSlash() throws Exception {
        mockMvc.perform(get("/h2-console"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/h2-console/"));
    }
}
