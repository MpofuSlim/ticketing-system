package com.innbucks.eventservice.service;

import com.innbucks.eventservice.config.MarketTimeZone;
import com.innbucks.eventservice.dto.CreateEventRequestDTO;
import com.innbucks.eventservice.dto.ReviewStatus;
import com.innbucks.eventservice.dto.SalesStatus;
import com.innbucks.eventservice.dto.series.CreateSeriesRequestDTO;
import com.innbucks.eventservice.dto.series.EventSeriesResponseDTO;
import com.innbucks.eventservice.dto.series.SeriesDateDTO;
import com.innbucks.eventservice.dto.series.SeriesScheduleDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.entity.SeriesState;
import com.innbucks.eventservice.exception.BadRequestException;
import com.innbucks.eventservice.exception.ConflictException;
import com.innbucks.eventservice.exception.FieldErrorsException;
import com.innbucks.eventservice.exception.NotFoundException;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventSeriesRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Recurring event series (V24, TIC-70): set one up, list them, read one, and
 * publish it. A series is reviewed, published, unpublished, rejected and
 * deleted through its template with the ordinary event endpoints too; publish
 * here is that same call. {@link SeriesDateGenerator} makes the dates.
 */
@Service
@Slf4j
public class EventSeriesService {

    static final String NOT_FOUND = "Not found";
    static final String SERIES_NOT_FOUND = "Series not found";
    static final String STAFF_CANNOT_CREATE =
            "A series must belong to an event organizer. Sign in as the organizer to set one up.";
    static final String SERIES_ENDED = "This series has ended. Set up a new one.";
    /** How many upcoming dates the detail view lists. */
    static final int DETAIL_DATES = 50;
    /** How many next starts the detail view previews. */
    static final int PREVIEW_STARTS = 10;

    private final EventSeriesRepository seriesRepository;
    private final EventRepository eventRepository;
    private final EventService eventService;
    private final MarketTimeZone marketTimeZone;
    private final SeriesPolicy policy;
    private Clock clock = Clock.systemUTC();

    public EventSeriesService(EventSeriesRepository seriesRepository,
                              EventRepository eventRepository,
                              EventService eventService,
                              MarketTimeZone marketTimeZone,
                              SeriesPolicy policy) {
        this.seriesRepository = seriesRepository;
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.marketTimeZone = marketTimeZone;
        this.policy = policy;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * A new series, in DRAFT, with its template. The organizer then sets the
     * template up (ticket types, tiers, add-ons, questions, banner, team) and
     * publishes; no date exists until the series is live.
     */
    @Transactional
    public EventSeriesResponseDTO create(UUID organizerUuid, String country, CreateSeriesRequestDTO request) {
        requireEnabled();
        if (organizerUuid == null) {
            throw new BadRequestException(STAFF_CANNOT_CREATE);
        }
        Map<String, String> errors = new LinkedHashMap<>();
        int durationMinutes = request.getDurationMinutes() == null ? 0 : request.getDurationMinutes();
        SeriesSchedule schedule = SeriesSchedule.parse(request.getDaysOfWeek(), request.getStartTimes(),
                durationMinutes, request.getStartDate(), request.getEndDate(), request.getSkipDates(),
                request.getExtraStarts(), errors);
        int window = request.getBookingWindowDays() == null
                ? SeriesSchedule.DEFAULT_BOOKING_WINDOW_DAYS : request.getBookingWindowDays();
        Integer open = request.getSalesOpenMinutesBefore();
        Integer close = request.getSalesCloseMinutesBefore();
        if (open != null && close != null && close >= open) {
            errors.put("salesCloseMinutesBefore",
                    "Must be less than salesOpenMinutesBefore: sales close after they open.");
        }
        LocalDateTime nowLocal = marketTimeZone.toMarketLocal(nowUtc());
        LocalDateTime firstLocal = null;
        if (schedule != null) {
            List<String> extras = request.getExtraStarts() == null ? List.of() : request.getExtraStarts();
            for (int i = 0; i < extras.size(); i++) {
                LocalDateTime extra = SeriesSchedule.parseExtra(extras.get(i));
                if (extra != null && !extra.isAfter(nowLocal)) {
                    errors.put("extraStarts[" + i + "]", "That start has passed.");
                }
            }
            Optional<LocalDateTime> first = schedule.firstStartFrom(nowLocal);
            if (first.isEmpty()) {
                errors.put("startDate", "This schedule makes no date from now on. Check the days, the dates and "
                        + "the skipped days.");
            } else {
                firstLocal = first.get();
            }
            int inWindow = schedule.startsBetween(nowLocal, nowLocal.plusDays(window)).size();
            if (inWindow > SeriesSchedule.MAX_DATES_IN_WINDOW) {
                errors.put("bookingWindowDays", "This schedule makes " + inWindow + " dates in " + window
                        + " days; at most " + SeriesSchedule.MAX_DATES_IN_WINDOW
                        + ". Shorten the booking window, or use fewer days or start times.");
            }
        }
        if (!errors.isEmpty()) {
            log.info("Series refused organizerUuid={} problems={}", organizerUuid, errors.keySet());
            throw new FieldErrorsException(errors);
        }

        CreateEventRequestDTO event = new CreateEventRequestDTO();
        event.setTitle(request.getTitle());
        event.setDescription(request.getDescription());
        event.setSettlementCode(request.getSettlementCode());
        event.setVenue(request.getVenue());
        event.setCategory(request.getCategory());
        event.setLocation(request.getLocation());
        event.setTotalCapacity(request.getTotalCapacity());
        event.setTermsText(request.getTermsText());
        event.setStartDateTime(firstLocal);
        event.setEndDateTime(firstLocal.plusMinutes(durationMinutes));
        Event template = eventService.createSeriesTemplate(organizerUuid, country, event);
        // The template row first: the series names it, then it names the series.
        eventRepository.flush();

        EventSeries series = EventSeries.builder()
                .seriesId(UUID.randomUUID())
                .templateEventId(template.getEventId())
                .tenantUserUuid(organizerUuid)
                .status(EventSeries.Status.DRAFT)
                .daysOfWeek(new java.util.ArrayList<>(schedule.storedDays()))
                .startTimes(new java.util.ArrayList<>(schedule.storedStartTimes()))
                .skipDates(new java.util.ArrayList<>(schedule.storedSkipDates()))
                .extraStarts(new java.util.ArrayList<>(schedule.storedExtraStarts()))
                .durationMinutes(durationMinutes)
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .bookingWindowDays(window)
                .salesOpenMinutesBefore(open)
                .salesCloseMinutesBefore(close)
                .build();
        seriesRepository.saveAndFlush(series);
        template.setSeriesId(series.getSeriesId());
        template.setSeriesState(SeriesState.TEMPLATE);
        eventRepository.saveAndFlush(template);
        log.info("Series created seriesId={} templateEventId={} organizerUuid={} days={} times={} window={}",
                series.getSeriesId(), template.getEventId(), organizerUuid, series.getDaysOfWeek(),
                series.getStartTimes(), window);
        return detail(series, template);
    }

    /** The caller's series, newest first; platform staff see every organizer's. */
    @Transactional(readOnly = true)
    public Page<EventSeriesResponseDTO> list(UUID organizerUuid, boolean staff, int page, int size) {
        requireEnabled();
        PageRequest pageable = PageRequest.of(page, size);
        Page<EventSeries> series;
        if (staff) {
            series = seriesRepository.findAllByOrderByCreatedAtDesc(pageable);
        } else if (organizerUuid != null) {
            series = seriesRepository.findByTenantUserUuidOrderByCreatedAtDesc(organizerUuid, pageable);
        } else {
            return Page.empty(pageable);
        }
        Map<UUID, Event> templates = eventRepository.findAllById(
                        series.getContent().stream().map(EventSeries::getTemplateEventId).toList())
                .stream().collect(Collectors.toMap(Event::getEventId, Function.identity()));
        return series.map(s -> summary(s, templates.get(s.getTemplateEventId())));
    }

    /** One series with its next starts and upcoming dates. Someone else's is the same 404 as a missing one. */
    @Transactional(readOnly = true)
    public EventSeriesResponseDTO get(UUID organizerUuid, boolean staff, UUID seriesId) {
        requireEnabled();
        EventSeries series = readable(organizerUuid, staff, seriesId);
        Event template = eventRepository.findById(series.getTemplateEventId()).orElse(null);
        return detail(series, template);
    }

    /**
     * Publishes the series: the same call as {@code PUT /events/{templateId}/activate},
     * with its review rules. Live (or approved, with the review step on), the
     * series makes its dates within minutes. Not transactional on purpose: the
     * publish commits in its own write phase and sends its notices after it.
     */
    public EventSeriesResponseDTO publish(UUID organizerUuid, String role, boolean staff, UUID seriesId) {
        requireEnabled();
        EventSeries series = readable(organizerUuid, staff, seriesId);
        if (series.getStatus() == EventSeries.Status.ENDED) {
            throw new ConflictException(SERIES_ENDED);
        }
        eventService.activateEvent(organizerUuid, role, series.getTemplateEventId());
        return get(organizerUuid, staff, seriesId);
    }

    private EventSeries readable(UUID organizerUuid, boolean staff, UUID seriesId) {
        EventSeries series = seriesRepository.findById(seriesId)
                .orElseThrow(() -> new NotFoundException(SERIES_NOT_FOUND));
        if (!staff && !series.getTenantUserUuid().equals(organizerUuid)) {
            throw new NotFoundException(SERIES_NOT_FOUND);
        }
        return series;
    }

    private void requireEnabled() {
        if (!policy.enabled()) {
            throw new NotFoundException(NOT_FOUND);
        }
    }

    private EventSeriesResponseDTO summary(EventSeries series, Event template) {
        return EventSeriesResponseDTO.builder()
                .seriesId(series.getSeriesId())
                .templateEventId(series.getTemplateEventId())
                .tenantUserUuid(series.getTenantUserUuid())
                .status(series.getStatus())
                .reviewStatus(template == null ? null : ReviewStatus.of(template))
                .rejectionReason(template == null ? null : template.getRejectionReason())
                .title(template == null ? null : template.getTitle())
                .venue(template == null ? null : template.getVenue())
                .schedule(SeriesScheduleDTO.builder()
                        .daysOfWeek(series.getDaysOfWeek())
                        .startTimes(series.getStartTimes())
                        .durationMinutes(series.getDurationMinutes())
                        .startDate(series.getStartDate())
                        .endDate(series.getEndDate())
                        .skipDates(series.getSkipDates())
                        .extraStarts(series.getExtraStarts())
                        .bookingWindowDays(series.getBookingWindowDays())
                        .salesOpenMinutesBefore(series.getSalesOpenMinutesBefore())
                        .salesCloseMinutesBefore(series.getSalesCloseMinutesBefore())
                        .build())
                .createdAt(series.getCreatedAt())
                .updatedAt(series.getUpdatedAt())
                .build();
    }

    private EventSeriesResponseDTO detail(EventSeries series, Event template) {
        EventSeriesResponseDTO dto = summary(series, template);
        LocalDateTime nowUtc = nowUtc();
        LocalDateTime nowLocal = marketTimeZone.toMarketLocal(nowUtc);
        SeriesSchedule schedule = SeriesSchedule.of(series);
        // A year ahead is far more than ten starts of any schedule that has them.
        dto.setNextStarts(schedule.startsBetween(nowLocal, nowLocal.plusDays(366)).stream()
                .limit(PREVIEW_STARTS)
                .map(marketTimeZone::toUtc)
                .toList());
        Page<Event> dates = eventRepository.findUpcomingSeriesDates(series.getSeriesId(), nowUtc,
                PageRequest.of(0, DETAIL_DATES));
        dto.setUpcomingDates(dates.getContent().stream().map(date -> SeriesDateDTO.builder()
                .eventId(date.getEventId())
                .startDateTime(date.getStartDateTime())
                .endDateTime(date.getEndDateTime())
                .salesStatus(SalesStatus.of(date.isActive(), date.isRejected(), date.getSalesStartAt(),
                        date.getSalesEndAt(), date.getEndDateTime(), nowUtc))
                .active(date.isActive())
                .build()).toList());
        dto.setUpcomingDateCount(dates.getTotalElements());
        dto.setLastDateStart(eventRepository.findLastSeriesStart(series.getSeriesId()));
        dto.setDatesInProgress(eventRepository.countBySeriesIdAndSeriesStateAndDeletedFalse(
                series.getSeriesId(), SeriesState.GENERATING));
        return dto;
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
