package com.innbucks.eventservice.service;

import com.innbucks.eventservice.cache.EventSnapshot;
import com.innbucks.eventservice.cache.PublicEventCatalog;
import com.innbucks.eventservice.client.SeatCategoryGateway;
import com.innbucks.eventservice.config.MarketTimeZone;
import com.innbucks.eventservice.dto.AvailabilityLevel;
import com.innbucks.eventservice.dto.EventResponseDTO;
import com.innbucks.eventservice.dto.series.SeriesMonthDTO;
import com.innbucks.eventservice.dto.series.SeriesPublicDateDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.exception.FieldErrorsException;
import com.innbucks.eventservice.exception.NotFoundException;
import com.innbucks.eventservice.mapper.EventMapper;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventSeriesRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A series' dates for buyers, a month at a time (TIC-73): the call behind a
 * date picker on Ticketize web and the Super app.
 *
 * <ul>
 *   <li><b>Which series.</b> One whose template is approved and not rejected:
 *       published at least once, and not taken down by staff. Anything else,
 *       or an unknown id, is the same 404 as {@code GET /events/{id}} for a
 *       draft. A paused or ended series stays readable: its dates are still
 *       public events.</li>
 *   <li><b>Which dates.</b> Made, published, not rejected, not deleted, and
 *       not ended. A date the organizer took down, or one still being made,
 *       is never listed.</li>
 *   <li><b>What is cached.</b> The series and its template are read live on
 *       every call. The month's dates and the month list are cached with the
 *       public list pages (30s, cleared by any event write on this pod). The
 *       sale status is worked out on every call; the price and the seats-left
 *       level come from the seat-category cache (30s) and are worked out per
 *       call too.</li>
 * </ul>
 */
@Service
public class SeriesDatesService {

    /** The most dates one month can list: ten starts a day for 31 days, with room. */
    static final int MAX_DATES_PER_MONTH = 500;
    /** How far ahead a month may be asked for; further out answers no dates, and nothing is cached. */
    static final int MAX_MONTHS_AHEAD = 36;
    /** The most upcoming starts read for the month list. */
    static final int MAX_STARTS_FOR_MONTHS = 2000;

    static final String BAD_MONTH = "Use a month like 2026-12.";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu-MM")
            .withResolverStyle(ResolverStyle.STRICT);

    private final EventSeriesRepository seriesRepository;
    private final EventRepository eventRepository;
    private final PublicEventCatalog catalog;
    private final SeatCategoryGateway seatCategoryGateway;
    private final EventMapper mapper;
    private final MarketTimeZone marketTimeZone;
    private final SeriesPolicy policy;
    private Clock clock = Clock.systemUTC();

    public SeriesDatesService(EventSeriesRepository seriesRepository, EventRepository eventRepository,
                              PublicEventCatalog catalog, SeatCategoryGateway seatCategoryGateway,
                              EventMapper mapper, MarketTimeZone marketTimeZone, SeriesPolicy policy) {
        this.seriesRepository = seriesRepository;
        this.eventRepository = eventRepository;
        this.catalog = catalog;
        this.seatCategoryGateway = seatCategoryGateway;
        this.mapper = mapper;
        this.marketTimeZone = marketTimeZone;
        this.policy = policy;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * One month of the series' public dates. {@code month} is {@code uuuu-MM}
     * in the market's calendar; blank means the month of the series' next
     * date, or this month when it has none.
     */
    public SeriesMonthDTO month(UUID seriesId, String month) {
        if (!policy.enabled()) {
            throw new NotFoundException(EventSeriesService.NOT_FOUND);
        }
        YearMonth asked = parseMonth(month);
        EventSeries series = seriesRepository.findById(seriesId)
                .orElseThrow(() -> new NotFoundException(EventSeriesService.SERIES_NOT_FOUND));
        Event template = eventRepository.findById(series.getTemplateEventId()).orElse(null);
        if (template == null || template.getApprovedAt() == null || template.isRejected()) {
            throw new NotFoundException(EventSeriesService.SERIES_NOT_FOUND);
        }

        LocalDateTime nowUtc = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        YearMonth shown = asked;
        PublicEventCatalog.SeriesMonthPage page;
        YearMonth thisMonth = YearMonth.from(marketTimeZone.toMarketLocal(nowUtc));
        if (shown != null) {
            if (shown.isBefore(thisMonth.minusMonths(1)) || shown.isAfter(thisMonth.plusMonths(MAX_MONTHS_AHEAD))) {
                // Nothing to show there; keep a caller's arbitrary months out of the cache.
                page = new PublicEventCatalog.SeriesMonthPage(List.of(), load(seriesId, thisMonth, nowUtc).months());
            } else {
                page = load(seriesId, shown, nowUtc);
            }
        } else {
            // The month list is the same in every entry, so read it from this month's.
            page = load(seriesId, thisMonth, nowUtc);
            shown = page.months().isEmpty() ? thisMonth : YearMonth.parse(page.months().getFirst(), MONTH);
            if (!shown.equals(thisMonth)) {
                page = load(seriesId, shown, nowUtc);
            }
        }

        List<EventSnapshot> live = page.dates().stream()
                .filter(Objects::nonNull)
                .filter(d -> d.endDateTime() == null || d.endDateTime().isAfter(nowUtc))
                .toList();
        Map<UUID, SeatCategoryGateway.SaleSummary> summaries = live.isEmpty() ? Map.of()
                : seatCategoryGateway.fetchSaleSummaries(live.stream().map(EventSnapshot::eventId).toList());
        Map<UUID, SeatCategoryGateway.SaleSummary> found = summaries == null ? Map.of() : summaries;

        List<SeriesPublicDateDTO> dates = new ArrayList<>(live.size());
        for (EventSnapshot snapshot : live) {
            EventResponseDTO dto = snapshot.toDto();
            SeatCategoryGateway.SaleSummary summary = found.get(snapshot.eventId());
            dates.add(SeriesPublicDateDTO.builder()
                    .eventId(dto.getEventId())
                    .startDateTime(dto.getStartDateTime())
                    .endDateTime(dto.getEndDateTime())
                    .salesStatus(dto.getSalesStatus())
                    .salesStartDateTime(dto.getSalesStartDateTime())
                    .salesEndDateTime(dto.getSalesEndDateTime())
                    .priceFrom(summary == null ? null : summary.priceFrom())
                    .availability(summary == null || summary.availability() == null
                            ? AvailabilityLevel.UNKNOWN : summary.availability())
                    .build());
        }
        return SeriesMonthDTO.builder()
                .seriesId(series.getSeriesId())
                .title(template.getTitle())
                .venue(template.getVenue())
                .month(shown.format(MONTH))
                .months(page.months())
                .dates(dates)
                .build();
    }

    private PublicEventCatalog.SeriesMonthPage load(UUID seriesId, YearMonth month, LocalDateTime nowUtc) {
        return catalog.seriesMonth(new PublicEventCatalog.SeriesMonthKey(seriesId, month), () -> {
            LocalDateTime fromUtc = marketTimeZone.toUtc(month.atDay(1).atStartOfDay());
            LocalDateTime toUtc = marketTimeZone.toUtc(month.plusMonths(1).atDay(1).atStartOfDay());
            List<Event> dates = eventRepository.findPublicSeriesDates(seriesId, fromUtc, toUtc, nowUtc,
                    PageRequest.of(0, MAX_DATES_PER_MONTH));
            List<LocalDateTime> starts = eventRepository.findPublicSeriesStarts(seriesId, nowUtc,
                    PageRequest.of(0, MAX_STARTS_FOR_MONTHS));
            Set<String> months = new LinkedHashSet<>();
            for (LocalDateTime start : starts == null ? List.<LocalDateTime>of() : starts) {
                months.add(YearMonth.from(marketTimeZone.toMarketLocal(start)).format(MONTH));
            }
            return PublicEventCatalog.SeriesMonthPage.of(dates == null ? List.of() : dates,
                    new ArrayList<>(months), mapper);
        });
    }

    static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(month.strip(), MONTH);
        } catch (DateTimeParseException e) {
            throw new FieldErrorsException(Collections.singletonMap("month", BAD_MONTH));
        }
    }
}
