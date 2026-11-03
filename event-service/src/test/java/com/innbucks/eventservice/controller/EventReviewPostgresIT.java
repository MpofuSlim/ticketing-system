package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.client.OrganizerNotificationGateway;
import com.innbucks.eventservice.client.StaffNotificationGateway;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.repository.EventBookingQuestionRepository;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventTermsVersionRepository;
import com.innbucks.eventservice.security.AuthDetailsKeys;
import com.innbucks.eventservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The review step switched ON (V17), end to end against real Postgres and the
 * real security chain: the organizer's publish submits, the staff queue, the
 * refusals compared strictly with their Swagger examples, approve publishes,
 * and the public never sees the review fields.
 */
@TestPropertySource(properties = "events.review.required=true")
@Import(EventReviewPostgresIT.Notices.class)
class EventReviewPostgresIT extends PostgresIntegrationTestBase {

    @TestConfiguration
    static class Notices {
        @Bean
        @Primary
        StaffNotificationGateway mockStaffNotices() {
            return Mockito.mock(StaffNotificationGateway.class);
        }

        @Bean
        @Primary
        OrganizerNotificationGateway mockOrganizerNotices() {
            return Mockito.mock(OrganizerNotificationGateway.class);
        }
    }

    @Autowired WebApplicationContext wac;
    @Autowired EventRepository eventRepository;
    @Autowired EventTermsVersionRepository termsVersionRepository;
    @Autowired EventBookingQuestionRepository bookingQuestionRepository;
    @Autowired StaffNotificationGateway staffNotices;
    @Autowired OrganizerNotificationGateway organizerNotices;

    private MockMvc mvc;
    private final UUID organizer = UUID.randomUUID();
    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    @AfterEach
    void clean() {
        termsVersionRepository.deleteAll();
        bookingQuestionRepository.deleteAll();
        eventRepository.deleteAll();
        Mockito.reset(staffNotices, organizerNotices);
    }

    @BeforeEach
    void mockMvc() {
        mvc = webAppContextSetup(wac).apply(springSecurity()).build();
    }

    private Event draft(String title) {
        return eventRepository.save(Event.builder()
                .tenantUserUuid(organizer).title(title).venue("Harare Gardens").country("Zimbabwe")
                .category(EventCategory.FUN_RUN)
                .startDateTime(now.plusDays(20)).endDateTime(now.plusDays(20).plusHours(4))
                .totalCapacity(500).availableTickets(500).active(false).deleted(false).build());
    }

    private static RequestPostProcessor as(String principal, UUID organizerUuid, String... roles) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(principal, null,
                Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        Map<String, Object> details = new LinkedHashMap<>();
        if (organizerUuid != null) {
            details.put(AuthDetailsKeys.ORGANIZER_UUID, organizerUuid);
        }
        token.setDetails(details);
        return authentication(token);
    }

    private RequestPostProcessor theOrganizer() {
        return as("organizer@example.com", organizer, "EVENT_ORGANIZER");
    }

    private static RequestPostProcessor staff() {
        return as("reviewer@innbucks.co.zw", null, "SUPER_ADMIN");
    }

    @Test
    void theOrganizersPublish_submitsIt_andTheStaffAreTold() throws Exception {
        Event event = draft("Pink Fun Run");

        mvc.perform(put("/events/" + event.getEventId() + "/activate").with(theOrganizer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(EventController.SUBMITTED_FOR_REVIEW_MESSAGE))
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.reviewStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.submittedAt").exists());

        Event stored = eventRepository.findByEventIdAndDeletedFalse(event.getEventId()).orElseThrow();
        assertThat(stored.isActive()).isFalse();
        assertThat(stored.getSubmittedAt()).isNotNull();
        Mockito.verify(staffNotices).notifyReviewStaff(eq(StaffNotificationGateway.EVENT_SUBMITTED),
                eq(event.getEventId()), eq("Event awaiting approval: Pink Fun Run"), anyString(), eq(organizer), any());
        Mockito.verify(organizerNotices).notifyEventSubmitted(organizer, event.getEventId(), "Pink Fun Run");
    }

    @Test
    void theQueue_listsWhatAwaitsADecision_oldestFirst_forStaffOnly() throws Exception {
        Event later = draft("Later");
        later.setSubmittedAt(now.minusMinutes(5));
        eventRepository.save(later);
        Event earlier = draft("Earlier");
        earlier.setSubmittedAt(now.minusHours(2));
        eventRepository.save(earlier);
        draft("Still a draft");
        Event approved = draft("Approved");
        approved.setSubmittedAt(now.minusDays(1));
        approved.setApprovedAt(now.minusHours(20));
        eventRepository.save(approved);
        Event rejected = draft("Rejected");
        rejected.setRejected(true);
        eventRepository.save(rejected);

        mvc.perform(get("/events/review-queue").with(as("officer@innbucks.co.zw", null, "PRODUCT_OFFICER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Review queue retrieved"))
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.content[0].title").value("Earlier"))
                .andExpect(jsonPath("$.data.content[1].title").value("Later"))
                .andExpect(jsonPath("$.data.content[0].reviewStatus").value("SUBMITTED"));

        mvc.perform(get("/events/review-queue").with(theOrganizer())).andExpect(status().isForbidden());
        mvc.perform(get("/events/review-queue")).andExpect(status().isUnauthorized());
    }

    @Test
    void staffCannotApproveADraft_norRejectWithoutAReason() throws Exception {
        Event event = draft("Pink Fun Run");

        mvc.perform(put("/events/" + event.getEventId() + "/approve").with(staff()))
                .andExpect(status().isConflict())
                .andExpect(content().json(EventController.EXAMPLE_NOT_SUBMITTED_YET, JsonCompareMode.STRICT));
        mvc.perform(put("/events/" + event.getEventId() + "/reject").with(staff()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(EventController.EXAMPLE_REJECTION_REASON_REQUIRED, JsonCompareMode.STRICT));

        Event stored = eventRepository.findByEventIdAndDeletedFalse(event.getEventId()).orElseThrow();
        assertThat(stored.isRejected()).isFalse();
        assertThat(stored.getApprovedAt()).isNull();
    }

    @Test
    void approve_putsItLive_andThePublicSeesNoReviewFields() throws Exception {
        Event event = draft("Pink Fun Run");
        event.setSubmittedAt(now.minusHours(1));
        eventRepository.save(event);

        mvc.perform(put("/events/" + event.getEventId() + "/approve").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvedAt").exists());
        Event stored = eventRepository.findByEventIdAndDeletedFalse(event.getEventId()).orElseThrow();
        assertThat(stored.getApprovedBy()).isEqualTo("reviewer@innbucks.co.zw");
        Mockito.verify(organizerNotices).notifyEventApprovedAndLive(eq(organizer), eq(event.getEventId()),
                eq("Pink Fun Run"), any());

        mvc.perform(get("/events/" + event.getEventId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").doesNotExist())
                .andExpect(jsonPath("$.data.approvedAt").doesNotExist())
                .andExpect(jsonPath("$.data.rejectionReason").doesNotExist());
        mvc.perform(get("/events/" + event.getEventId()).with(theOrganizer()))
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"));
    }

    @Test
    void aRejection_carriesItsReason_andTheOrganizerResubmits() throws Exception {
        Event event = draft("Pink Fun Run");
        event.setSubmittedAt(now.minusHours(1));
        eventRepository.save(event);

        mvc.perform(put("/events/" + event.getEventId() + "/reject").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"<b>Please</b> upload your own banner.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectionReason").value("Please upload your own banner."));
        mvc.perform(get("/events/" + event.getEventId()).with(theOrganizer()))
                .andExpect(jsonPath("$.data.rejectionReason").value("Please upload your own banner."));

        mvc.perform(put("/events/" + event.getEventId() + "/activate").with(theOrganizer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(EventController.SUBMITTED_FOR_REVIEW_MESSAGE))
                .andExpect(jsonPath("$.data.reviewStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.rejected").value(false))
                .andExpect(jsonPath("$.data.rejectionReason").doesNotExist());
    }
}
