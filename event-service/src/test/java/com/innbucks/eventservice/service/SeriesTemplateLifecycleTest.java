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
import com.innbucks.eventservice.dto.UpdateEventRequestDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.entity.SeriesState;
import com.innbucks.eventservice.exception.BadRequestException;
import com.innbucks.eventservice.mapper.EventMapper;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventSeriesRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A series is published, reviewed, unpublished, rejected and deleted through
 * its template with the ordinary event calls (TIC-70). The template itself
 * never goes live; its series does.
 */
class SeriesTemplateLifecycleTest {

    private static final UUID ORGANIZER = UUID.randomUUID();

    private final EventRepository repo = mock(EventRepository.class);
    private final EventSeriesRepository seriesRepo = mock(EventSeriesRepository.class);
    private final OrganizerNotificationGateway organizerNotices = mock(OrganizerNotificationGateway.class);
    private final StaffNotificationGateway staffNotices = mock(StaffNotificationGateway.class);
    private final BookingNotificationGateway bookingNotices = mock(BookingNotificationGateway.class);
    private final OrganizerGateway organizers = mock(OrganizerGateway.class);

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    private EventService service(boolean reviewRequired) {
        EventService service = new EventService(repo, new MarketTimeZone("ZW"), new EventMapper(),
                mock(SeatCategoryGateway.class), mock(BookingGateway.class), organizers,
                bookingNotices, organizerNotices);
        service.setReviewPolicy(new EventReviewPolicy(reviewRequired));
        service.setStaffNotificationGateway(staffNotices);
        service.setSeriesTemplates(new SeriesTemplates(seriesRepo));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(seriesRepo.save(any(EventSeries.class))).thenAnswer(inv -> inv.getArgument(0));
        when(organizers.organizersByUserUuids(any())).thenReturn(
                Map.of(ORGANIZER, OrganizerDTO.builder().businessName("Laugh Factory").build()));
        return service;
    }

    private EventSeries series(EventSeries.Status status) {
        EventSeries series = EventSeries.builder()
                .seriesId(UUID.randomUUID()).templateEventId(UUID.randomUUID()).tenantUserUuid(ORGANIZER)
                .status(status).daysOfWeek(List.of("FRIDAY")).startTimes(List.of("19:00")).durationMinutes(120)
                .startDate(LocalDate.of(2030, 1, 4)).bookingWindowDays(60).build();
        when(seriesRepo.findById(series.getSeriesId())).thenReturn(Optional.of(series));
        return series;
    }

    private Event template(EventSeries series) {
        Event event = Event.builder()
                .eventId(series.getTemplateEventId()).tenantUserUuid(ORGANIZER).title("Friday Comedy Night")
                .venue("Theatre in the Park").country("Zimbabwe").category(EventCategory.COMEDY)
                .startDateTime(LocalDateTime.of(2030, 1, 4, 17, 0))
                .endDateTime(LocalDateTime.of(2030, 1, 4, 19, 0))
                .totalCapacity(120).availableTickets(120).deleted(false).active(false)
                .seriesId(series.getSeriesId()).seriesState(SeriesState.TEMPLATE).build();
        when(repo.findByEventIdAndDeletedFalse(event.getEventId())).thenReturn(Optional.of(event));
        return event;
    }

    private static void signedInAsStaff() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "reviewer@innbucks.co.zw", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @Test
    void stepOff_publishingTheTemplate_putsTheSeriesLive_notTheTemplate() {
        EventService service = service(false);
        EventSeries series = series(EventSeries.Status.DRAFT);
        Event template = template(series);

        EventResponseDTO answer = service.activateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", template.getEventId());

        assertThat(template.isActive()).as("a template is never published").isFalse();
        assertThat(answer.isActive()).isFalse();
        assertThat(answer.getSeriesState()).isEqualTo(SeriesState.TEMPLATE);
        assertThat(answer.getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(series.getStatus()).isEqualTo(EventSeries.Status.ACTIVE);
        verify(organizerNotices).notifyEventActivated(ORGANIZER, template.getEventId(), "Friday Comedy Night", null);
    }

    @Test
    void stepOn_theOrganizerSubmits_andStaffApprovalPutsTheSeriesLive() {
        EventService service = service(true);
        EventSeries series = series(EventSeries.Status.DRAFT);
        Event template = template(series);

        service.activateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", template.getEventId());
        assertThat(ReviewStatus.of(template)).isEqualTo(ReviewStatus.SUBMITTED);
        assertThat(series.getStatus()).as("not live before the review").isEqualTo(EventSeries.Status.DRAFT);

        signedInAsStaff();
        service.approveEvent(template.getEventId());

        assertThat(template.isActive()).isFalse();
        assertThat(ReviewStatus.of(template)).isEqualTo(ReviewStatus.APPROVED);
        assertThat(template.getApprovedBy()).isEqualTo("reviewer@innbucks.co.zw");
        assertThat(series.getStatus()).isEqualTo(EventSeries.Status.ACTIVE);
    }

    @Test
    void unpublishingOrRejectingTheTemplate_stopsNewDates() {
        EventService service = service(false);
        EventSeries series = series(EventSeries.Status.ACTIVE);
        Event template = template(series);

        service.deactivateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", template.getEventId());
        assertThat(series.getStatus()).isEqualTo(EventSeries.Status.DRAFT);

        series.setStatus(EventSeries.Status.ACTIVE);
        signedInAsStaff();
        service.rejectEvent(template.getEventId(), "Not a real show");
        assertThat(series.getStatus()).isEqualTo(EventSeries.Status.DRAFT);
        assertThat(template.isRejected()).isTrue();
    }

    @Test
    void deletingTheTemplate_endsTheSeries_andTellsNoTicketHolder() {
        EventService service = service(false);
        EventSeries series = series(EventSeries.Status.ACTIVE);
        Event template = template(series);

        service.deleteEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", template.getEventId());

        assertThat(template.isDeleted()).isTrue();
        assertThat(series.getStatus()).isEqualTo(EventSeries.Status.ENDED);
        verify(bookingNotices, never()).notifyEventChange(any(), anyString(), any(), any(), any());
    }

    @Test
    void anEndedSeries_isNeverRevived() {
        EventService service = service(false);
        EventSeries series = series(EventSeries.Status.ENDED);
        Event template = template(series);
        template.setApprovedAt(LocalDateTime.of(2029, 12, 1, 8, 0));

        service.activateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", template.getEventId());

        assertThat(series.getStatus()).isEqualTo(EventSeries.Status.ENDED);
        assertThat(template.isActive()).isFalse();
    }

    @Test
    void aTemplatesTimes_comeFromTheSchedule() {
        EventService service = service(false);
        Event template = template(series(EventSeries.Status.DRAFT));
        UpdateEventRequestDTO times = new UpdateEventRequestDTO();
        times.setStartDateTime(LocalDateTime.of(2030, 2, 1, 19, 0));
        UpdateEventRequestDTO salesWindow = new UpdateEventRequestDTO();
        salesWindow.setSalesEndDateTime(null);

        assertThatThrownBy(() -> service.updateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", template.getEventId(), times))
                .isInstanceOf(BadRequestException.class).hasMessage(EventService.SERIES_TEMPLATE_TIMES);
        assertThatThrownBy(() -> service.updateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", template.getEventId(),
                salesWindow)).isInstanceOf(BadRequestException.class);

        UpdateEventRequestDTO title = new UpdateEventRequestDTO();
        title.setTitle("Friday Comedy Club");
        service.updateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", template.getEventId(), title);
        assertThat(template.getTitle()).isEqualTo("Friday Comedy Club");
    }

    @Test
    void anOrdinaryEvent_stillGoesLive() {
        EventService service = service(false);
        Event event = Event.builder()
                .eventId(UUID.randomUUID()).tenantUserUuid(ORGANIZER).title("One-off").venue("Hall")
                .country("Zimbabwe").category(EventCategory.COMEDY)
                .startDateTime(LocalDateTime.of(2030, 1, 4, 17, 0)).endDateTime(LocalDateTime.of(2030, 1, 4, 19, 0))
                .totalCapacity(10).availableTickets(10).deleted(false).active(false).build();
        when(repo.findByEventIdAndDeletedFalse(event.getEventId())).thenReturn(Optional.of(event));

        service.activateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", event.getEventId());

        assertThat(event.isActive()).isTrue();
        verify(seriesRepo, never()).save(any());
    }
}
