package com.innbucks.eventservice.service;

import com.innbucks.eventservice.cache.PublicEventCatalog;
import com.innbucks.eventservice.cache.ReadCacheConfig;
import com.innbucks.eventservice.cache.ReadCacheProperties;
import com.innbucks.eventservice.client.SeatCategoryGateway;
import com.innbucks.eventservice.config.MarketTimeZone;
import com.innbucks.eventservice.dto.AvailabilityLevel;
import com.innbucks.eventservice.dto.EventPriceFromDTO;
import com.innbucks.eventservice.dto.SalesStatus;
import com.innbucks.eventservice.dto.series.SeriesMonthDTO;
import com.innbucks.eventservice.dto.series.SeriesPublicDateDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.exception.FieldErrorsException;
import com.innbucks.eventservice.exception.NotFoundException;
import com.innbucks.eventservice.mapper.EventMapper;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventSeriesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A series' dates for buyers (TIC-73): which series answer, the month the
 * picker opens on, what each date carries, and what is cached and what is
 * worked out per call. Repositories and seat-service are mocked; the cache is
 * the real Caffeine one.
 */
class SeriesDatesServiceTest {

    /** 2026-11-26 10:00 UTC = 12:00 in Harare. */
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final UUID seriesId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();

    private EventSeriesRepository seriesRepository;
    private EventRepository eventRepository;
    private SeatCategoryGateway seats;
    private SeriesPolicy policy;
    private SeriesDatesService service;
    private Event template;

    @BeforeEach
    void setUp() {
        seriesRepository = mock(EventSeriesRepository.class);
        eventRepository = mock(EventRepository.class);
        seats = mock(SeatCategoryGateway.class);
        policy = mock(SeriesPolicy.class);
        when(policy.enabled()).thenReturn(true);
        EventMapper mapper = new EventMapper();
        PublicEventCatalog catalog = new PublicEventCatalog(
                new ReadCacheConfig().readCacheManager(new ReadCacheProperties()), mapper);
        service = new SeriesDatesService(seriesRepository, eventRepository, catalog, seats, mapper,
                new MarketTimeZone("ZW"), policy);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));

        template = Event.builder().eventId(templateId).title("Friday Comedy Night").venue("Theatre in the Park")
                .category(EventCategory.COMEDY).approvedAt(NOW_UTC.minusDays(3)).active(false).build();
        when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(EventSeries.builder()
                .seriesId(seriesId).templateEventId(templateId).tenantUserUuid(UUID.randomUUID())
                .status(EventSeries.Status.ACTIVE).build()));
        when(eventRepository.findById(templateId)).thenReturn(Optional.of(template));
        when(seats.fetchSaleSummaries(anyCollection())).thenReturn(Map.of());
    }

    private Event date(LocalDateTime startUtc) {
        return Event.builder().eventId(UUID.randomUUID()).title("Friday Comedy Night").venue("Theatre in the Park")
                .category(EventCategory.COMEDY).seriesId(seriesId).seriesState("READY")
                .startDateTime(startUtc).endDateTime(startUtc.plusHours(2))
                .salesEndAt(startUtc.minusMinutes(30)).active(true).build();
    }

    private void stubMonth(List<Event> dates, LocalDateTime... upcomingStarts) {
        when(eventRepository.findPublicSeriesDates(eq(seriesId), any(), any(), any(), any())).thenReturn(dates);
        when(eventRepository.findPublicSeriesStarts(eq(seriesId), any(), any())).thenReturn(List.of(upcomingStarts));
    }

    @Test
    void aMonth_listsItsDates_withStatusPriceAndSeatsLeft() {
        Event onSale = date(LocalDateTime.parse("2026-11-27T17:00:00"));
        Event unknown = date(LocalDateTime.parse("2026-11-28T17:00:00"));
        stubMonth(List.of(onSale, unknown), onSale.getStartDateTime(), LocalDateTime.parse("2026-12-04T17:00:00"));
        EventPriceFromDTO from = EventPriceFromDTO.builder().currentPrice(new BigDecimal("80.00"))
                .fullPrice(new BigDecimal("120.00")).tierName("Early bird").categoryName("Standard").build();
        when(seats.fetchSaleSummaries(anyCollection())).thenReturn(Map.of(onSale.getEventId(),
                new SeatCategoryGateway.SaleSummary(from, AvailabilityLevel.FEW_LEFT)));

        SeriesMonthDTO month = service.month(seriesId, "2026-11");

        assertThat(month.getTitle()).isEqualTo("Friday Comedy Night");
        assertThat(month.getMonth()).isEqualTo("2026-11");
        assertThat(month.getMonths()).containsExactly("2026-11", "2026-12");
        assertThat(month.getDates()).extracting(SeriesPublicDateDTO::getEventId)
                .containsExactly(onSale.getEventId(), unknown.getEventId());
        SeriesPublicDateDTO first = month.getDates().getFirst();
        assertThat(first.getSalesStatus()).isEqualTo(SalesStatus.ON_SALE);
        assertThat(first.getPriceFrom().getTierName()).isEqualTo("Early bird");
        assertThat(first.getAvailability()).isEqualTo(AvailabilityLevel.FEW_LEFT);
        assertThat(month.getDates().get(1).getAvailability())
                .as("no seat-service answer for the date").isEqualTo(AvailabilityLevel.UNKNOWN);
        assertThat(month.getDates().get(1).getPriceFrom()).isNull();
    }

    @Test
    void theMonthIsTheMarketsCalendar_notUtcs() {
        stubMonth(List.of());

        service.month(seriesId, "2026-12");

        // 1 December 00:00 in Harare is 30 November 22:00 UTC.
        verify(eventRepository).findPublicSeriesDates(eq(seriesId),
                eq(LocalDateTime.parse("2026-11-30T22:00:00")), eq(LocalDateTime.parse("2026-12-31T22:00:00")),
                eq(NOW_UTC), any());
    }

    @Test
    void noMonth_opensOnTheMonthOfTheNextDate() {
        Event january = date(LocalDateTime.parse("2027-01-08T17:00:00"));
        when(eventRepository.findPublicSeriesDates(eq(seriesId), any(), any(), any(), any()))
                .thenAnswer(inv -> ((LocalDateTime) inv.getArgument(1)).getMonthValue() == 12
                        ? List.of(january) : List.of());
        when(eventRepository.findPublicSeriesStarts(eq(seriesId), any(), any()))
                .thenReturn(List.of(january.getStartDateTime()));

        SeriesMonthDTO month = service.month(seriesId, null);

        assertThat(month.getMonth()).isEqualTo("2027-01");
        assertThat(month.getDates()).extracting(SeriesPublicDateDTO::getEventId).containsExactly(january.getEventId());
    }

    @Test
    void noMonth_andNoDates_isThisMonthEmpty() {
        stubMonth(List.of());

        SeriesMonthDTO month = service.month(seriesId, "");

        assertThat(month.getMonth()).isEqualTo("2026-11");
        assertThat(month.getMonths()).isEmpty();
        assertThat(month.getDates()).isEmpty();
        verifyNoInteractions(seats);
    }

    @Test
    void aDateThatEndedSinceItWasCached_isLeftOut() {
        Event endsSoon = Event.builder().eventId(UUID.randomUUID()).seriesId(seriesId).seriesState("READY")
                .startDateTime(NOW_UTC.minusHours(2)).endDateTime(NOW_UTC.plusMinutes(5)).active(true).build();
        stubMonth(List.of(endsSoon), endsSoon.getStartDateTime());
        assertThat(service.month(seriesId, "2026-11").getDates()).hasSize(1);

        service.setClock(Clock.fixed(NOW.plus(Duration.ofMinutes(10)), ZoneOffset.UTC));

        assertThat(service.month(seriesId, "2026-11").getDates()).isEmpty();
    }

    @Test
    void theDatesAreCached_butPriceAndSeatsLeftAreAskedEveryTime() {
        Event d = date(LocalDateTime.parse("2026-11-27T17:00:00"));
        stubMonth(List.of(d), d.getStartDateTime());

        service.month(seriesId, "2026-11");
        service.month(seriesId, "2026-11");

        verify(eventRepository, times(1)).findPublicSeriesDates(eq(seriesId), any(), any(), any(), any());
        verify(eventRepository, times(1)).findPublicSeriesStarts(eq(seriesId), any(), any());
        verify(seats, times(2)).fetchSaleSummaries(anyCollection());
        // The visibility check is live on every call.
        verify(eventRepository, times(2)).findById(templateId);
    }

    @Test
    void aSeriesNeverPublished_rejected_unknown_orSwitchedOff_isNotFound() {
        template.setApprovedAt(null);
        assertThatThrownBy(() -> service.month(seriesId, null)).isInstanceOf(NotFoundException.class)
                .hasMessage("Series not found");

        template.setApprovedAt(NOW_UTC.minusDays(1));
        template.setRejected(true);
        assertThatThrownBy(() -> service.month(seriesId, null)).isInstanceOf(NotFoundException.class)
                .hasMessage("Series not found");

        UUID unknown = UUID.randomUUID();
        when(seriesRepository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.month(unknown, null)).isInstanceOf(NotFoundException.class)
                .hasMessage("Series not found");

        when(policy.enabled()).thenReturn(false);
        assertThatThrownBy(() -> service.month(seriesId, null)).isInstanceOf(NotFoundException.class)
                .hasMessage("Not found");
        verify(eventRepository, never()).findPublicSeriesDates(any(), any(), any(), any(), any());
    }

    @Test
    void aMalformedMonth_isAFieldError() {
        for (String bad : List.of("2026-13", "12-2026", "2026-1", "December")) {
            assertThatThrownBy(() -> service.month(seriesId, bad))
                    .as(bad).isInstanceOf(FieldErrorsException.class)
                    .satisfies(e -> assertThat(((FieldErrorsException) e).getErrors())
                            .containsEntry("month", "Use a month like 2026-12."));
        }
    }

    @Test
    void aMonthFarOut_answersNoDates_andIsNeverQueriedOrCached() {
        stubMonth(List.of(), LocalDateTime.parse("2026-12-04T17:00:00"));

        SeriesMonthDTO far = service.month(seriesId, "2031-01");
        SeriesMonthDTO past = service.month(seriesId, "2025-06");

        assertThat(far.getMonth()).isEqualTo("2031-01");
        assertThat(far.getDates()).isEmpty();
        assertThat(far.getMonths()).containsExactly("2026-12");
        assertThat(past.getDates()).isEmpty();
        // Only this month's entry was loaded, for the month list.
        verify(eventRepository, times(1)).findPublicSeriesDates(eq(seriesId),
                eq(LocalDateTime.parse("2026-10-31T22:00:00")), any(), any(), any());
        verify(eventRepository, times(1)).findPublicSeriesDates(any(), any(), any(), any(), any());
    }
}
