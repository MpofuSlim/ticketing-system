package com.innbucks.eventservice.service;

import com.innbucks.eventservice.config.MarketTimeZone;
import com.innbucks.eventservice.dto.CreateEventRequestDTO;
import com.innbucks.eventservice.dto.LocationDTO;
import com.innbucks.eventservice.dto.series.CreateSeriesRequestDTO;
import com.innbucks.eventservice.dto.series.EventSeriesResponseDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.entity.SeriesState;
import com.innbucks.eventservice.exception.BadRequestException;
import com.innbucks.eventservice.exception.ConflictException;
import com.innbucks.eventservice.exception.FieldErrorsException;
import com.innbucks.eventservice.exception.NotFoundException;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventSeriesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Setting up, reading and publishing a series (TIC-70). Tuesday 1 December
 * 2026, 10:00 in Harare (08:00 UTC).
 */
class EventSeriesServiceTest {

    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 12, 1, 8, 0);
    private static final UUID ORGANIZER = UUID.randomUUID();

    private final EventSeriesRepository seriesRepo = mock(EventSeriesRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final EventService eventService = mock(EventService.class);
    private EventSeriesService service;

    @BeforeEach
    void setUp() {
        service = service(true);
        when(seriesRepo.saveAndFlush(any(EventSeries.class))).thenAnswer(inv -> inv.getArgument(0));
        when(events.saveAndFlush(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(events.findUpcomingSeriesDates(any(), any(), any())).thenReturn(Page.empty());
        when(eventService.createSeriesTemplate(eq(ORGANIZER), eq("ZW"), any(CreateEventRequestDTO.class)))
                .thenAnswer(inv -> {
                    CreateEventRequestDTO e = inv.getArgument(2);
                    return Event.builder().eventId(UUID.randomUUID()).tenantUserUuid(ORGANIZER).title(e.getTitle())
                            .venue(e.getVenue()).active(false).deleted(false).build();
                });
    }

    private EventSeriesService service(boolean enabled) {
        EventSeriesService s = new EventSeriesService(seriesRepo, events, eventService, new MarketTimeZone("ZW"),
                new SeriesPolicy(enabled, 100, Duration.ofSeconds(25)));
        s.setClock(Clock.fixed(NOW_UTC.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        return s;
    }

    private static CreateSeriesRequestDTO request() {
        CreateSeriesRequestDTO r = new CreateSeriesRequestDTO();
        r.setTitle("Friday Comedy Night");
        r.setVenue("Theatre in the Park");
        r.setCategory(EventCategory.COMEDY);
        r.setLocation(LocationDTO.builder().latitude(-17.82).longitude(31.05).build());
        r.setTotalCapacity(120);
        r.setTermsText("1. No refunds.");
        r.setDaysOfWeek(List.of("FRIDAY"));
        r.setStartTimes(List.of("21:30", "19:00"));
        r.setDurationMinutes(120);
        r.setStartDate(LocalDate.of(2026, 12, 1));
        r.setSkipDates(List.of("2026-12-25"));
        r.setSalesOpenMinutesBefore(20160);
        r.setSalesCloseMinutesBefore(30);
        return r;
    }

    @Test
    void aNewSeries_isADraftWithATemplateAtItsFirstStart_andNoDates() {
        EventSeriesResponseDTO created = service.create(ORGANIZER, "ZW", request());

        ArgumentCaptor<CreateEventRequestDTO> event = ArgumentCaptor.forClass(CreateEventRequestDTO.class);
        verify(eventService).createSeriesTemplate(eq(ORGANIZER), eq("ZW"), event.capture());
        assertThat(event.getValue().getStartDateTime()).as("market-local, as createEvent takes it")
                .isEqualTo(LocalDateTime.of(2026, 12, 4, 19, 0));
        assertThat(event.getValue().getEndDateTime()).isEqualTo(LocalDateTime.of(2026, 12, 4, 21, 0));
        assertThat(event.getValue().getSalesStartDateTime()).isNull();
        assertThat(event.getValue().getTermsText()).isEqualTo("1. No refunds.");

        ArgumentCaptor<EventSeries> series = ArgumentCaptor.forClass(EventSeries.class);
        verify(seriesRepo).saveAndFlush(series.capture());
        EventSeries saved = series.getValue();
        assertThat(saved.getStatus()).isEqualTo(EventSeries.Status.DRAFT);
        assertThat(saved.getStartTimes()).containsExactly("19:00", "21:30");
        assertThat(saved.getBookingWindowDays()).as("the default").isEqualTo(60);
        assertThat(saved.getTenantUserUuid()).isEqualTo(ORGANIZER);

        ArgumentCaptor<Event> template = ArgumentCaptor.forClass(Event.class);
        verify(events).saveAndFlush(template.capture());
        assertThat(template.getValue().getSeriesState()).isEqualTo(SeriesState.TEMPLATE);
        assertThat(template.getValue().getSeriesId()).isEqualTo(saved.getSeriesId());
        assertThat(saved.getTemplateEventId()).isEqualTo(template.getValue().getEventId());

        // The template row reaches the database before the series that names it.
        InOrder order = inOrder(eventService, events, seriesRepo);
        order.verify(eventService).createSeriesTemplate(any(), any(), any());
        order.verify(events).flush();
        order.verify(seriesRepo).saveAndFlush(any());
        order.verify(events).saveAndFlush(any());

        assertThat(created.getStatus()).isEqualTo(EventSeries.Status.DRAFT);
        assertThat(created.getNextStarts()).as("UTC, rendered at the market offset on the wire")
                .startsWith(LocalDateTime.of(2026, 12, 4, 17, 0), LocalDateTime.of(2026, 12, 4, 19, 30));
        assertThat(created.getUpcomingDates()).isEmpty();
    }

    @Test
    void salesClosingBeforeTheyOpen_aPassedExtraStart_andTooManyDates_areRefused() {
        CreateSeriesRequestDTO r = request();
        r.setSalesOpenMinutesBefore(30);
        r.setSalesCloseMinutesBefore(30);
        r.setExtraStarts(List.of("2026-11-30T19:00"));
        r.setDaysOfWeek(List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"));
        r.setStartTimes(List.of("08:00", "10:00", "12:00", "14:00", "16:00"));
        r.setBookingWindowDays(120);

        assertThatThrownBy(() -> service.create(ORGANIZER, "ZW", r))
                .isInstanceOfSatisfying(FieldErrorsException.class, e -> assertThat(e.getErrors())
                        .containsOnlyKeys("salesCloseMinutesBefore", "extraStarts[0]", "bookingWindowDays")
                        .containsEntry("extraStarts[0]", "That start has passed."));
        verify(eventService, never()).createSeriesTemplate(any(), any(), any());
    }

    @Test
    void aScheduleWithNoDateFromNowOn_isRefused() {
        CreateSeriesRequestDTO r = request();
        r.setStartDate(LocalDate.of(2026, 1, 2));
        r.setEndDate(LocalDate.of(2026, 3, 27));

        assertThatThrownBy(() -> service.create(ORGANIZER, "ZW", r))
                .isInstanceOfSatisfying(FieldErrorsException.class, e -> assertThat(e.getErrors())
                        .containsOnlyKeys("startDate"));
    }

    @Test
    void staff_cannotSetUpASeries_forNoOrganizer() {
        assertThatThrownBy(() -> service.create(null, "ZW", request()))
                .isInstanceOf(BadRequestException.class).hasMessage(EventSeriesService.STAFF_CANNOT_CREATE);
    }

    @Test
    void switchedOff_everyCallIsA404() {
        EventSeriesService off = service(false);
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> off.create(ORGANIZER, "ZW", request())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> off.list(ORGANIZER, false, 0, 20)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> off.get(ORGANIZER, false, id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> off.publish(ORGANIZER, "ROLE_EVENT_ORGANIZER", false, id))
                .isInstanceOf(NotFoundException.class);
        verify(seriesRepo, never()).findById(any());
    }

    private EventSeries stored(UUID owner, EventSeries.Status status) {
        EventSeries series = EventSeries.builder()
                .seriesId(UUID.randomUUID()).templateEventId(UUID.randomUUID()).tenantUserUuid(owner)
                .status(status).daysOfWeek(List.of("FRIDAY")).startTimes(List.of("19:00")).durationMinutes(120)
                .startDate(LocalDate.of(2026, 12, 1)).bookingWindowDays(60).build();
        when(seriesRepo.findById(series.getSeriesId())).thenReturn(Optional.of(series));
        return series;
    }

    @Test
    void someoneElsesSeries_isTheSame404AsAMissingOne_butStaffReadIt() {
        EventSeries theirs = stored(UUID.randomUUID(), EventSeries.Status.ACTIVE);

        assertThatThrownBy(() -> service.get(ORGANIZER, false, theirs.getSeriesId()))
                .isInstanceOf(NotFoundException.class).hasMessage(EventSeriesService.SERIES_NOT_FOUND);
        assertThatThrownBy(() -> service.get(ORGANIZER, false, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class).hasMessage(EventSeriesService.SERIES_NOT_FOUND);
        assertThat(service.get(null, true, theirs.getSeriesId()).getSeriesId()).isEqualTo(theirs.getSeriesId());
    }

    @Test
    void theDetailView_listsTheUpcomingDates() {
        EventSeries mine = stored(ORGANIZER, EventSeries.Status.ACTIVE);
        Event date = Event.builder().eventId(UUID.randomUUID()).active(true).rejected(false)
                .startDateTime(LocalDateTime.of(2026, 12, 4, 17, 0)).endDateTime(LocalDateTime.of(2026, 12, 4, 19, 0))
                .seriesId(mine.getSeriesId()).seriesState(SeriesState.READY).build();
        when(events.findUpcomingSeriesDates(eq(mine.getSeriesId()), eq(NOW_UTC), any()))
                .thenReturn(new PageImpl<>(List.of(date)));
        when(events.findLastSeriesStart(mine.getSeriesId())).thenReturn(date.getStartDateTime());

        EventSeriesResponseDTO detail = service.get(ORGANIZER, false, mine.getSeriesId());

        assertThat(detail.getUpcomingDates()).singleElement().satisfies(d -> {
            assertThat(d.getEventId()).isEqualTo(date.getEventId());
            assertThat(d.isActive()).isTrue();
        });
        assertThat(detail.getUpcomingDateCount()).isEqualTo(1);
        assertThat(detail.getLastDateStart()).isEqualTo(date.getStartDateTime());
        assertThat(detail.getDatesInProgress()).isZero();
    }

    @Test
    void publish_isTheTemplatesActivate_andAnEndedSeriesIsRefused() {
        EventSeries mine = stored(ORGANIZER, EventSeries.Status.DRAFT);

        service.publish(ORGANIZER, "ROLE_EVENT_ORGANIZER", false, mine.getSeriesId());
        verify(eventService).activateEvent(ORGANIZER, "ROLE_EVENT_ORGANIZER", mine.getTemplateEventId());

        EventSeries ended = stored(ORGANIZER, EventSeries.Status.ENDED);
        assertThatThrownBy(() -> service.publish(ORGANIZER, "ROLE_EVENT_ORGANIZER", false, ended.getSeriesId()))
                .isInstanceOf(ConflictException.class).hasMessage(EventSeriesService.SERIES_ENDED);
        verify(eventService, never()).activateEvent(any(), anyString(), eq(ended.getTemplateEventId()));
    }
}
