package com.innbucks.eventservice.service;

import com.innbucks.eventservice.cache.PublicEventCatalog;
import com.innbucks.eventservice.client.SeatLayoutGateway;
import com.innbucks.eventservice.config.MarketTimeZone;
import com.innbucks.eventservice.entity.BookingQuestionType;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventBookingQuestion;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.entity.EventSalesAssignment;
import com.innbucks.eventservice.entity.EventSalesWindowChange;
import com.innbucks.eventservice.entity.EventScanner;
import com.innbucks.eventservice.entity.EventScannerAssignment;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.entity.Location;
import com.innbucks.eventservice.entity.SeriesState;
import com.innbucks.eventservice.repository.EventBookingQuestionRepository;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventSalesAssignmentRepository;
import com.innbucks.eventservice.repository.EventSalesWindowChangeRepository;
import com.innbucks.eventservice.repository.EventScannerAssignmentRepository;
import com.innbucks.eventservice.repository.EventScannerRepository;
import com.innbucks.eventservice.repository.EventSeriesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The series generator (TIC-70) over mocked repositories: which starts get a
 * date, what a date copies from its template, and how it is finished.
 * Tuesday 1 December 2026, 10:00 in Harare (08:00 UTC).
 */
class SeriesDateGeneratorTest {

    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 12, 1, 8, 0);
    private static final UUID ORGANIZER = UUID.randomUUID();

    private final EventSeriesRepository seriesRepo = mock(EventSeriesRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final EventBookingQuestionRepository questions = mock(EventBookingQuestionRepository.class);
    private final EventScannerRepository scanners = mock(EventScannerRepository.class);
    private final EventScannerAssignmentRepository scanAssignments = mock(EventScannerAssignmentRepository.class);
    private final EventSalesAssignmentRepository salesAssignments = mock(EventSalesAssignmentRepository.class);
    private final EventSalesWindowChangeRepository windowChanges = mock(EventSalesWindowChangeRepository.class);
    private final EventTermsAndQuestions terms = mock(EventTermsAndQuestions.class);
    private final SeatLayoutGateway layouts = mock(SeatLayoutGateway.class);
    private final PublicEventCatalog catalog = mock(PublicEventCatalog.class);

    private final Map<UUID, Event> stored = new HashMap<>();
    private SeriesDateGenerator generator;
    private EventSeries series;
    private Event template;

    @BeforeEach
    void setUp() {
        generator = new SeriesDateGenerator(new SeriesPolicy(true, 100, Duration.ofSeconds(25)), seriesRepo, events,
                questions, scanners, scanAssignments, salesAssignments, windowChanges, terms, layouts,
                new MarketTimeZone("ZW"), catalog, null);
        generator.setClock(Clock.fixed(NOW_UTC.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

        series = EventSeries.builder()
                .seriesId(UUID.randomUUID()).templateEventId(UUID.randomUUID()).tenantUserUuid(ORGANIZER)
                .status(EventSeries.Status.ACTIVE)
                .daysOfWeek(new ArrayList<>(List.of("FRIDAY"))).startTimes(new ArrayList<>(List.of("19:00")))
                .durationMinutes(120).startDate(LocalDate.of(2026, 12, 1)).bookingWindowDays(14)
                .salesOpenMinutesBefore(14 * 24 * 60).salesCloseMinutesBefore(30)
                .build();
        template = Event.builder()
                .eventId(series.getTemplateEventId()).tenantUserUuid(ORGANIZER).title("Friday Comedy Night")
                .settlementCode("COMEDYFRI").description("<p>Stand-up</p>").venue("Theatre in the Park")
                .country("Zimbabwe").category(EventCategory.COMEDY).location(new Location(-17.82, 31.05))
                .startDateTime(LocalDateTime.of(2026, 11, 27, 17, 0)).endDateTime(LocalDateTime.of(2026, 11, 27, 19, 0))
                .totalCapacity(120).availableTickets(120).bannerContentType("image/png")
                .deleted(false).active(false).termsVersion(2)
                .submittedAt(LocalDateTime.of(2026, 11, 20, 9, 0)).approvedAt(LocalDateTime.of(2026, 11, 21, 9, 0))
                .approvedBy("reviewer@innbucks.co.zw")
                .seriesId(series.getSeriesId()).seriesState(SeriesState.TEMPLATE).build();
        stored.put(template.getEventId(), template);

        when(seriesRepo.findById(series.getSeriesId())).thenAnswer(inv -> Optional.of(series));
        when(seriesRepo.findIdsByStatusIn(anyCollection())).thenReturn(List.of(series.getSeriesId()));
        when(events.findById(any())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.<UUID>getArgument(0))));
        when(events.lockById(any())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.<UUID>getArgument(0))));
        when(events.saveAndFlush(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            if (e.getEventId() == null) {
                e.setEventId(UUID.randomUUID());
            }
            stored.put(e.getEventId(), e);
            return e;
        });
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(events.findSeriesStartsFrom(any(), any())).thenReturn(List.of());
        when(events.findGeneratingDateIds(any(), any())).thenAnswer(inv -> stored.values().stream()
                .filter(e -> SeriesState.GENERATING.equals(e.getSeriesState()))
                .map(Event::getEventId).toList());
        when(events.findBannerImage(template.getEventId())).thenReturn(Optional.of(new byte[]{1, 2, 3}));
        when(terms.currentText(template)).thenReturn("1. No refunds.");
        when(scanAssignments.findByEventId(template.getEventId())).thenReturn(List.of());
        when(salesAssignments.findByEventId(template.getEventId())).thenReturn(List.of());
        when(questions.findByEventIdOrderByPositionAsc(template.getEventId())).thenReturn(List.of());
    }

    private static LocalDateTime utc(int month, int day, int hour) {
        return LocalDateTime.of(2026, month, day, hour, 0);
    }

    @Test
    void everyStartInTheWindow_withoutADate_isPlanned() {
        SeriesDateGenerator.Plan plan = generator.plan(series.getSeriesId());

        // Fridays 4 and 11 December, 19:00 in Harare = 17:00 UTC; 18 December is past the 14-day window.
        assertThat(plan.missingStartsUtc()).containsExactly(utc(12, 4, 17), utc(12, 11, 17));
        assertThat(plan.nextStartUtc()).isEqualTo(utc(12, 4, 17));
    }

    @Test
    void aStartThatHasADate_deletedOrNot_isNotPlannedAgain() {
        when(events.findSeriesStartsFrom(series.getSeriesId(), NOW_UTC)).thenReturn(List.of(utc(12, 4, 17)));

        assertThat(generator.plan(series.getSeriesId()).missingStartsUtc()).containsExactly(utc(12, 11, 17));
    }

    @Test
    void aDraftSeries_makesNoDates_butItsTemplateMovesToTheNextStart() {
        series.setStatus(EventSeries.Status.DRAFT);

        SeriesDateGenerator.Summary summary = generator.run();

        assertThat(summary.created()).isZero();
        assertThat(template.getStartDateTime()).isEqualTo(utc(12, 4, 17));
        assertThat(template.getEndDateTime()).isEqualTo(utc(12, 4, 19));
        verify(events, never()).saveAndFlush(any());
    }

    @Test
    void aNewDate_isAHiddenApprovedCopyOfTheTemplate_withItsSalesWindowFromTheSchedule() {
        UUID dateId = generator.createDate(series.getSeriesId(), utc(12, 4, 17));

        Event date = stored.get(dateId);
        assertThat(date.getSeriesId()).isEqualTo(series.getSeriesId());
        assertThat(date.getSeriesState()).isEqualTo(SeriesState.GENERATING);
        assertThat(date.isActive()).as("hidden until it is finished").isFalse();
        assertThat(date.getStartDateTime()).isEqualTo(utc(12, 4, 17));
        assertThat(date.getEndDateTime()).isEqualTo(utc(12, 4, 19));
        assertThat(date.getSalesStartAt()).isEqualTo(utc(11, 20, 17));
        assertThat(date.getSalesEndAt()).isEqualTo(LocalDateTime.of(2026, 12, 4, 16, 30));
        assertThat(date.getTitle()).isEqualTo("Friday Comedy Night");
        assertThat(date.getSettlementCode()).isEqualTo("COMEDYFRI");
        assertThat(date.getTotalCapacity()).isEqualTo(120);
        assertThat(date.getAvailableTickets()).isEqualTo(120);
        assertThat(date.getLocation()).isNotSameAs(template.getLocation());
        assertThat(date.getLocation().getLatitude()).isEqualTo(-17.82);
        assertThat(date.getApprovedAt()).isEqualTo(NOW_UTC);
        assertThat(date.getApprovedBy()).isEqualTo("reviewer@innbucks.co.zw");
        assertThat(date.getTermsVersion()).as("the template's current terms, as version 1").isEqualTo(1);
        verify(terms).recordFirstVersion(dateId, "1. No refunds.");
        verify(events).writeBannerImage(eq(dateId), eq(new byte[]{1, 2, 3}));
        ArgumentCaptor<EventSalesWindowChange> change = ArgumentCaptor.forClass(EventSalesWindowChange.class);
        verify(windowChanges).save(change.capture());
        assertThat(change.getValue().getChangeType()).isEqualTo(EventSalesWindowChange.ChangeType.CREATED);
        assertThat(change.getValue().getNewSalesEndAt()).isEqualTo(date.getSalesEndAt());
    }

    @Test
    void aStartThatAlreadyHasADate_orASeriesNoLongerLive_makesNothing() {
        when(events.existsBySeriesIdAndStartDateTimeAndSeriesStateIn(eq(series.getSeriesId()), eq(utc(12, 4, 17)),
                anyCollection())).thenReturn(true);
        assertThat(generator.createDate(series.getSeriesId(), utc(12, 4, 17))).isNull();

        series.setStatus(EventSeries.Status.DRAFT);
        assertThat(generator.createDate(series.getSeriesId(), utc(12, 11, 17))).isNull();
        verify(events, never()).saveAndFlush(any());
    }

    @Test
    void theTeamIsCopied_exceptAScannerWhoseNumberWouldBeAtTwoEventsAtOnce() {
        EventScanner free = EventScanner.builder().scannerId(UUID.randomUUID()).organizerUuid(ORGANIZER)
                .phoneNumber("+263771111111").firstName("Tendai").build();
        EventScanner busy = EventScanner.builder().scannerId(UUID.randomUUID()).organizerUuid(ORGANIZER)
                .phoneNumber("+263772222222").firstName("Rudo").build();
        EventScanner stranger = EventScanner.builder().scannerId(UUID.randomUUID()).organizerUuid(UUID.randomUUID())
                .phoneNumber("+263773333333").firstName("Other").build();
        when(scanAssignments.findByEventId(template.getEventId())).thenReturn(List.of(
                EventScannerAssignment.builder().scannerId(free.getScannerId()).eventId(template.getEventId())
                        .assignedBy("organizer@example.com").build(),
                EventScannerAssignment.builder().scannerId(busy.getScannerId()).eventId(template.getEventId()).build(),
                EventScannerAssignment.builder().scannerId(stranger.getScannerId()).eventId(template.getEventId())
                        .build()));
        when(scanners.findAllById(any())).thenReturn(List.of(free, busy, stranger));
        when(scanAssignments.countClashes(eq("+263772222222"), any(), any(), any(), any(), any())).thenReturn(1L);
        when(salesAssignments.findByEventId(template.getEventId())).thenReturn(List.of(
                EventSalesAssignment.builder().scannerId(free.getScannerId()).eventId(template.getEventId())
                        .ticketCap(50).commissionPercent(new BigDecimal("5.00")).assignedBy("organizer@example.com")
                        .build()));

        UUID dateId = generator.createDate(series.getSeriesId(), utc(12, 4, 17));

        verify(scanners).lockAllByPhoneNumber("+263771111111");
        verify(scanners).lockAllByPhoneNumber("+263772222222");
        verify(scanners, never()).lockAllByPhoneNumber("+263773333333");
        ArgumentCaptor<EventScannerAssignment> scan = ArgumentCaptor.forClass(EventScannerAssignment.class);
        verify(scanAssignments, times(1)).save(scan.capture());
        assertThat(scan.getValue().getScannerId()).isEqualTo(free.getScannerId());
        assertThat(scan.getValue().getEventId()).isEqualTo(dateId);
        assertThat(scan.getValue().getAssignedBy()).isEqualTo("organizer@example.com");
        ArgumentCaptor<EventSalesAssignment> sale = ArgumentCaptor.forClass(EventSalesAssignment.class);
        verify(salesAssignments).save(sale.capture());
        assertThat(sale.getValue().getEventId()).isEqualTo(dateId);
        assertThat(sale.getValue().getTicketCap()).isEqualTo(50);
        assertThat(sale.getValue().getCommissionPercent()).isEqualByComparingTo("5.00");
    }

    @Test
    void finishing_copiesTheQuestionsOntoTheDatesCategories_andPutsTheDateLive() {
        UUID dateId = generator.createDate(series.getSeriesId(), utc(12, 4, 17));
        UUID vip = UUID.randomUUID();
        UUID vipOnDate = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        when(questions.findByEventIdOrderByPositionAsc(template.getEventId())).thenReturn(List.of(
                question("Shirt size", List.of()),
                question("Dietary needs", List.of(vip.toString(), gone.toString())),
                question("Parking", List.of(gone.toString()))));
        when(layouts.copyLayout(template.getEventId(), dateId, 120)).thenReturn(
                new SeatLayoutGateway.LayoutCopy(SeatLayoutGateway.Outcome.DONE, Map.of(vip, vipOnDate)));

        assertThat(generator.finishDate(template.getEventId(), dateId)).isEqualTo(SeriesDateGenerator.Finish.READY);

        Event date = stored.get(dateId);
        assertThat(date.getSeriesState()).isEqualTo(SeriesState.READY);
        assertThat(date.isActive()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EventBookingQuestion>> saved = ArgumentCaptor.forClass(List.class);
        verify(questions).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(EventBookingQuestion::getLabel)
                .as("a question none of whose categories reached the date is left off")
                .containsExactly("Shirt size", "Dietary needs");
        assertThat(saved.getValue().get(0).getCategoryIds()).as("every category").isEmpty();
        assertThat(saved.getValue().get(1).getCategoryIds()).containsExactly(vipOnDate.toString());
        assertThat(saved.getValue()).allSatisfy(q -> {
            assertThat(q.getEventId()).isEqualTo(dateId);
            assertThat(q.getCreatedAt()).isEqualTo(NOW_UTC);
        });
        assertThat(saved.getValue()).extracting(EventBookingQuestion::getPosition).containsExactly(0, 1);
        verify(catalog).invalidateAll();
    }

    @Test
    void aDateWhoseLayoutCannotBeCopied_waitsHidden_forTheNextRun() {
        UUID dateId = generator.createDate(series.getSeriesId(), utc(12, 4, 17));
        when(layouts.copyLayout(any(), any(), anyInt())).thenReturn(
                new SeatLayoutGateway.LayoutCopy(SeatLayoutGateway.Outcome.UNAVAILABLE, Map.of()));

        assertThat(generator.finishDate(template.getEventId(), dateId)).isEqualTo(SeriesDateGenerator.Finish.WAITING);

        assertThat(stored.get(dateId).getSeriesState()).isEqualTo(SeriesState.GENERATING);
        assertThat(stored.get(dateId).isActive()).isFalse();
        verify(questions, never()).saveAll(anyList());
    }

    @Test
    void aDateFinishedAfterItsSeriesWasUnpublished_isReadyButNotLive() {
        UUID dateId = generator.createDate(series.getSeriesId(), utc(12, 4, 17));
        series.setStatus(EventSeries.Status.DRAFT);
        when(layouts.copyLayout(any(), any(), anyInt())).thenReturn(
                new SeatLayoutGateway.LayoutCopy(SeatLayoutGateway.Outcome.DONE, Map.of()));

        generator.finishDate(template.getEventId(), dateId);

        assertThat(stored.get(dateId).getSeriesState()).isEqualTo(SeriesState.READY);
        assertThat(stored.get(dateId).isActive()).isFalse();
        verify(catalog, never()).invalidateAll();
    }

    @Test
    void aDateFinishedWhileItsSeriesIsPaused_isListedWithItsSalesClosed_soResumingOpensIt() {
        generator.setPauses(new SeriesPauses(events, windowChanges));
        UUID dateId = generator.createDate(series.getSeriesId(), utc(12, 4, 17));
        LocalDateTime ownSalesEnd = stored.get(dateId).getSalesEndAt();
        series.setStatus(EventSeries.Status.PAUSED);
        when(layouts.copyLayout(any(), any(), anyInt())).thenReturn(
                new SeatLayoutGateway.LayoutCopy(SeatLayoutGateway.Outcome.DONE, Map.of()));

        generator.finishDate(template.getEventId(), dateId);

        Event date = stored.get(dateId);
        assertThat(date.getSeriesState()).isEqualTo(SeriesState.READY);
        assertThat(date.isActive()).isTrue();
        assertThat(date.getSalesEndAt()).isEqualTo(NOW_UTC);
        assertThat(date.getSeriesPausedAt()).isEqualTo(NOW_UTC);
        assertThat(date.getSeriesPausedSalesEndAt()).isEqualTo(ownSalesEnd);
    }

    @Test
    void aRun_makesAndFinishesEveryPlannedDate() {
        when(layouts.copyLayout(any(), any(), anyInt())).thenReturn(
                new SeatLayoutGateway.LayoutCopy(SeatLayoutGateway.Outcome.DONE, Map.of()));

        SeriesDateGenerator.Summary summary = generator.run();

        assertThat(summary.created()).isEqualTo(2);
        assertThat(summary.finished()).isEqualTo(2);
        assertThat(stored.values()).filteredOn(e -> SeriesState.READY.equals(e.getSeriesState()))
                .extracting(Event::getStartDateTime).containsExactlyInAnyOrder(utc(12, 4, 17), utc(12, 11, 17));
    }

    @Test
    void aRun_makesNoMoreDatesThanItsBudget() {
        generator = new SeriesDateGenerator(new SeriesPolicy(true, 1, Duration.ofSeconds(25)), seriesRepo, events,
                questions, scanners, scanAssignments, salesAssignments, windowChanges, terms, layouts,
                new MarketTimeZone("ZW"), catalog, null);
        generator.setClock(Clock.fixed(NOW_UTC.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        when(layouts.copyLayout(any(), any(), anyInt())).thenReturn(
                new SeatLayoutGateway.LayoutCopy(SeatLayoutGateway.Outcome.DONE, Map.of()));

        assertThat(generator.run().created()).isEqualTo(1);
    }

    @Test
    void switchedOff_theScheduledRunDoesNothing() {
        generator = new SeriesDateGenerator(new SeriesPolicy(false, 100, Duration.ofSeconds(25)), seriesRepo, events,
                questions, scanners, scanAssignments, salesAssignments, windowChanges, terms, layouts,
                new MarketTimeZone("ZW"), catalog, null);

        generator.scheduledRun();

        verify(seriesRepo, never()).findIdsByStatusIn(anyCollection());
        verify(layouts, never()).copyLayout(any(), any(), anyInt());
        verify(scanners, never()).lockAllByPhoneNumber(anyString());
    }

    private static EventBookingQuestion question(String label, List<String> categories) {
        return EventBookingQuestion.builder()
                .id(UUID.randomUUID()).label(label).type(BookingQuestionType.TEXT).required(true)
                .maxLength(50).categoryIds(new ArrayList<>(categories)).createdAt(NOW_UTC).build();
    }
}
