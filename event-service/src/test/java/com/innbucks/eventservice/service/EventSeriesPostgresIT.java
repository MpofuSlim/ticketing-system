package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.OrganizerNotificationGateway;
import com.innbucks.eventservice.client.SeatLayoutGateway;
import com.innbucks.eventservice.client.StaffNotificationGateway;
import com.innbucks.eventservice.dto.LocationDTO;
import com.innbucks.eventservice.dto.series.CreateSeriesRequestDTO;
import com.innbucks.eventservice.dto.series.EventSeriesResponseDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.entity.EventScanner;
import com.innbucks.eventservice.entity.EventScannerAssignment;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.entity.SeriesState;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventScannerAssignmentRepository;
import com.innbucks.eventservice.repository.EventScannerRepository;
import com.innbucks.eventservice.repository.EventSeriesRepository;
import com.innbucks.eventservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Recurring series against real Postgres (V24, TIC-70): the migration, the
 * template that never goes live (the CHECK refuses it), the generator making
 * hidden dates and finishing them, a second run making nothing, a deleted
 * date never made again, and the lists and clash rule ignoring the template.
 * The seat layout copy is seat-service's and is stubbed here.
 */
@TestPropertySource(properties = {
        "events.series.enabled=true",
        // The test runs the generator itself; the schedule must not race it.
        "events.series.generator-cron=-"
})
class EventSeriesPostgresIT extends PostgresIntegrationTestBase {

    @MockitoBean SeatLayoutGateway seatLayoutGateway;
    @MockitoBean OrganizerNotificationGateway organizerNotices;
    @MockitoBean StaffNotificationGateway staffNotices;

    @Autowired EventSeriesService seriesService;
    @Autowired SeriesDateGenerator generator;
    @Autowired SeriesDatesService datesService;
    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;
    @Autowired EventSeriesRepository seriesRepository;
    @Autowired EventScannerRepository scannerRepository;
    @Autowired EventScannerAssignmentRepository assignmentRepository;
    @Autowired JdbcTemplate jdbc;

    private final UUID organizer = UUID.randomUUID();

    // Before AND after: every IT in the module shares the container, and the
    // others delete events with deleteAll(), which a series row would block.
    @BeforeEach
    @AfterEach
    void clean() {
        // The two series foreign keys point both ways; unlink first. The
        // sales-window record refuses DELETE (V22) and has no foreign key, so
        // it stays.
        jdbc.update("UPDATE events SET series_id = NULL, series_state = NULL WHERE series_id IS NOT NULL");
        jdbc.update("DELETE FROM event_series");
        for (String table : List.of("event_scanner_assignments", "event_sales_assignments",
                "event_terms_versions", "event_booking_questions")) {
            jdbc.update("DELETE FROM " + table + " WHERE event_id IN "
                    + "(SELECT event_id FROM events WHERE tenant_user_uuid = ?)", organizer);
        }
        jdbc.update("DELETE FROM event_scanners WHERE organizer_uuid = ?", organizer);
        jdbc.update("DELETE FROM events WHERE tenant_user_uuid = ?", organizer);
    }

    private CreateSeriesRequestDTO everyDay(int windowDays) {
        CreateSeriesRequestDTO r = new CreateSeriesRequestDTO();
        r.setTitle("Daily Tour " + UUID.randomUUID());
        r.setVenue("Harare Gardens");
        r.setCategory(EventCategory.COMEDY);
        r.setLocation(LocationDTO.builder().latitude(-17.82).longitude(31.05).build());
        r.setTotalCapacity(40);
        r.setTermsText("1. Be on time.");
        r.setDaysOfWeek(List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"));
        r.setStartTimes(List.of("10:00"));
        r.setDurationMinutes(90);
        r.setStartDate(LocalDate.now(ZoneOffset.UTC).minusDays(1));
        r.setBookingWindowDays(windowDays);
        return r;
    }

    private EventSeriesResponseDTO publishedSeries(int windowDays) {
        EventSeriesResponseDTO created = seriesService.create(organizer, "ZW", everyDay(windowDays));
        eventService.activateEvent(organizer, "ROLE_EVENT_ORGANIZER", created.getTemplateEventId());
        when(seatLayoutGateway.copyLayout(any(), any(), anyInt())).thenReturn(
                new SeatLayoutGateway.LayoutCopy(SeatLayoutGateway.Outcome.DONE, Map.of()));
        return created;
    }

    private List<Event> datesOf(UUID seriesId) {
        return eventRepository.findAll().stream()
                .filter(e -> seriesId.equals(e.getSeriesId()) && !SeriesState.isTemplate(e))
                .toList();
    }

    @Test
    void aPublishedSeries_getsADateForEveryStartInItsWindow_andASecondRunMakesNothing() {
        EventSeriesResponseDTO series = publishedSeries(3);
        Event template = eventRepository.findById(series.getTemplateEventId()).orElseThrow();
        assertThat(template.getSeriesState()).isEqualTo(SeriesState.TEMPLATE);
        assertThat(template.isActive()).as("a template is never published").isFalse();
        assertThat(seriesRepository.findById(series.getSeriesId()).orElseThrow().getStatus())
                .isEqualTo(EventSeries.Status.ACTIVE);

        SeriesDateGenerator.Summary first = generator.run();

        List<Event> dates = datesOf(series.getSeriesId());
        // One 10:00 start a day; [now, now + 3 days) always holds three.
        assertThat(dates).hasSize(3);
        assertThat(first.created()).isEqualTo(dates.size());
        assertThat(first.finished()).isEqualTo(dates.size());
        assertThat(dates).allSatisfy(date -> {
            assertThat(date.getSeriesState()).isEqualTo(SeriesState.READY);
            assertThat(date.isActive()).isTrue();
            assertThat(date.getApprovedAt()).isNotNull();
            assertThat(date.getTermsVersion()).isEqualTo(1);
            assertThat(date.getTitle()).isEqualTo(template.getTitle());
            assertThat(date.getEndDateTime()).isEqualTo(date.getStartDateTime().plusMinutes(90));
            // 10:00 in Harare is 08:00 UTC.
            assertThat(date.getStartDateTime().getHour()).isEqualTo(8);
        });

        SeriesDateGenerator.Summary second = generator.run();
        assertThat(second.created()).isZero();
        assertThat(datesOf(series.getSeriesId())).hasSameSizeAs(dates);

        EventSeriesResponseDTO detail = seriesService.get(organizer, false, series.getSeriesId());
        assertThat(detail.getUpcomingDateCount()).isEqualTo(dates.size());
        assertThat(detail.getDatesInProgress()).isZero();
    }

    @Test
    void theTemplate_isInNoListButTheReviewQueue_andItsDatesAreOrdinaryEvents() {
        EventSeriesResponseDTO series = publishedSeries(2);
        generator.run();

        List<UUID> mine = eventRepository.findByTenantUserUuid(organizer, null, null, null, PageRequest.of(0, 50))
                .map(Event::getEventId).getContent();
        assertThat(mine).doesNotContain(series.getTemplateEventId())
                .containsAll(datesOf(series.getSeriesId()).stream().map(Event::getEventId).toList());
        List<UUID> inactive = eventRepository.findByTenantUserUuidInactiveOnly(organizer, null, null, null, null,
                PageRequest.of(0, 50)).map(Event::getEventId).getContent();
        assertThat(inactive).doesNotContain(series.getTemplateEventId());
    }

    @Test
    void theDatabase_refusesALiveTemplate_andTwoDatesAtOneStart() {
        EventSeriesResponseDTO series = publishedSeries(2);
        generator.run();

        assertThatThrownBy(() -> jdbc.update("UPDATE events SET active = TRUE WHERE event_id = ?",
                series.getTemplateEventId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_events_series_unpublished");

        Event date = datesOf(series.getSeriesId()).get(0);
        Event copy = Event.builder()
                .tenantUserUuid(organizer).title("Copy").venue("Harare Gardens").country("Zimbabwe")
                .category(EventCategory.COMEDY).startDateTime(date.getStartDateTime())
                .endDateTime(date.getEndDateTime()).totalCapacity(40).availableTickets(40)
                .deleted(false).active(false).rejected(false)
                .seriesId(series.getSeriesId()).seriesState(SeriesState.GENERATING).build();
        assertThatThrownBy(() -> eventRepository.saveAndFlush(copy))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_events_series_start");
    }

    @Test
    void aDeletedDate_isNeverMadeAgain() {
        EventSeriesResponseDTO series = publishedSeries(3);
        generator.run();
        Event first = datesOf(series.getSeriesId()).stream()
                .min(java.util.Comparator.comparing(Event::getStartDateTime)).orElseThrow();
        int before = datesOf(series.getSeriesId()).size();

        eventService.deleteEvent(organizer, "ROLE_EVENT_ORGANIZER", first.getEventId());
        generator.run();

        assertThat(datesOf(series.getSeriesId())).hasSize(before);
        assertThat(eventRepository.findById(first.getEventId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    void theReportLookup_listsEveryDate_deletedOnesIncluded_neverTheTemplate() {
        EventSeriesResponseDTO series = publishedSeries(3);
        generator.run();
        List<Event> dates = datesOf(series.getSeriesId());
        Event first = dates.stream().min(java.util.Comparator.comparing(Event::getStartDateTime)).orElseThrow();
        eventService.deleteEvent(organizer, "ROLE_EVENT_ORGANIZER", first.getEventId());

        var answer = seriesService.dateIds(series.getSeriesId());

        assertThat(answer.found()).isTrue();
        assertThat(answer.tenantUserUuid()).isEqualTo(organizer);
        assertThat(answer.eventIds()).containsExactlyInAnyOrderElementsOf(
                dates.stream().map(Event::getEventId).toList());
        assertThat(answer.eventIds()).contains(first.getEventId()).doesNotContain(series.getTemplateEventId());

        var unknown = seriesService.dateIds(UUID.randomUUID());
        assertThat(unknown.found()).isFalse();
        assertThat(unknown.tenantUserUuid()).isNull();
        assertThat(unknown.eventIds()).isEmpty();
    }

    @Test
    void aDateWhoseLayoutFails_staysHidden_untilARunFinishesIt() {
        EventSeriesResponseDTO series = seriesService.create(organizer, "ZW", everyDay(2));
        eventService.activateEvent(organizer, "ROLE_EVENT_ORGANIZER", series.getTemplateEventId());
        when(seatLayoutGateway.copyLayout(any(), any(), anyInt())).thenReturn(
                new SeatLayoutGateway.LayoutCopy(SeatLayoutGateway.Outcome.UNAVAILABLE, Map.of()));

        generator.run();
        assertThat(datesOf(series.getSeriesId())).isNotEmpty().allSatisfy(date -> {
            assertThat(date.getSeriesState()).isEqualTo(SeriesState.GENERATING);
            assertThat(date.isActive()).isFalse();
        });

        when(seatLayoutGateway.copyLayout(any(), any(), anyInt())).thenReturn(
                new SeatLayoutGateway.LayoutCopy(SeatLayoutGateway.Outcome.DONE, Map.of()));
        generator.run();
        assertThat(datesOf(series.getSeriesId())).allSatisfy(date ->
                assertThat(date.getSeriesState()).isEqualTo(SeriesState.READY));
    }

    @Test
    void aScannerOnTheTemplate_clashesWithNothing_andIsCopiedOntoEachDate() {
        EventSeriesResponseDTO series = publishedSeries(2);
        EventScanner scanner = scannerRepository.saveAndFlush(EventScanner.builder()
                .scannerId(UUID.randomUUID()).organizerUuid(organizer).phoneNumber("+263771234567")
                .firstName("Tendai").lastName("Moyo").build());
        assignmentRepository.saveAndFlush(EventScannerAssignment.builder().assignmentId(UUID.randomUUID())
                .scannerId(scanner.getScannerId()).eventId(series.getTemplateEventId()).build());
        Event template = eventRepository.findById(series.getTemplateEventId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        assertThat(assignmentRepository.countClashes("+263771234567", UUID.randomUUID(), UUID.randomUUID(),
                template.getStartDateTime(), template.getEndDateTime(), now))
                .as("the template's times are only its next date's").isZero();

        generator.run();

        List<Event> dates = datesOf(series.getSeriesId());
        assertThat(dates).isNotEmpty();
        assertThat(assignmentRepository.findByEventId(dates.get(0).getEventId()))
                .extracting(EventScannerAssignment::getScannerId).containsExactly(scanner.getScannerId());
    }

    @Test
    void buyersSee_onlyTheSeriesPublishedDates_monthByMonth() {
        EventSeriesResponseDTO series = publishedSeries(4);
        generator.run();
        List<Event> dates = datesOf(series.getSeriesId());
        assertThat(dates).hasSize(4);
        UUID takenDown = dates.get(0).getEventId();
        UUID deleted = dates.get(1).getEventId();
        jdbc.update("UPDATE events SET active = false WHERE event_id = ?", takenDown);
        jdbc.update("UPDATE events SET deleted = true WHERE event_id = ?", deleted);

        com.innbucks.eventservice.dto.series.SeriesMonthDTO first = datesService.month(series.getSeriesId(), null);
        List<UUID> listed = new java.util.ArrayList<>();
        for (String month : first.getMonths()) {
            datesService.month(series.getSeriesId(), month).getDates()
                    .forEach(d -> listed.add(d.getEventId()));
        }

        assertThat(first.getMonth()).isEqualTo(first.getMonths().getFirst());
        assertThat(listed).containsExactlyElementsOf(dates.subList(2, 4).stream()
                .sorted(java.util.Comparator.comparing(Event::getStartDateTime))
                .map(Event::getEventId).toList());
        assertThat(listed).doesNotContain(series.getTemplateEventId(), takenDown, deleted);
    }

    @Test
    void aSeriesNeverPublished_hasNoPublicDates() {
        EventSeriesResponseDTO draft = seriesService.create(organizer, "ZW", everyDay(3));

        assertThatThrownBy(() -> datesService.month(draft.getSeriesId(), null))
                .isInstanceOf(com.innbucks.eventservice.exception.NotFoundException.class)
                .hasMessage("Series not found");
    }
}
