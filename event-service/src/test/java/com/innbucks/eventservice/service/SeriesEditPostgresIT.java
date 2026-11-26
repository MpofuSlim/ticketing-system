package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.BookingGateway;
import com.innbucks.eventservice.client.BookingNotificationGateway;
import com.innbucks.eventservice.client.OrganizerNotificationGateway;
import com.innbucks.eventservice.client.SeatLayoutGateway;
import com.innbucks.eventservice.client.StaffNotificationGateway;
import com.innbucks.eventservice.dto.LocationDTO;
import com.innbucks.eventservice.dto.UpdateEventRequestDTO;
import com.innbucks.eventservice.dto.series.CreateSeriesRequestDTO;
import com.innbucks.eventservice.dto.series.EventSeriesResponseDTO;
import com.innbucks.eventservice.dto.series.FutureDatesRequestDTO;
import com.innbucks.eventservice.dto.series.SeriesChangeDTO;
import com.innbucks.eventservice.dto.series.SeriesChangeDTO.Outcome;
import com.innbucks.eventservice.dto.series.SeriesScheduleRequestDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.entity.SeriesState;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventSeriesRepository;
import com.innbucks.eventservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Changing a series against real Postgres (V25, TIC-71): the pause columns and
 * the new queries, a paused date still editable through the ordinary event
 * update, a removed date detached so the unique start index lets a later
 * schedule make its start again, a skipped date kept so it never comes back,
 * and a move under the same index. Booking counts, booking messages and the
 * seat layout are other services' and are stubbed here.
 */
@TestPropertySource(properties = {
        "events.series.enabled=true",
        // The test runs the generator itself; the schedule must not race it.
        "events.series.generator-cron=-"
})
class SeriesEditPostgresIT extends PostgresIntegrationTestBase {

    @MockitoBean SeatLayoutGateway seatLayoutGateway;
    @MockitoBean OrganizerNotificationGateway organizerNotices;
    @MockitoBean StaffNotificationGateway staffNotices;
    @MockitoBean BookingGateway bookingGateway;
    @MockitoBean BookingNotificationGateway bookingNotices;

    @Autowired EventSeriesService seriesService;
    @Autowired SeriesEditService editService;
    @Autowired SeriesDateGenerator generator;
    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;
    @Autowired EventSeriesRepository seriesRepository;
    @Autowired JdbcTemplate jdbc;

    private final UUID organizer = UUID.randomUUID();

    // Before AND after, as in EventSeriesPostgresIT: the container is shared.
    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("UPDATE events SET series_id = NULL, series_state = NULL WHERE series_id IS NOT NULL");
        jdbc.update("DELETE FROM event_series");
        for (String table : List.of("event_scanner_assignments", "event_sales_assignments",
                "event_terms_versions", "event_booking_questions")) {
            jdbc.update("DELETE FROM " + table + " WHERE event_id IN "
                    + "(SELECT event_id FROM events WHERE tenant_user_uuid = ?)", organizer);
        }
        jdbc.update("DELETE FROM events WHERE tenant_user_uuid = ?", organizer);
    }

    @BeforeEach
    void stubs() {
        when(seatLayoutGateway.copyLayout(any(), any(), anyInt())).thenReturn(
                new SeatLayoutGateway.LayoutCopy(SeatLayoutGateway.Outcome.DONE, Map.of()));
        when(bookingGateway.activeCountsIfKnown(anyCollection())).thenReturn(Optional.of(Map.of()));
        when(bookingGateway.activeCountsByEventIds(anyCollection())).thenReturn(Map.of());
    }

    /**
     * Every day at 10:00 Harare, from yesterday. Sales open 10 hours before
     * each date and never close early, so [now, now + window) always holds one
     * start a day, and tomorrow's date has a sales start still ahead (which a
     * pause's sales end then sits before).
     */
    private EventSeriesResponseDTO publishedDailySeries(int windowDays) {
        CreateSeriesRequestDTO r = new CreateSeriesRequestDTO();
        r.setTitle("Daily Tour " + UUID.randomUUID());
        r.setVenue("Harare Gardens");
        r.setCategory(EventCategory.COMEDY);
        r.setLocation(LocationDTO.builder().latitude(-17.82).longitude(31.05).build());
        r.setTotalCapacity(40);
        r.setDaysOfWeek(List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"));
        r.setStartTimes(List.of("10:00"));
        r.setDurationMinutes(90);
        r.setStartDate(LocalDate.now(ZoneOffset.UTC).minusDays(1));
        r.setBookingWindowDays(windowDays);
        r.setSalesOpenMinutesBefore(600);
        EventSeriesResponseDTO created = seriesService.create(organizer, "ZW", r);
        eventService.activateEvent(organizer, "ROLE_EVENT_ORGANIZER", created.getTemplateEventId());
        return created;
    }

    private SeriesScheduleRequestDTO dailyAt(String time, int windowDays) {
        SeriesScheduleRequestDTO r = new SeriesScheduleRequestDTO();
        r.setDaysOfWeek(List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"));
        r.setStartTimes(List.of(time));
        r.setDurationMinutes(90);
        r.setStartDate(LocalDate.now(ZoneOffset.UTC).minusDays(1));
        r.setBookingWindowDays(windowDays);
        r.setSalesOpenMinutesBefore(600);
        return r;
    }

    private List<Event> liveDatesOf(UUID seriesId) {
        return eventRepository.findAll().stream()
                .filter(e -> seriesId.equals(e.getSeriesId()) && !SeriesState.isTemplate(e) && !e.isDeleted())
                .sorted(Comparator.comparing(Event::getStartDateTime))
                .toList();
    }

    @Test
    void pausing_closesSalesInTheDatabase_aPausedDateStaysEditable_andResumingPutsTheOwnEndBack() {
        EventSeriesResponseDTO series = publishedDailySeries(3);
        generator.run();
        Event date = liveDatesOf(series.getSeriesId()).get(1);
        assertThat(date.getSalesEndAt()).as("sells until it ends").isNull();
        assertThat(date.getSalesStartAt()).as("tomorrow's sales are still to open").isAfter(LocalDateTime.now(ZoneOffset.UTC));

        editService.pause(organizer, false, series.getSeriesId());

        Event paused = eventRepository.findById(date.getEventId()).orElseThrow();
        assertThat(paused.getSeriesPausedAt()).isNotNull();
        assertThat(paused.getSeriesPausedSalesEndAt()).isNull();
        assertThat(paused.getSalesEndAt()).as("closed at the pause").isBefore(paused.getSalesStartAt());
        assertThat(paused.isActive()).isTrue();
        assertThat(seriesRepository.findById(series.getSeriesId()).orElseThrow().getStatus())
                .isEqualTo(EventSeries.Status.PAUSED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_sales_window_changes WHERE event_id = ?",
                Long.class, date.getEventId())).as("CREATED at birth, then the pause").isEqualTo(2L);
        assertThat(generator.run().created()).as("a paused series makes no dates").isZero();

        // Checked against the date's own sales end, not the pause's (which sits
        // before its sales start and would be refused as closing before opening).
        UpdateEventRequestDTO title = new UpdateEventRequestDTO();
        title.setTitle("Daily Tour (new title)");
        eventService.updateEvent(organizer, "ROLE_EVENT_ORGANIZER", date.getEventId(), title);
        assertThat(eventRepository.findById(date.getEventId()).orElseThrow().getSeriesPausedAt())
                .as("an edit that is not about sales keeps the pause").isNotNull();

        editService.resume(organizer, false, series.getSeriesId());

        Event resumed = eventRepository.findById(date.getEventId()).orElseThrow();
        assertThat(resumed.getSalesEndAt()).isNull();
        assertThat(resumed.getSeriesPausedAt()).isNull();
        assertThat(resumed.getSeriesPausedSalesEndAt()).isNull();
        assertThat(resumed.getTitle()).isEqualTo("Daily Tour (new title)");
    }

    @Test
    void settingAPausedDatesSalesWindow_takesItOutOfThePause() {
        EventSeriesResponseDTO series = publishedDailySeries(3);
        generator.run();
        Event date = liveDatesOf(series.getSeriesId()).get(1);
        editService.pause(organizer, false, series.getSeriesId());

        UpdateEventRequestDTO sales = new UpdateEventRequestDTO();
        sales.setSalesEndDateTime(null);
        eventService.updateEvent(organizer, "ROLE_EVENT_ORGANIZER", date.getEventId(), sales);

        Event edited = eventRepository.findById(date.getEventId()).orElseThrow();
        assertThat(edited.getSeriesPausedAt()).isNull();
        assertThat(edited.getSalesEndAt()).as("explicitly cleared: sells until the date ends").isNull();
    }

    @Test
    void aRemovedDate_leavesTheSeries_soARevertedScheduleMakesItsStartAgain() {
        EventSeriesResponseDTO series = publishedDailySeries(3);
        generator.run();
        List<Event> tens = liveDatesOf(series.getSeriesId());
        assertThat(tens).hasSize(3);

        SeriesChangeDTO moved = editService.updateSchedule(organizer, false, series.getSeriesId(),
                dailyAt("11:00", 3));
        assertThat(moved.getDates()).hasSize(3).allSatisfy(c -> assertThat(c.getOutcome()).isEqualTo(Outcome.REMOVED));
        for (Event removed : tens) {
            Event row = eventRepository.findById(removed.getEventId()).orElseThrow();
            assertThat(row.isDeleted()).isTrue();
            assertThat(row.getSeriesId()).isNull();
        }
        generator.run();
        assertThat(liveDatesOf(series.getSeriesId())).hasSize(3)
                .allSatisfy(d -> assertThat(d.getStartDateTime().getHour()).as("11:00 Harare").isEqualTo(9));

        editService.updateSchedule(organizer, false, series.getSeriesId(), dailyAt("10:00", 3));
        generator.run();

        assertThat(liveDatesOf(series.getSeriesId())).hasSize(3)
                .allSatisfy(d -> assertThat(d.getStartDateTime().getHour()).as("10:00 Harare again").isEqualTo(8));
    }

    @Test
    void aBookedDate_isKept_whenTheScheduleDropsIt() {
        EventSeriesResponseDTO series = publishedDailySeries(3);
        generator.run();
        Event booked = liveDatesOf(series.getSeriesId()).get(0);
        when(bookingGateway.activeCountsIfKnown(anyCollection()))
                .thenReturn(Optional.of(Map.of(booked.getEventId(), 2L)));

        SeriesChangeDTO result = editService.updateSchedule(organizer, false, series.getSeriesId(),
                dailyAt("11:00", 3));

        assertThat(result.getDates()).filteredOn(c -> c.getEventId().equals(booked.getEventId()))
                .singleElement().satisfies(c -> assertThat(c.getOutcome()).isEqualTo(Outcome.KEPT_HAS_BOOKINGS));
        Event row = eventRepository.findById(booked.getEventId()).orElseThrow();
        assertThat(row.isDeleted()).isFalse();
        assertThat(row.isActive()).isTrue();
        assertThat(row.getSeriesId()).isEqualTo(series.getSeriesId());
    }

    @Test
    void aSkippedDate_staysInTheSeries_andIsNeverMadeAgain() {
        EventSeriesResponseDTO series = publishedDailySeries(3);
        generator.run();
        Event skipped = liveDatesOf(series.getSeriesId()).get(1);

        editService.skipDate(organizer, false, series.getSeriesId(), skipped.getEventId());
        generator.run();

        assertThat(liveDatesOf(series.getSeriesId())).hasSize(2)
                .noneMatch(d -> d.getStartDateTime().equals(skipped.getStartDateTime()));
        assertThat(eventRepository.findById(skipped.getEventId()).orElseThrow().getSeriesId())
                .isEqualTo(series.getSeriesId());
    }

    @Test
    void movingTheStartTime_movesEveryFutureDate_underTheUniqueStartIndex() {
        EventSeriesResponseDTO series = publishedDailySeries(3);
        generator.run();
        List<Event> before = liveDatesOf(series.getSeriesId());

        FutureDatesRequestDTO request = new FutureDatesRequestDTO();
        FutureDatesRequestDTO.MoveStartTime move = new FutureDatesRequestDTO.MoveStartTime();
        move.setFrom("10:00");
        move.setTo("10:30");
        request.setMoveStartTime(move);
        SeriesChangeDTO result = editService.updateFutureDates(organizer, false, series.getSeriesId(), request);

        assertThat(result.getDates()).allSatisfy(c -> assertThat(c.getOutcome()).isIn(Outcome.MOVED,
                Outcome.NOT_MOVED_PASSED));
        for (Event date : before) {
            Event row = eventRepository.findById(date.getEventId()).orElseThrow();
            if (row.getStartDateTime().isAfter(date.getStartDateTime())) {
                assertThat(row.getStartDateTime()).isEqualTo(date.getStartDateTime().plusMinutes(30));
                assertThat(row.getEndDateTime()).isEqualTo(date.getEndDateTime().plusMinutes(30));
            }
        }
        assertThat(seriesRepository.findById(series.getSeriesId()).orElseThrow().getStartTimes())
                .containsExactly("10:30");
        generator.run();
        assertThat(liveDatesOf(series.getSeriesId())).as("the schedule followed the move: no 10:00 date is made again")
                .allSatisfy(d -> assertThat(d.getStartDateTime().getMinute()).isEqualTo(30));
    }

    @Test
    void ending_removesTheUnbookedDates_andDeletesTheTemplate() {
        EventSeriesResponseDTO series = publishedDailySeries(3);
        generator.run();

        SeriesChangeDTO result = editService.end(organizer, false, series.getSeriesId());

        assertThat(result.getDates()).isNotEmpty()
                .allSatisfy(c -> assertThat(c.getOutcome()).isEqualTo(Outcome.REMOVED));
        assertThat(liveDatesOf(series.getSeriesId())).isEmpty();
        assertThat(eventRepository.findById(series.getTemplateEventId()).orElseThrow().isDeleted()).isTrue();
        assertThat(seriesRepository.findById(series.getSeriesId()).orElseThrow().getStatus())
                .isEqualTo(EventSeries.Status.ENDED);
    }
}
