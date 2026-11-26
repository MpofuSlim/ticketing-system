package com.innbucks.eventservice.service;

import com.innbucks.eventservice.cache.PublicEventCatalog;
import com.innbucks.eventservice.client.BookingGateway;
import com.innbucks.eventservice.client.BookingNotificationGateway;
import com.innbucks.eventservice.client.SeatLayoutGateway;
import com.innbucks.eventservice.config.MarketTimeZone;
import com.innbucks.eventservice.dto.series.FutureDatesRequestDTO;
import com.innbucks.eventservice.dto.series.FuturePriceRequestDTO;
import com.innbucks.eventservice.dto.series.SeriesChangeDTO;
import com.innbucks.eventservice.dto.series.SeriesChangeDTO.DateChange;
import com.innbucks.eventservice.dto.series.SeriesChangeDTO.Outcome;
import com.innbucks.eventservice.dto.series.SeriesScheduleRequestDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventSalesWindowChange;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.entity.SeriesState;
import com.innbucks.eventservice.exception.BadRequestException;
import com.innbucks.eventservice.exception.ConflictException;
import com.innbucks.eventservice.exception.FieldErrorsException;
import com.innbucks.eventservice.exception.NotFoundException;
import com.innbucks.eventservice.exception.ServiceUnavailableException;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventSalesWindowChangeRepository;
import com.innbucks.eventservice.repository.EventSeriesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Changing a series after set-up (TIC-71). Tuesday 1 December 2026, 10:00 in
 * Harare (08:00 UTC); the series runs on Fridays at 19:00 and 21:30, so its
 * first dates are Friday 4 December at 17:00 and 19:30 UTC.
 */
class SeriesEditServiceTest {

    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 12, 1, 8, 0);
    private static final UUID ORGANIZER = UUID.randomUUID();
    private static final UUID SERIES = UUID.randomUUID();
    private static final UUID TEMPLATE = UUID.randomUUID();

    private final EventSeriesRepository seriesRepo = mock(EventSeriesRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final BookingGateway bookings = mock(BookingGateway.class);
    private final BookingNotificationGateway notices = mock(BookingNotificationGateway.class);
    private final SeatLayoutGateway seats = mock(SeatLayoutGateway.class);
    private final EventSalesWindowChangeRepository windowChanges = mock(EventSalesWindowChangeRepository.class);
    private final Map<UUID, Event> store = new LinkedHashMap<>();
    private final List<EventSalesWindowChange> recorded = new ArrayList<>();
    private EventSeries series;
    private SeriesPauses pauses;
    private SeriesEditService service;

    @BeforeEach
    void setUp() {
        series = EventSeries.builder()
                .seriesId(SERIES).templateEventId(TEMPLATE).tenantUserUuid(ORGANIZER)
                .status(EventSeries.Status.ACTIVE)
                .daysOfWeek(new ArrayList<>(List.of("FRIDAY")))
                .startTimes(new ArrayList<>(List.of("19:00", "21:30")))
                .skipDates(new ArrayList<>()).extraStarts(new ArrayList<>())
                .durationMinutes(120).startDate(LocalDate.of(2026, 12, 1)).bookingWindowDays(60)
                .salesOpenMinutesBefore(20160).salesCloseMinutesBefore(30)
                .version(3L)
                .build();
        when(seriesRepo.findById(SERIES)).thenAnswer(inv -> Optional.of(series));
        when(seriesRepo.save(any(EventSeries.class))).thenAnswer(inv -> inv.getArgument(0));

        store.put(TEMPLATE, Event.builder().eventId(TEMPLATE).tenantUserUuid(ORGANIZER).title("Friday Comedy Night")
                .venue("Theatre in the Park").startDateTime(utc(4, 17, 0)).endDateTime(utc(4, 19, 0))
                .active(false).deleted(false).rejected(false)
                .seriesId(SERIES).seriesState(SeriesState.TEMPLATE).build());
        when(events.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(events.lockById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(events.save(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            store.put(e.getEventId(), e);
            return e;
        });
        when(events.saveAll(anyIterable())).thenAnswer(inv -> inv.getArgument(0));
        when(events.findUpcomingSeriesDates(any(), any(), any())).thenReturn(Page.empty());
        when(events.findFutureSeriesDates(eq(SERIES), any())).thenAnswer(inv ->
                dates(e -> !e.isDeleted() && e.getStartDateTime().isAfter(inv.getArgument(1))));
        when(events.findFutureSeriesDatesWithDeleted(eq(SERIES), any())).thenAnswer(inv ->
                dates(e -> e.getStartDateTime().isAfter(inv.getArgument(1))));
        when(events.findSeriesStartsFrom(eq(SERIES), any())).thenAnswer(inv ->
                dates(e -> !e.getStartDateTime().isBefore(inv.getArgument(1))).stream()
                        .map(Event::getStartDateTime).toList());
        when(events.findSeriesDatesToPause(eq(SERIES), any())).thenAnswer(inv ->
                dates(e -> !e.isDeleted() && e.getEndDateTime().isAfter(inv.getArgument(1))
                        && e.getSeriesPausedAt() == null));
        when(events.findSeriesPausedDates(SERIES)).thenAnswer(inv ->
                dates(e -> !e.isDeleted() && e.getSeriesPausedAt() != null));
        when(windowChanges.save(any(EventSalesWindowChange.class))).thenAnswer(inv -> {
            recorded.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        MarketTimeZone zw = new MarketTimeZone("ZW");
        Clock clock = Clock.fixed(NOW_UTC.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        EventSeriesService seriesService = new EventSeriesService(seriesRepo, events, mock(EventService.class), zw,
                new SeriesPolicy(true, 100, Duration.ofSeconds(25)));
        seriesService.setClock(clock);
        pauses = new SeriesPauses(events, windowChanges);
        service = new SeriesEditService(seriesService, seriesRepo, events, pauses, bookings, notices, seats, zw,
                mock(PublicEventCatalog.class), windowChanges, null);
        service.setClock(clock);
    }

    /** The series' dates in the store matching {@code filter}, soonest first, the template left out. */
    private List<Event> dates(Predicate<Event> filter) {
        return store.values().stream()
                .filter(e -> SERIES.equals(e.getSeriesId()) && !SeriesState.isTemplate(e))
                .filter(filter)
                .sorted(Comparator.comparing(Event::getStartDateTime))
                .toList();
    }

    private static LocalDateTime utc(int decemberDay, int hour, int minute) {
        return LocalDateTime.of(2026, 12, decemberDay, hour, minute);
    }

    /** A finished, published date of the series starting at {@code startUtc}, two hours long. */
    private Event date(LocalDateTime startUtc) {
        Event date = Event.builder().eventId(UUID.randomUUID()).tenantUserUuid(ORGANIZER)
                .title("Friday Comedy Night").venue("Theatre in the Park")
                .startDateTime(startUtc).endDateTime(startUtc.plusHours(2))
                .salesStartAt(startUtc.minusDays(14)).salesEndAt(startUtc.minusMinutes(30))
                .active(true).deleted(false).rejected(false)
                .seriesId(SERIES).seriesState(SeriesState.READY).build();
        store.put(date.getEventId(), date);
        return date;
    }

    private void bookingsAre(Map<UUID, Long> counts) {
        when(bookings.activeCountsIfKnown(anyCollection())).thenReturn(Optional.of(counts));
    }

    private static SeriesScheduleRequestDTO schedule(List<String> days, List<String> times) {
        SeriesScheduleRequestDTO r = new SeriesScheduleRequestDTO();
        r.setDaysOfWeek(days);
        r.setStartTimes(times);
        r.setDurationMinutes(120);
        r.setStartDate(LocalDate.of(2026, 12, 1));
        r.setBookingWindowDays(60);
        r.setSalesOpenMinutesBefore(20160);
        r.setSalesCloseMinutesBefore(30);
        return r;
    }

    private static DateChange row(SeriesChangeDTO result, Event date) {
        return result.getDates().stream().filter(c -> c.getEventId().equals(date.getEventId())).findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + date.getStartDateTime()));
    }

    // ---- The schedule ---------------------------------------------------------

    @Test
    void movingTheSeriesToSaturdays_removesTheUnbookedFridays_keepsTheBookedOne_andDetachesTheRemoved() {
        Event booked = date(utc(4, 17, 0));
        Event late = date(utc(4, 19, 30));
        Event next = date(utc(11, 17, 0));
        bookingsAre(Map.of(booked.getEventId(), 3L));

        SeriesChangeDTO result = service.updateSchedule(ORGANIZER, false, SERIES,
                schedule(List.of("SATURDAY"), List.of("19:00")));

        assertThat(row(result, booked).getOutcome()).isEqualTo(Outcome.KEPT_HAS_BOOKINGS);
        assertThat(row(result, booked).getActiveBookings()).isEqualTo(3L);
        assertThat(booked.isActive()).as("back on sale as it was").isTrue();
        assertThat(booked.isDeleted()).isFalse();
        assertThat(booked.getSeriesId()).isEqualTo(SERIES);

        for (Event removed : List.of(late, next)) {
            assertThat(row(result, removed).getOutcome()).isEqualTo(Outcome.REMOVED);
            assertThat(removed.isDeleted()).isTrue();
            assertThat(removed.isActive()).isFalse();
            assertThat(removed.getSeriesId()).as("its start stays free for a later schedule").isNull();
            assertThat(removed.getSeriesState()).isNull();
        }
        assertThat(series.getDaysOfWeek()).containsExactly("SATURDAY");
        assertThat(series.getStartTimes()).containsExactly("19:00");
        assertThat(store.get(TEMPLATE).getStartDateTime()).as("the new first start, Saturday 5 Dec 19:00 local")
                .isEqualTo(utc(5, 17, 0));
    }

    @Test
    void aScheduleThatStillHasEveryDate_removesNothing_andAsksNoCounts() {
        date(utc(4, 17, 0));

        SeriesChangeDTO result = service.updateSchedule(ORGANIZER, false, SERIES,
                schedule(List.of("FRIDAY"), List.of("19:00", "21:30")));

        assertThat(result.getDates()).isEmpty();
        verify(bookings, never()).activeCountsIfKnown(anyCollection());
    }

    @Test
    void whenTheBookingsCantBeCounted_nothingChanges_andTheDatesGoBackOnSale() {
        Event late = date(utc(4, 19, 30));
        when(bookings.activeCountsIfKnown(anyCollection())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSchedule(ORGANIZER, false, SERIES,
                schedule(List.of("FRIDAY"), List.of("19:00"))))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage(SeriesEditService.COUNTS_UNAVAILABLE);

        assertThat(late.isActive()).isTrue();
        assertThat(late.isDeleted()).isFalse();
        assertThat(series.getStartTimes()).containsExactly("19:00", "21:30");
    }

    @Test
    void aSeriesChangedMeanwhile_isAConflict_andTheDatesGoBack() {
        Event late = date(utc(4, 19, 30));
        when(bookings.activeCountsIfKnown(anyCollection())).thenAnswer(inv -> {
            series.setVersion(4L);
            return Optional.of(Map.of());
        });

        assertThatThrownBy(() -> service.updateSchedule(ORGANIZER, false, SERIES,
                schedule(List.of("FRIDAY"), List.of("19:00"))))
                .isInstanceOf(ConflictException.class)
                .hasMessage(SeriesEditService.SERIES_CHANGED);

        assertThat(late.isActive()).isTrue();
        assertThat(late.isDeleted()).isFalse();
    }

    @Test
    void aPassedExtraTheSeriesHad_canBeSentBack_butANewPassedOneCant() {
        series.setExtraStarts(new ArrayList<>(List.of("2026-11-27T15:00")));
        SeriesScheduleRequestDTO keep = schedule(List.of("FRIDAY"), List.of("19:00", "21:30"));
        keep.setExtraStarts(List.of("2026-11-27T15:00"));
        service.updateSchedule(ORGANIZER, false, SERIES, keep);

        SeriesScheduleRequestDTO added = schedule(List.of("FRIDAY"), List.of("19:00", "21:30"));
        added.setExtraStarts(List.of("2026-11-28T15:00"));
        assertThatThrownBy(() -> service.updateSchedule(ORGANIZER, false, SERIES, added))
                .isInstanceOfSatisfying(FieldErrorsException.class, e ->
                        assertThat(e.getErrors()).containsEntry("extraStarts[0]", "That start has passed."));
    }

    @Test
    void anEndedSeries_andSomeoneElsesSeries_areRefused() {
        assertThatThrownBy(() -> service.updateSchedule(UUID.randomUUID(), false, SERIES,
                schedule(List.of("FRIDAY"), List.of("19:00"))))
                .isInstanceOf(NotFoundException.class);
        series.setStatus(EventSeries.Status.ENDED);
        assertThatThrownBy(() -> service.updateSchedule(ORGANIZER, false, SERIES,
                schedule(List.of("FRIDAY"), List.of("19:00"))))
                .isInstanceOf(ConflictException.class)
                .hasMessage(SeriesEditService.SERIES_ENDED);
    }

    // ---- Every future date ------------------------------------------------------

    @Test
    void moving1900To1930_movesTheRegularDates_notAnExtraStart_notATakenSlot_andMessagesOnlyTheBooked() {
        Event booked = date(utc(4, 17, 0));
        Event unbooked = date(utc(11, 17, 0));
        Event blocked = date(utc(18, 17, 0));
        Event alreadyThere = date(utc(18, 17, 30));
        series.setExtraStarts(new ArrayList<>(List.of("2026-12-24T19:00")));
        Event extra = date(utc(24, 17, 0));
        Event skipped = date(utc(25, 17, 0));
        skipped.setDeleted(true);
        skipped.setActive(false);
        bookingsAre(Map.of(booked.getEventId(), 2L));

        FutureDatesRequestDTO request = new FutureDatesRequestDTO();
        FutureDatesRequestDTO.MoveStartTime move = new FutureDatesRequestDTO.MoveStartTime();
        move.setFrom("19:00");
        move.setTo("19:30");
        request.setMoveStartTime(move);
        SeriesChangeDTO result = service.updateFutureDates(ORGANIZER, false, SERIES, request);

        assertThat(row(result, booked).getOutcome()).isEqualTo(Outcome.MOVED);
        assertThat(row(result, booked).getActiveBookings()).isEqualTo(2L);
        assertThat(booked.getStartDateTime()).isEqualTo(utc(4, 17, 30));
        assertThat(booked.getEndDateTime()).as("its length kept").isEqualTo(utc(4, 19, 30));
        assertThat(booked.getSalesStartAt()).isEqualTo(utc(4, 17, 30).minusDays(14));
        assertThat(booked.getSalesEndAt()).isEqualTo(utc(4, 17, 0));
        assertThat(row(result, unbooked).getOutcome()).isEqualTo(Outcome.MOVED);
        assertThat(row(result, unbooked).getActiveBookings()).isZero();
        assertThat(row(result, blocked).getOutcome()).isEqualTo(Outcome.NOT_MOVED_TAKEN);
        assertThat(blocked.getStartDateTime()).isEqualTo(utc(18, 17, 0));
        assertThat(result.getDates()).as("an extra start and a 21:30-equivalent date are not touched")
                .noneMatch(c -> c.getEventId().equals(extra.getEventId())
                        || c.getEventId().equals(alreadyThere.getEventId()));
        assertThat(extra.getStartDateTime()).isEqualTo(utc(24, 17, 0));
        assertThat(skipped.getStartDateTime()).as("a skipped date moves too, so it keeps its start blocked")
                .isEqualTo(utc(25, 17, 30));
        assertThat(result.getDates()).noneMatch(c -> c.getEventId().equals(skipped.getEventId()));

        verify(notices).notifyEventChange(eq(booked.getEventId()), eq(BookingNotificationGateway.CHANGE_UPDATED),
                eq("Friday Comedy Night"), eq("2026-12-04T17:30"), isNull());
        verify(notices, times(1)).notifyEventChange(any(), anyString(), anyString(), any(), any());
        assertThat(series.getStartTimes()).containsExactly("19:30", "21:30");
        assertThat(recorded).as("each moved live date's sales window is on its record").hasSize(2);
    }

    @Test
    void aNewVenue_reachesTheTemplateAndEveryDate_andWhenCountsAreUnknownEveryDateIsMessaged() {
        Event first = date(utc(4, 17, 0));
        Event second = date(utc(4, 19, 30));
        when(bookings.activeCountsIfKnown(anyCollection())).thenReturn(Optional.empty());

        FutureDatesRequestDTO request = new FutureDatesRequestDTO();
        request.setVenue("Reps Theatre, Harare");
        SeriesChangeDTO result = service.updateFutureDates(ORGANIZER, false, SERIES, request);

        assertThat(result.getDates()).extracting(DateChange::getOutcome).containsOnly(Outcome.UPDATED);
        assertThat(store.get(TEMPLATE).getVenue()).isEqualTo("Reps Theatre, Harare");
        assertThat(first.getVenue()).isEqualTo("Reps Theatre, Harare");
        assertThat(second.getVenue()).isEqualTo("Reps Theatre, Harare");
        verify(notices).notifyEventChange(eq(first.getEventId()), anyString(), anyString(), isNull(),
                eq("Reps Theatre, Harare"));
        verify(notices).notifyEventChange(eq(second.getEventId()), anyString(), anyString(), isNull(),
                eq("Reps Theatre, Harare"));
    }

    @Test
    void aTitleChange_messagesNobody() {
        date(utc(4, 17, 0));
        FutureDatesRequestDTO request = new FutureDatesRequestDTO();
        request.setTitle("Friday Comedy: Live");

        service.updateFutureDates(ORGANIZER, false, SERIES, request);

        verify(notices, never()).notifyEventChange(any(), anyString(), anyString(), any(), any());
        verify(bookings, never()).activeCountsIfKnown(anyCollection());
    }

    @Test
    void nothingToChange_orABadMove_isRefusedBeforeAnythingIsRead() {
        assertThatThrownBy(() -> service.updateFutureDates(ORGANIZER, false, SERIES, new FutureDatesRequestDTO()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(SeriesEditService.NOTHING_TO_CHANGE);

        FutureDatesRequestDTO request = new FutureDatesRequestDTO();
        FutureDatesRequestDTO.MoveStartTime move = new FutureDatesRequestDTO.MoveStartTime();
        move.setFrom("18:00");
        move.setTo("21:30");
        request.setMoveStartTime(move);
        assertThatThrownBy(() -> service.updateFutureDates(ORGANIZER, false, SERIES, request))
                .isInstanceOfSatisfying(FieldErrorsException.class, e -> assertThat(e.getErrors())
                        .containsEntry("moveStartTime.from", "The schedule has no start at 18:00.")
                        .containsEntry("moveStartTime.to", "The schedule already has a start at 21:30."));
        verify(events, never()).findFutureSeriesDates(any(), any());
    }

    // ---- Skipping one date ------------------------------------------------------

    @Test
    void skippingAnUnbookedDate_removesIt_butKeepsItInTheSeries() {
        Event date = date(utc(11, 17, 0));
        bookingsAre(Map.of());

        SeriesChangeDTO result = service.skipDate(ORGANIZER, false, SERIES, date.getEventId());

        assertThat(row(result, date).getOutcome()).isEqualTo(Outcome.REMOVED);
        assertThat(date.isDeleted()).isTrue();
        assertThat(date.getSeriesId()).as("so the generator never makes it again").isEqualTo(SERIES);
    }

    @Test
    void skippingABookedDate_isRefused_andTheDateIsBackOnSale() {
        Event date = date(utc(11, 17, 0));
        bookingsAre(Map.of(date.getEventId(), 1L));

        assertThatThrownBy(() -> service.skipDate(ORGANIZER, false, SERIES, date.getEventId()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("People hold tickets for this date (1 active booking), so it can't be skipped. Change "
                        + "the date instead, or contact them first.");
        assertThat(date.isActive()).isTrue();
        assertThat(date.isDeleted()).isFalse();
    }

    @Test
    void skipping_aStartedDate_isAConflict_andAnotherSeriesDate_isNotFound() {
        Event started = date(NOW_UTC.minusMinutes(10));
        assertThatThrownBy(() -> service.skipDate(ORGANIZER, false, SERIES, started.getEventId()))
                .isInstanceOf(ConflictException.class)
                .hasMessage(SeriesEditService.DATE_STARTED);

        Event other = date(utc(11, 17, 0));
        other.setSeriesId(UUID.randomUUID());
        assertThatThrownBy(() -> service.skipDate(ORGANIZER, false, SERIES, other.getEventId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(SeriesEditService.DATE_NOT_FOUND);
    }

    // ---- Pause, resume, end -----------------------------------------------------

    @Test
    void pausing_closesEverySaleNow_andResuming_putsEachDatesOwnEndBack() {
        Event first = date(utc(4, 17, 0));
        Event noEnd = date(utc(11, 17, 0));
        noEnd.setSalesEndAt(null);

        SeriesChangeDTO paused = service.pause(ORGANIZER, false, SERIES);

        assertThat(series.getStatus()).isEqualTo(EventSeries.Status.PAUSED);
        assertThat(paused.getDates()).extracting(DateChange::getOutcome).containsOnly(Outcome.SALES_PAUSED);
        assertThat(first.getSalesEndAt()).isEqualTo(NOW_UTC);
        assertThat(first.getSeriesPausedSalesEndAt()).isEqualTo(utc(4, 16, 30));
        assertThat(noEnd.getSalesEndAt()).isEqualTo(NOW_UTC);
        assertThat(noEnd.getSeriesPausedSalesEndAt()).isNull();
        assertThat(first.isActive()).as("still listed").isTrue();
        assertThat(recorded).hasSize(2);

        SeriesChangeDTO resumed = service.resume(ORGANIZER, false, SERIES);

        assertThat(series.getStatus()).isEqualTo(EventSeries.Status.ACTIVE);
        assertThat(resumed.getDates()).extracting(DateChange::getOutcome).containsOnly(Outcome.SALES_RESUMED);
        assertThat(first.getSalesEndAt()).isEqualTo(utc(4, 16, 30));
        assertThat(first.getSeriesPausedAt()).isNull();
        assertThat(noEnd.getSalesEndAt()).isNull();
        assertThat(recorded).hasSize(4);
    }

    @Test
    void onlyALiveSeriesPauses_onlyAPausedOneResumes_andARejectedOneWaitsForReview() {
        series.setStatus(EventSeries.Status.DRAFT);
        assertThatThrownBy(() -> service.pause(ORGANIZER, false, SERIES))
                .isInstanceOf(ConflictException.class).hasMessage(SeriesEditService.NOT_LIVE);
        assertThatThrownBy(() -> service.resume(ORGANIZER, false, SERIES))
                .isInstanceOf(ConflictException.class).hasMessage(SeriesEditService.NOT_PAUSED);

        series.setStatus(EventSeries.Status.PAUSED);
        assertThatThrownBy(() -> service.pause(ORGANIZER, false, SERIES))
                .isInstanceOf(ConflictException.class).hasMessage(SeriesEditService.ALREADY_PAUSED);
        store.get(TEMPLATE).setRejected(true);
        assertThatThrownBy(() -> service.resume(ORGANIZER, false, SERIES))
                .isInstanceOf(ConflictException.class).hasMessage(SeriesEditService.REJECTED);
    }

    @Test
    void aPausedSeries_staysPausedThroughItsTemplatesPublishAndUnpublish_andEndingItReopensTheDates() {
        Event first = date(utc(4, 17, 0));
        service.pause(ORGANIZER, false, SERIES);
        SeriesTemplates templates = new SeriesTemplates(seriesRepo, pauses);

        templates.published(store.get(TEMPLATE));
        templates.unpublished(store.get(TEMPLATE));
        assertThat(series.getStatus()).isEqualTo(EventSeries.Status.PAUSED);
        assertThat(first.getSalesEndAt()).isEqualTo(NOW_UTC);

        templates.ended(store.get(TEMPLATE));
        assertThat(series.getStatus()).isEqualTo(EventSeries.Status.ENDED);
        assertThat(first.getSalesEndAt()).isEqualTo(utc(4, 16, 30));
        assertThat(first.getSeriesPausedAt()).isNull();
    }

    @Test
    void ending_removesTheUnbookedFutureDates_keepsTheBookedOnes_andEndsTheSeries() {
        Event booked = date(utc(4, 17, 0));
        Event unbooked = date(utc(11, 17, 0));
        service.pause(ORGANIZER, false, SERIES);
        bookingsAre(Map.of(booked.getEventId(), 5L));

        SeriesChangeDTO result = service.end(ORGANIZER, false, SERIES);

        assertThat(series.getStatus()).isEqualTo(EventSeries.Status.ENDED);
        assertThat(store.get(TEMPLATE).isDeleted()).isTrue();
        assertThat(row(result, booked).getOutcome()).isEqualTo(Outcome.KEPT_HAS_BOOKINGS);
        assertThat(booked.isDeleted()).isFalse();
        assertThat(booked.isActive()).isTrue();
        assertThat(booked.getSalesEndAt()).as("the pause's closure is lifted").isEqualTo(utc(4, 16, 30));
        assertThat(row(result, unbooked).getOutcome()).isEqualTo(Outcome.REMOVED);
        assertThat(unbooked.isDeleted()).isTrue();
        assertThat(unbooked.getSeriesId()).isEqualTo(SERIES);

        assertThatThrownBy(() -> service.end(ORGANIZER, false, SERIES))
                .isInstanceOf(ConflictException.class).hasMessage(SeriesEditService.SERIES_ENDED);
    }

    @Test
    void endingWhenTheBookingsCantBeCounted_endsNothing() {
        Event date = date(utc(11, 17, 0));
        when(bookings.activeCountsIfKnown(anyCollection())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.end(ORGANIZER, false, SERIES))
                .isInstanceOf(ServiceUnavailableException.class);

        assertThat(series.getStatus()).isEqualTo(EventSeries.Status.ACTIVE);
        assertThat(store.get(TEMPLATE).isDeleted()).isFalse();
        assertThat(date.isActive()).isTrue();
        assertThat(date.isDeleted()).isFalse();
    }

    // ---- One price on every future date -----------------------------------------

    private static SeatLayoutGateway.PriceRow priceRow(UUID eventId, String outcome, String message) {
        SeatLayoutGateway.PriceRow row = new SeatLayoutGateway.PriceRow();
        row.setEventId(eventId);
        row.setOutcome(outcome);
        row.setMessage(message);
        return row;
    }

    private static FuturePriceRequestDTO price(String name, String amount) {
        FuturePriceRequestDTO r = new FuturePriceRequestDTO();
        r.setCategoryName(name);
        r.setPrice(new BigDecimal(amount));
        return r;
    }

    @Test
    void aPriceReachesTheTemplateAndEveryFutureDate_andEachDateSaysWhatHappened() {
        Event first = date(utc(4, 17, 0));
        Event second = date(utc(11, 17, 0));
        when(seats.setPriceByName(anyList(), eq("VIP"), eq(new BigDecimal("150.00")), eq(ORGANIZER.toString())))
                .thenReturn(new SeatLayoutGateway.PriceChange(SeatLayoutGateway.Outcome.DONE, List.of(
                        priceRow(TEMPLATE, "UPDATED", null),
                        priceRow(first.getEventId(), "UPDATED", null),
                        priceRow(second.getEventId(), "REFUSED", "Lower or remove that tier first.")), null));

        SeriesChangeDTO result = service.setFuturePrice(ORGANIZER, false, ORGANIZER.toString(), SERIES,
                price(" VIP ", "150.00"));

        verify(seats).setPriceByName(eq(List.of(TEMPLATE, first.getEventId(), second.getEventId())), eq("VIP"),
                any(), anyString());
        assertThat(result.getDates().get(0).getTemplate()).isTrue();
        assertThat(result.getDates().get(0).getOutcome()).isEqualTo(Outcome.PRICE_UPDATED);
        assertThat(row(result, first).getTemplate()).isNull();
        assertThat(row(result, first).getStartDateTime()).isEqualTo(utc(4, 17, 0));
        assertThat(row(result, second).getOutcome()).isEqualTo(Outcome.PRICE_REFUSED);
        assertThat(row(result, second).getMessage()).isEqualTo("Lower or remove that tier first.");
    }

    @Test
    void aPriceSeatServiceCantTake_orCantBeAsked_orNoDateHas_isRefused() {
        when(seats.setPriceByName(anyList(), anyString(), any(), anyString()))
                .thenReturn(new SeatLayoutGateway.PriceChange(SeatLayoutGateway.Outcome.UNAVAILABLE, List.of(), null));
        assertThatThrownBy(() -> service.setFuturePrice(ORGANIZER, false, "x", SERIES, price("VIP", "1.00")))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage(SeriesEditService.PRICES_UNAVAILABLE);

        when(seats.setPriceByName(anyList(), anyString(), any(), anyString()))
                .thenReturn(new SeatLayoutGateway.PriceChange(SeatLayoutGateway.Outcome.REFUSED, List.of(),
                        "Price must be greater than 0."));
        assertThatThrownBy(() -> service.setFuturePrice(ORGANIZER, false, "x", SERIES, price("VIP", "1.00")))
                .isInstanceOf(BadRequestException.class).hasMessage("Price must be greater than 0.");

        when(seats.setPriceByName(anyList(), anyString(), any(), anyString()))
                .thenReturn(new SeatLayoutGateway.PriceChange(SeatLayoutGateway.Outcome.DONE, List.of(
                        priceRow(TEMPLATE, "NO_SUCH_CATEGORY", null)), null));
        assertThatThrownBy(() -> service.setFuturePrice(ORGANIZER, false, "x", SERIES, price("VVIP", "1.00")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("This series has no ticket type called \"VVIP\".");
    }

}
