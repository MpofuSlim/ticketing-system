package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.BookingGateway;
import com.innbucks.eventservice.client.BookingNotificationGateway;
import com.innbucks.eventservice.client.OrganizerGateway;
import com.innbucks.eventservice.client.OrganizerNotificationGateway;
import com.innbucks.eventservice.client.SeatCategoryGateway;
import com.innbucks.eventservice.client.StaffNotificationGateway;
import com.innbucks.eventservice.config.MarketTimeZone;
import com.innbucks.eventservice.dto.EventResponseDTO;
import com.innbucks.eventservice.dto.OrganizerDTO;
import com.innbucks.eventservice.dto.ReviewStatus;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.exception.BadRequestException;
import com.innbucks.eventservice.exception.ConflictException;
import com.innbucks.eventservice.mapper.EventMapper;
import com.innbucks.eventservice.repository.EventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * The event review step (V17, owner decisions 2026-11-03): behind a switch that
 * starts off; staff are told about every new event either way; an event
 * approved once is never reviewed again.
 */
class EventReviewTest {

    private static final UUID ORGANIZER = UUID.randomUUID();
    private static final String STAFF = "reviewer@innbucks.co.zw";

    private final EventRepository repo = mock(EventRepository.class);
    private final OrganizerNotificationGateway organizerNotices = mock(OrganizerNotificationGateway.class);
    private final StaffNotificationGateway staffNotices = mock(StaffNotificationGateway.class);
    private final OrganizerGateway organizers = mock(OrganizerGateway.class);

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    private EventService service(boolean reviewRequired) {
        EventService service = new EventService(repo, new MarketTimeZone("ZW"), new EventMapper(),
                mock(SeatCategoryGateway.class), mock(BookingGateway.class), organizers,
                mock(BookingNotificationGateway.class), organizerNotices);
        service.setReviewPolicy(new EventReviewPolicy(reviewRequired));
        service.setStaffNotificationGateway(staffNotices);
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(organizers.organizersByUserUuids(any())).thenReturn(
                Map.of(ORGANIZER, OrganizerDTO.builder().businessName("Harare Runners Club").build()));
        return service;
    }

    private Event draft() {
        Event event = Event.builder()
                .eventId(UUID.randomUUID()).tenantUserUuid(ORGANIZER).title("Pink Fun Run").venue("Harare Gardens")
                .country("Zimbabwe").category(EventCategory.FUN_RUN)
                .startDateTime(LocalDateTime.of(2030, 11, 28, 5, 0))
                .endDateTime(LocalDateTime.of(2030, 11, 28, 9, 0))
                .totalCapacity(500).availableTickets(500).deleted(false).active(false).build();
        when(repo.findByEventIdAndDeletedFalse(event.getEventId())).thenReturn(Optional.of(event));
        return event;
    }

    private static void signedInAsStaff() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                STAFF, null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    // ---- switch OFF: publish as before, staff are told ----------------------

    @Test
    void stepOff_theOrganizerPublishes_andTheStaffAreToldOnce() {
        EventService service = service(false);
        Event event = draft();

        EventResponseDTO live = service.activateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", event.getEventId());

        assertTrue(live.isActive());
        assertEquals(ReviewStatus.APPROVED, live.getReviewStatus());
        assertNotNull(event.getApprovedAt());
        assertNull(event.getApprovedBy(), "went live without a review");
        verify(organizerNotices).notifyEventActivated(ORGANIZER, event.getEventId(), "Pink Fun Run", null);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(staffNotices).notifyReviewStaff(eq(StaffNotificationGateway.EVENT_PUBLISHED), eq(event.getEventId()),
                eq("New event published: Pink Fun Run"), message.capture(), eq(ORGANIZER), eq("Harare Runners Club"));
        assertEquals("Harare Runners Club published \"Pink Fun Run\". It is live on Ticketize now. "
                + "It starts Thu 28 Nov 2030 at 07:00 at Harare Gardens. "
                + "If it should not be on sale, reject it with a reason the organizer will see.", message.getValue());

        // Unpublished and published again: the staff are not told twice.
        event.setActive(false);
        service.activateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", event.getEventId());
        verify(staffNotices, times(1)).notifyReviewStaff(any(), any(), any(), any(), any(), any());
    }

    @Test
    void stepOff_anEventLiveBeforeV17_isRepublishedWithoutTellingTheStaff() {
        EventService service = service(false);
        Event event = draft();
        event.setApprovedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(30)); // V17's backfill

        service.activateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", event.getEventId());

        assertTrue(event.isActive());
        verifyNoInteractions(staffNotices);
    }

    @Test
    void stepOff_rejectNeedsNoReason_andApproveOnlyClearsTheRejection() {
        EventService service = service(false);
        Event event = draft();
        event.setActive(true);
        event.setApprovedAt(LocalDateTime.now(ZoneOffset.UTC));

        EventResponseDTO rejected = service.rejectEvent(event.getEventId());
        assertEquals(ReviewStatus.REJECTED, rejected.getReviewStatus());
        assertFalse(event.isActive());
        assertNull(event.getApprovedAt(), "a rejection clears the approval");
        verify(organizerNotices).notifyEventRejected(ORGANIZER, event.getEventId(), "Pink Fun Run", null);

        signedInAsStaff();
        EventResponseDTO approved = service.approveEvent(event.getEventId());
        assertFalse(approved.isActive(), "with the step off, approve does not publish");
        assertEquals(ReviewStatus.APPROVED, approved.getReviewStatus());
        assertEquals(STAFF, event.getApprovedBy());
        verify(organizerNotices).notifyEventApproved(ORGANIZER, "Pink Fun Run");

        // The organizer publishes it again: staff just approved it, so they are not told.
        SecurityContextHolder.clearContext();
        service.activateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", event.getEventId());
        assertTrue(event.isActive());
        verifyNoInteractions(staffNotices);
    }

    // ---- switch ON: publish submits, staff approve -------------------------

    @Test
    void stepOn_theOrganizersPublishSubmits_onceOnly() {
        EventService service = service(true);
        Event event = draft();

        EventResponseDTO submitted = service.activateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", event.getEventId());

        assertFalse(submitted.isActive(), "not live until approved");
        assertEquals(ReviewStatus.SUBMITTED, submitted.getReviewStatus());
        assertNotNull(submitted.getSubmittedAt());
        verify(organizerNotices).notifyEventSubmitted(ORGANIZER, event.getEventId(), "Pink Fun Run");
        verify(organizerNotices, never()).notifyEventActivated(any(), any(), any(), any());
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(staffNotices).notifyReviewStaff(eq(StaffNotificationGateway.EVENT_SUBMITTED), eq(event.getEventId()),
                eq("Event awaiting approval: Pink Fun Run"), message.capture(), eq(ORGANIZER), eq("Harare Runners Club"));
        assertTrue(message.getValue().startsWith("Harare Runners Club submitted \"Pink Fun Run\" for review. "),
                message.getValue());

        service.activateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", event.getEventId());
        verify(staffNotices, times(1)).notifyReviewStaff(any(), any(), any(), any(), any(), any());
        verify(organizerNotices, times(1)).notifyEventSubmitted(any(), any(), any());
    }

    @Test
    void stepOn_staffApprove_putsItLive_andTellTheOrganizer() {
        EventService service = service(true);
        Event event = draft();
        event.setSubmittedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        signedInAsStaff();

        EventResponseDTO approved = service.approveEvent(event.getEventId());

        assertTrue(approved.isActive());
        assertEquals(ReviewStatus.APPROVED, approved.getReviewStatus());
        assertEquals(STAFF, event.getApprovedBy());
        verify(organizerNotices).notifyEventApprovedAndLive(ORGANIZER, event.getEventId(), "Pink Fun Run", null);
        verifyNoInteractions(staffNotices);
    }

    @Test
    void stepOn_staffPublishingASubmittedEvent_approvesIt() {
        EventService service = service(true);
        Event event = draft();
        event.setSubmittedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        signedInAsStaff();

        service.activateEvent(UUID.randomUUID(), "ROLE_SUPER_ADMIN", event.getEventId());

        assertTrue(event.isActive());
        assertEquals(STAFF, event.getApprovedBy());
        verify(organizerNotices).notifyEventApprovedAndLive(ORGANIZER, event.getEventId(), "Pink Fun Run", null);
    }

    @Test
    void stepOn_aDraftIsNeverPublishedOrApprovedByStaff() {
        EventService service = service(true);
        Event event = draft();
        signedInAsStaff();

        assertEquals(EventService.NOT_SUBMITTED_YET, assertThrows(ConflictException.class,
                () -> service.approveEvent(event.getEventId())).getMessage());
        assertEquals(EventService.NOT_SUBMITTED_YET, assertThrows(ConflictException.class,
                () -> service.activateEvent(UUID.randomUUID(), "ROLE_SUPER_ADMIN", event.getEventId())).getMessage());
        assertFalse(event.isActive());
        verify(repo, never()).save(any());
    }

    @Test
    void stepOn_approvingAnApprovedEvent_doesNotPutItBackLive() {
        EventService service = service(true);
        Event event = draft();
        event.setApprovedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(2)); // its organizer unpublished it
        signedInAsStaff();

        EventResponseDTO same = service.approveEvent(event.getEventId());

        assertFalse(same.isActive());
        verify(repo, never()).save(any());
        verifyNoInteractions(organizerNotices);
    }

    @Test
    void stepOn_rejectNeedsAReason_whichIsCleanedKeptAndSent() {
        EventService service = service(true);
        Event event = draft();
        event.setSubmittedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));

        assertEquals(EventService.REJECTION_REASON_REQUIRED, assertThrows(BadRequestException.class,
                () -> service.rejectEvent(event.getEventId(), "  <b> </b> ")).getMessage());
        verify(repo, never()).save(any());

        EventResponseDTO rejected = service.rejectEvent(event.getEventId(),
                "<b>The banner</b> shows another organizer's logo.");

        assertEquals(ReviewStatus.REJECTED, rejected.getReviewStatus());
        assertEquals("The banner shows another organizer's logo.", rejected.getRejectionReason());
        assertNull(event.getSubmittedAt());
        verify(organizerNotices).notifyEventRejected(ORGANIZER, event.getEventId(), "Pink Fun Run",
                "The banner shows another organizer's logo.");
    }

    @Test
    void stepOn_theOwnerPublishingARejectedEvent_resubmitsIt() {
        EventService service = service(true);
        Event event = draft();
        event.setRejected(true);
        event.setRejectionReason("Wrong logo");

        EventResponseDTO resubmitted = service.activateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", event.getEventId());

        assertEquals(ReviewStatus.SUBMITTED, resubmitted.getReviewStatus());
        assertFalse(resubmitted.isActive());
        assertNull(resubmitted.getRejectionReason());
        verify(staffNotices).notifyReviewStaff(eq(StaffNotificationGateway.EVENT_SUBMITTED), eq(event.getEventId()),
                anyString(), anyString(), eq(ORGANIZER), eq("Harare Runners Club"));
    }

    @Test
    void stepOn_anApprovedEvent_isRepublishedByItsOwner_withNoReview() {
        EventService service = service(true);
        Event event = draft();
        event.setApprovedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(2));
        event.setApprovedBy(STAFF);

        EventResponseDTO live = service.activateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", event.getEventId());

        assertTrue(live.isActive());
        verify(organizerNotices).notifyEventActivated(eq(ORGANIZER), eq(event.getEventId()), eq("Pink Fun Run"),
                isNull());
        verifyNoInteractions(staffNotices);
    }

    @Test
    void theOrganizerIsToldWhenSalesOpen_inMarketTime() {
        EventService service = service(false);
        Event event = draft();
        event.setSalesStartAt(LocalDateTime.of(2030, 11, 14, 8, 0)); // 10:00 in Harare

        service.activateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", event.getEventId());

        verify(organizerNotices).notifyEventActivated(ORGANIZER, event.getEventId(), "Pink Fun Run",
                "Ticket sales open on Thu 14 Nov 2030 at 10:00.");
    }

    @Test
    void thePublicNeverSeesTheReview() {
        EventService service = service(false);
        Event event = draft();
        event.setActive(true);
        event.setApprovedAt(LocalDateTime.now(ZoneOffset.UTC));
        event.setApprovedBy(STAFF);

        EventResponseDTO publicView = service.getEventById(event.getEventId());

        assertNull(publicView.getReviewStatus());
        assertNull(publicView.getApprovedAt());
        assertNull(publicView.getSubmittedAt());
        assertNull(publicView.getRejectionReason());

        signedInAsStaff();
        assertEquals(ReviewStatus.APPROVED, service.getEventById(event.getEventId()).getReviewStatus());
    }

    @Test
    void reviewStatus_isReadFromTheFlags() {
        Event event = Event.builder().build();
        assertEquals(ReviewStatus.DRAFT, ReviewStatus.of(event));
        event.setSubmittedAt(LocalDateTime.now(ZoneOffset.UTC));
        assertEquals(ReviewStatus.SUBMITTED, ReviewStatus.of(event));
        event.setApprovedAt(LocalDateTime.now(ZoneOffset.UTC));
        assertEquals(ReviewStatus.APPROVED, ReviewStatus.of(event));
        event.setRejected(true);
        assertEquals(ReviewStatus.REJECTED, ReviewStatus.of(event), "rejected wins");
    }
}
