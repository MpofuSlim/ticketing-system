package com.innbucks.eventservice.service;

import com.innbucks.eventservice.cache.PublicEventCatalog;
import com.innbucks.eventservice.client.BookingGateway;
import com.innbucks.eventservice.client.BookingNotificationGateway;
import com.innbucks.eventservice.client.SeatLayoutGateway;
import com.innbucks.eventservice.config.MarketTimeZone;
import com.innbucks.eventservice.dto.series.EventSeriesResponseDTO;
import com.innbucks.eventservice.dto.series.FutureDatesRequestDTO;
import com.innbucks.eventservice.dto.series.FuturePriceRequestDTO;
import com.innbucks.eventservice.dto.series.SeriesChangeDTO;
import com.innbucks.eventservice.dto.series.SeriesChangeDTO.DateChange;
import com.innbucks.eventservice.dto.series.SeriesChangeDTO.Outcome;
import com.innbucks.eventservice.dto.series.SeriesScheduleRequestDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventSalesWindowChange;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.entity.Location;
import com.innbucks.eventservice.entity.SeriesState;
import com.innbucks.eventservice.exception.BadRequestException;
import com.innbucks.eventservice.exception.ConflictException;
import com.innbucks.eventservice.exception.FieldErrorsException;
import com.innbucks.eventservice.exception.NotFoundException;
import com.innbucks.eventservice.exception.ServiceUnavailableException;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventSalesWindowChangeRepository;
import com.innbucks.eventservice.repository.EventSeriesRepository;
import com.innbucks.eventservice.util.HtmlSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Changing a recurring series after it is set up (TIC-71): its schedule, every
 * future date at once, one ticket type's price on every future date, skipping
 * one date, pausing, resuming and ending. One date on its own is changed with
 * the ordinary event endpoints, since every date is an ordinary event.
 *
 * <p><b>Nobody loses a ticket.</b> A change that would remove a date removes
 * it only when nobody holds a ticket for it ({@link #removeUnbooked}); a date
 * people hold tickets for is kept and reported. A date that moves, or whose
 * venue changes, messages everyone booked on it, as an edit of one event does.
 * A price change never touches a sold ticket: each keeps the price it was sold
 * at.
 *
 * <p><b>No remote call runs inside a transaction.</b> Booking counts, booking
 * messages and seat-service's prices are asked between this class's short
 * write phases.
 */
@Service
@Slf4j
public class SeriesEditService {

    static final String SERIES_ENDED = EventSeriesService.SERIES_ENDED;
    static final String SERIES_CHANGED = "This series was changed by someone else just now. Refresh it and try again.";
    static final String NOT_LIVE = "Only a live series can be paused. Publish it first.";
    static final String ALREADY_PAUSED = "This series is already paused.";
    static final String NOT_PAUSED = "This series isn't paused.";
    static final String REJECTED = "This series was rejected. Publish it again for review, then resume it.";
    static final String NOTHING_TO_CHANGE = "Send at least one change.";
    static final String COUNTS_UNAVAILABLE =
            "We couldn't check the bookings just now, so nothing was changed. Try again in a minute.";
    static final String PRICES_UNAVAILABLE = "We couldn't reach the ticket prices just now. Try again in a minute: "
            + "any price already changed stays as it is.";
    static final String DATE_NOT_FOUND = "Date not found";
    static final String DATE_STARTED = "This date has already started.";
    static final String KEPT_DESCRIPTION = "description";
    static final String KEPT_BANNER = "banner";

    private final EventSeriesService seriesService;
    private final EventSeriesRepository seriesRepository;
    private final EventRepository eventRepository;
    private final SeriesPauses pauses;
    private final BookingGateway bookingGateway;
    private final BookingNotificationGateway notifications;
    private final SeatLayoutGateway seatLayoutGateway;
    private final MarketTimeZone marketTimeZone;
    private final PublicEventCatalog publicEventCatalog;
    private final EventSalesWindowChangeRepository salesWindowChanges;
    private final TransactionTemplate writeTx;
    private Clock clock = Clock.systemUTC();

    public SeriesEditService(EventSeriesService seriesService,
                             EventSeriesRepository seriesRepository,
                             EventRepository eventRepository,
                             SeriesPauses pauses,
                             BookingGateway bookingGateway,
                             BookingNotificationGateway notifications,
                             SeatLayoutGateway seatLayoutGateway,
                             MarketTimeZone marketTimeZone,
                             PublicEventCatalog publicEventCatalog,
                             EventSalesWindowChangeRepository salesWindowChanges,
                             PlatformTransactionManager transactionManager) {
        this.seriesService = seriesService;
        this.seriesRepository = seriesRepository;
        this.eventRepository = eventRepository;
        this.pauses = pauses;
        this.bookingGateway = bookingGateway;
        this.notifications = notifications;
        this.seatLayoutGateway = seatLayoutGateway;
        this.marketTimeZone = marketTimeZone;
        this.publicEventCatalog = publicEventCatalog;
        this.salesWindowChanges = salesWindowChanges;
        this.writeTx = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ---- The schedule ---------------------------------------------------------

    /**
     * Replaces the series' schedule. The same rules as a new series'. Dates not
     * made yet follow it at once (the generator's next run). A date already
     * made that the new schedule no longer has is removed when nobody holds a
     * ticket for it, and kept when someone does: its start stays free for a
     * later schedule to use again. Dates the schedule still has keep their own
     * length and sales window; the new length and sales offsets are for new
     * dates.
     */
    public SeriesChangeDTO updateSchedule(UUID organizerUuid, boolean staff, UUID seriesId,
                                          SeriesScheduleRequestDTO request) {
        seriesService.requireEnabled();
        EventSeries series = seriesService.readable(organizerUuid, staff, seriesId);
        requireNotEnded(series);
        EventSeriesService.CheckedSchedule checked = seriesService.checkSchedule(request.getDaysOfWeek(),
                request.getStartTimes(), request.getDurationMinutes(), request.getStartDate(), request.getEndDate(),
                request.getSkipDates(), request.getExtraStarts(), request.getBookingWindowDays(),
                request.getSalesOpenMinutesBefore(), request.getSalesCloseMinutesBefore(),
                series.getExtraStarts(), "seriesId=" + seriesId);
        SeriesSchedule schedule = checked.schedule();
        Long version = series.getVersion();
        LocalDateTime nowUtc = nowUtc();

        List<Event> future = eventRepository.findFutureSeriesDates(seriesId, nowUtc);
        List<UUID> misfits = new ArrayList<>();
        if (!future.isEmpty()) {
            LocalDateTime nowLocal = marketTimeZone.toMarketLocal(nowUtc);
            LocalDateTime lastLocal = marketTimeZone.toMarketLocal(future.get(future.size() - 1).getStartDateTime());
            Set<LocalDateTime> starts = new HashSet<>(schedule.startsBetween(nowLocal, lastLocal.plusMinutes(1)));
            for (Event date : future) {
                if (!starts.contains(marketTimeZone.toMarketLocal(date.getStartDateTime()))) {
                    misfits.add(date.getEventId());
                }
            }
        }
        LocalDateTime templateStart = marketTimeZone.toUtc(checked.firstLocal());
        List<DateChange> changes = removeUnbooked(misfits, true, () -> {
            EventSeries locked = lockedSeries(seriesId, version);
            locked.setDaysOfWeek(new ArrayList<>(schedule.storedDays()));
            locked.setStartTimes(new ArrayList<>(schedule.storedStartTimes()));
            locked.setSkipDates(new ArrayList<>(schedule.storedSkipDates()));
            locked.setExtraStarts(new ArrayList<>(schedule.storedExtraStarts()));
            locked.setDurationMinutes(request.getDurationMinutes());
            locked.setStartDate(request.getStartDate());
            locked.setEndDate(request.getEndDate());
            locked.setBookingWindowDays(checked.window());
            locked.setSalesOpenMinutesBefore(request.getSalesOpenMinutesBefore());
            locked.setSalesCloseMinutesBefore(request.getSalesCloseMinutesBefore());
            seriesRepository.save(locked);
            // The template's times are the series' next start (the generator keeps them there).
            eventRepository.findById(locked.getTemplateEventId())
                    .filter(template -> !template.isDeleted() && SeriesState.isTemplate(template))
                    .ifPresent(template -> {
                        template.setStartDateTime(templateStart);
                        template.setEndDateTime(templateStart.plusMinutes(request.getDurationMinutes()));
                        eventRepository.save(template);
                    });
        });
        publicEventCatalog.invalidateAll();
        log.info("Series schedule changed seriesId={} days={} times={} datesRemoved={} datesKept={}", seriesId,
                schedule.storedDays(), schedule.storedStartTimes(), count(changes, Outcome.REMOVED),
                count(changes, Outcome.KEPT_HAS_BOOKINGS));
        return result(organizerUuid, staff, seriesId, changes);
    }

    // ---- Every future date ------------------------------------------------------

    /** What one date's edit did, for the messages sent after the commit. */
    private record Edited(UUID eventId, String title, LocalDateTime newStart, String newVenue) {
    }

    /**
     * Changes the template and every date that has not started: title,
     * description, venue, category, location, and optionally one start time
     * moved to another the same day. The schedule's start times change with the
     * move, so new dates follow it. A move takes a date the organizer deleted
     * along too, so its start stays blocked and the generator never makes it
     * again; an extra start is never moved. Everyone booked on a date that
     * moved, or whose venue changed, is messaged after the commit.
     */
    public SeriesChangeDTO updateFutureDates(UUID organizerUuid, boolean staff, UUID seriesId,
                                             FutureDatesRequestDTO request) {
        seriesService.requireEnabled();
        EventSeries series = seriesService.readable(organizerUuid, staff, seriesId);
        requireNotEnded(series);
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.getTitle() != null && request.getTitle().isBlank()) {
            errors.put("title", "Title can't be blank.");
        }
        if (request.getVenue() != null && request.getVenue().isBlank()) {
            errors.put("venue", "Venue can't be blank.");
        }
        LocalTime from = null;
        LocalTime to = null;
        FutureDatesRequestDTO.MoveStartTime move = request.getMoveStartTime();
        if (move != null) {
            Set<LocalTime> current = new HashSet<>();
            series.getStartTimes().forEach(t -> current.add(SeriesSchedule.parseTime(t)));
            from = SeriesSchedule.parseTime(move.getFrom());
            to = SeriesSchedule.parseTime(move.getTo());
            if (from == null) {
                errors.put("moveStartTime.from", "Use the 24-hour time, like 19:00.");
            } else if (!current.contains(from)) {
                errors.put("moveStartTime.from", "The schedule has no start at " + move.getFrom().trim() + ".");
            }
            if (to == null) {
                errors.put("moveStartTime.to", "Use the 24-hour time, like 19:30.");
            } else if (current.contains(to)) {
                errors.put("moveStartTime.to", "The schedule already has a start at " + move.getTo().trim() + ".");
            }
        }
        boolean fields = request.getTitle() != null || request.getDescription() != null
                || request.getVenue() != null || request.getCategory() != null || request.getLocation() != null;
        if (!fields && move == null) {
            throw new BadRequestException(NOTHING_TO_CHANGE);
        }
        if (!errors.isEmpty()) {
            throw new FieldErrorsException(errors);
        }
        Long version = series.getVersion();
        LocalTime moveFrom = from;
        LocalTime moveTo = to;
        LocalDateTime nowUtc = nowUtc();

        boolean otherFields = request.getTitle() != null || request.getVenue() != null
                || request.getCategory() != null || request.getLocation() != null;

        List<Edited> edited = new ArrayList<>();
        List<DateChange> changes = inTx(() -> {
            EventSeries locked = lockedSeries(seriesId, version);
            // The description every date still showing the series' own carries.
            // A date whose description differs was given its own and keeps it.
            Optional<Event> template = eventRepository.findById(locked.getTemplateEventId())
                    .filter(t -> !t.isDeleted());
            String seriesDescription = template.map(Event::getDescription).orElse(null);
            template.ifPresent(t -> {
                applyFields(t, request, true);
                eventRepository.save(t);
            });
            List<Event> dates = move == null
                    ? eventRepository.findFutureSeriesDates(seriesId, nowUtc)
                    : eventRepository.findFutureSeriesDatesWithDeleted(seriesId, nowUtc);
            Set<LocalDateTime> extras = new HashSet<>();
            locked.getExtraStarts().forEach(e -> {
                LocalDateTime parsed = SeriesSchedule.parseExtra(e);
                if (parsed != null) {
                    extras.add(parsed);
                }
            });
            Set<LocalDateTime> occupied = move == null ? Set.of()
                    : new HashSet<>(eventRepository.findSeriesStartsFrom(seriesId, nowUtc));
            List<DateChange> rows = new ArrayList<>();
            for (Event date : dates) {
                boolean live = !date.isDeleted();
                boolean ownDescription = request.getDescription() != null && template.isPresent()
                        && !Objects.equals(date.getDescription(), seriesDescription);
                boolean changesSomething = otherFields || (request.getDescription() != null && !ownDescription);
                Outcome outcome = live && fields ? (changesSomething ? Outcome.UPDATED : Outcome.KEPT_OWN) : null;
                boolean moved = false;
                if (move != null) {
                    LocalDateTime local = marketTimeZone.toMarketLocal(date.getStartDateTime());
                    if (local.toLocalTime().equals(moveFrom) && !extras.contains(local)) {
                        LocalDateTime newStart = marketTimeZone.toUtc(local.toLocalDate().atTime(moveTo));
                        if (!newStart.isAfter(nowUtc)) {
                            outcome = live ? Outcome.NOT_MOVED_PASSED : null;
                        } else if (occupied.contains(newStart)) {
                            outcome = live ? Outcome.NOT_MOVED_TAKEN : null;
                        } else {
                            occupied.remove(date.getStartDateTime());
                            occupied.add(newStart);
                            moveDate(date, newStart, live);
                            moved = true;
                            outcome = live ? Outcome.MOVED : null;
                        }
                    }
                }
                String oldVenue = date.getVenue();
                if (live && fields) {
                    applyFields(date, request, !ownDescription);
                }
                eventRepository.save(date);
                if (outcome == null) {
                    continue;
                }
                boolean venueChanged = request.getVenue() != null && !Objects.equals(oldVenue, date.getVenue());
                if (moved || venueChanged) {
                    edited.add(new Edited(date.getEventId(), date.getTitle(),
                            moved ? date.getStartDateTime() : null, venueChanged ? date.getVenue() : null));
                }
                rows.add(DateChange.builder().eventId(date.getEventId()).startDateTime(date.getStartDateTime())
                        .outcome(outcome).keptOwn(live && ownDescription ? List.of(KEPT_DESCRIPTION) : null)
                        .build());
            }
            if (move != null) {
                TreeSet<LocalTime> times = new TreeSet<>();
                locked.getStartTimes().forEach(t -> times.add(SeriesSchedule.parseTime(t)));
                times.remove(moveFrom);
                times.add(moveTo);
                locked.setStartTimes(new ArrayList<>(times.stream().map(SeriesSchedule.TIME::format).toList()));
                seriesRepository.save(locked);
            }
            return rows;
        });
        publicEventCatalog.invalidateAll();
        tellTicketHolders(edited, changes);
        log.info("Series future dates changed seriesId={} fields={} move={}->{} dates={} moved={} messaged={}",
                seriesId, fields, moveFrom, moveTo, changes.size(), count(changes, Outcome.MOVED), edited.size());
        return result(organizerUuid, staff, seriesId, changes);
    }

    private static void applyFields(Event event, FutureDatesRequestDTO request, boolean description) {
        // OWASP A03: the same sanitising as PUT /events/{id}.
        if (request.getTitle() != null) {
            event.setTitle(HtmlSanitizer.stripAll(request.getTitle()));
        }
        if (description && request.getDescription() != null) {
            event.setDescription(HtmlSanitizer.sanitizeRichText(request.getDescription()));
        }
        if (request.getVenue() != null) {
            event.setVenue(HtmlSanitizer.stripAll(request.getVenue()));
        }
        if (request.getCategory() != null) {
            event.setCategory(request.getCategory());
        }
        if (request.getLocation() != null) {
            event.setLocation(Location.builder()
                    .latitude(request.getLocation().getLatitude())
                    .longitude(request.getLocation().getLongitude())
                    .build());
        }
    }

    /**
     * Moves a date to {@code newStart}, keeping its length and its sales
     * window's distance from the start. A date its series paused keeps its
     * sales closed; its own sales end moves with it.
     */
    private void moveDate(Event date, LocalDateTime newStart, boolean live) {
        Duration shift = Duration.between(date.getStartDateTime(), newStart);
        LocalDateTime oldSalesStart = date.getSalesStartAt();
        LocalDateTime oldSalesEnd = date.getSalesEndAt();
        date.setStartDateTime(newStart);
        date.setEndDateTime(date.getEndDateTime().plus(shift));
        if (oldSalesStart != null) {
            date.setSalesStartAt(oldSalesStart.plus(shift));
        }
        if (date.getSeriesPausedAt() != null) {
            if (date.getSeriesPausedSalesEndAt() != null) {
                date.setSeriesPausedSalesEndAt(date.getSeriesPausedSalesEndAt().plus(shift));
            }
        } else if (oldSalesEnd != null) {
            date.setSalesEndAt(oldSalesEnd.plus(shift));
        }
        if (live && (!Objects.equals(oldSalesStart, date.getSalesStartAt())
                || !Objects.equals(oldSalesEnd, date.getSalesEndAt()))) {
            salesWindowChanges.save(EventSalesWindowChange.builder()
                    .eventId(date.getEventId())
                    .changeType(EventSalesWindowChange.ChangeType.CHANGED)
                    .oldSalesStartAt(oldSalesStart)
                    .newSalesStartAt(date.getSalesStartAt())
                    .oldSalesEndAt(oldSalesEnd)
                    .newSalesEndAt(date.getSalesEndAt())
                    .build());
        }
    }

    /**
     * Messages everyone booked on each date that moved or changed venue, the
     * message one event's edit sends. Best-effort, after the commit: the
     * gateway logs a failure and moves on. Dates nobody booked are skipped when
     * booking-service can say so; when it can't, every one is asked, and
     * booking-service sends nothing for a date with no bookings.
     */
    private void tellTicketHolders(List<Edited> edited, List<DateChange> changes) {
        if (edited.isEmpty()) {
            return;
        }
        Optional<Map<UUID, Long>> counts = bookingGateway.activeCountsIfKnown(
                edited.stream().map(Edited::eventId).toList());
        counts.ifPresent(known -> changes.forEach(change -> {
            if (edited.stream().anyMatch(e -> e.eventId().equals(change.getEventId()))) {
                change.setActiveBookings(known.getOrDefault(change.getEventId(), 0L));
            }
        }));
        for (Edited date : edited) {
            if (counts.isPresent() && counts.get().getOrDefault(date.eventId(), 0L) == 0L) {
                continue;
            }
            notifications.notifyEventChange(date.eventId(), BookingNotificationGateway.CHANGE_UPDATED, date.title(),
                    date.newStart() == null ? null : String.valueOf(date.newStart()), date.newVenue());
        }
    }

    // ---- One price on every future date -----------------------------------------

    /**
     * Sets one ticket type's full price on the template and every date that
     * has not started, through seat-service, by the ticket type's name (the
     * dates' types were copied from the template by name). Each date is
     * changed on its own: one with a price tier still open at or above the new
     * price is refused and reported, the others change. Sold tickets keep
     * their price. Asked with no transaction open; {@code actor} goes into each
     * category's price history.
     */
    public SeriesChangeDTO setFuturePrice(UUID organizerUuid, boolean staff, String actor, UUID seriesId,
                                          FuturePriceRequestDTO request) {
        seriesService.requireEnabled();
        EventSeries series = seriesService.readable(organizerUuid, staff, seriesId);
        requireNotEnded(series);
        String name = request.getCategoryName().strip();
        List<Event> future = eventRepository.findFutureSeriesDates(seriesId, nowUtc());
        Map<UUID, Event> byId = new LinkedHashMap<>();
        future.forEach(date -> byId.put(date.getEventId(), date));
        List<UUID> ids = new ArrayList<>();
        ids.add(series.getTemplateEventId());
        ids.addAll(byId.keySet());

        SeatLayoutGateway.PriceChange answer = seatLayoutGateway.setPriceByName(ids, name, request.getPrice(), actor);
        if (answer.outcome() == SeatLayoutGateway.Outcome.UNAVAILABLE) {
            throw new ServiceUnavailableException(PRICES_UNAVAILABLE);
        }
        if (answer.outcome() == SeatLayoutGateway.Outcome.REFUSED) {
            throw new BadRequestException(answer.message() == null || answer.message().isBlank()
                    ? "The price was refused." : answer.message());
        }
        List<DateChange> changes = new ArrayList<>();
        for (SeatLayoutGateway.PriceRow row : answer.rows()) {
            boolean template = series.getTemplateEventId().equals(row.getEventId());
            Event date = byId.get(row.getEventId());
            changes.add(DateChange.builder()
                    .eventId(row.getEventId())
                    .template(template ? Boolean.TRUE : null)
                    .startDateTime(date == null ? null : date.getStartDateTime())
                    .outcome(priceOutcome(row.getOutcome()))
                    .message(row.getMessage())
                    .build());
        }
        if (!changes.isEmpty() && changes.stream().allMatch(c -> c.getOutcome() == Outcome.NO_SUCH_TICKET_TYPE)) {
            throw new BadRequestException("This series has no ticket type called \"" + name + "\".");
        }
        log.info("Series future price set seriesId={} ticketType={} price={} dates={} updated={} refused={}",
                seriesId, name, request.getPrice(), changes.size(), count(changes, Outcome.PRICE_UPDATED),
                count(changes, Outcome.PRICE_REFUSED));
        return result(organizerUuid, staff, seriesId, changes);
    }

    private static Outcome priceOutcome(String seatOutcome) {
        if (seatOutcome == null) {
            return Outcome.PRICE_REFUSED;
        }
        return switch (seatOutcome) {
            case "UPDATED" -> Outcome.PRICE_UPDATED;
            case "UNCHANGED" -> Outcome.PRICE_UNCHANGED;
            case "NO_SUCH_CATEGORY" -> Outcome.NO_SUCH_TICKET_TYPE;
            default -> Outcome.PRICE_REFUSED;
        };
    }

    // ---- Skipping one date ------------------------------------------------------

    /**
     * Skips one date that has not started: removed when nobody holds a ticket
     * for it, refused (409) when someone does. A skipped date stays in the
     * series, so the generator never makes its start again.
     */
    public SeriesChangeDTO skipDate(UUID organizerUuid, boolean staff, UUID seriesId, UUID eventId) {
        seriesService.requireEnabled();
        seriesService.readable(organizerUuid, staff, seriesId);
        Event date = eventRepository.findById(eventId)
                .filter(e -> seriesId.equals(e.getSeriesId()) && !e.isDeleted() && !SeriesState.isTemplate(e))
                .orElseThrow(() -> new NotFoundException(DATE_NOT_FOUND));
        if (!date.getStartDateTime().isAfter(nowUtc())) {
            throw new ConflictException(DATE_STARTED);
        }
        List<DateChange> changes = removeUnbooked(List.of(eventId), false, null);
        DateChange change = changes.isEmpty() ? null : changes.get(0);
        if (change != null && change.getOutcome() == Outcome.KEPT_HAS_BOOKINGS) {
            long held = change.getActiveBookings() == null ? 0 : change.getActiveBookings();
            throw new ConflictException("People hold tickets for this date (" + held + " active "
                    + (held == 1 ? "booking" : "bookings") + "), so it can't be skipped. Change the date instead, "
                    + "or contact them first.");
        }
        publicEventCatalog.invalidateAll();
        log.info("Series date skipped seriesId={} eventId={}", seriesId, eventId);
        return result(organizerUuid, staff, seriesId, changes);
    }

    // ---- Pause, resume, end -----------------------------------------------------

    /**
     * Pauses a live series: no new dates, and every date that has not ended
     * stops selling (its sales close now; it stays listed and its tickets stay
     * valid). Resume reopens them.
     */
    public SeriesChangeDTO pause(UUID organizerUuid, boolean staff, UUID seriesId) {
        seriesService.requireEnabled();
        EventSeries series = seriesService.readable(organizerUuid, staff, seriesId);
        requireNotEnded(series);
        if (series.getStatus() == EventSeries.Status.PAUSED) {
            throw new ConflictException(ALREADY_PAUSED);
        }
        if (series.getStatus() != EventSeries.Status.ACTIVE) {
            throw new ConflictException(NOT_LIVE);
        }
        Long version = series.getVersion();
        LocalDateTime nowUtc = nowUtc();
        List<DateChange> changes = inTx(() -> {
            EventSeries locked = lockedSeries(seriesId, version);
            locked.setStatus(EventSeries.Status.PAUSED);
            seriesRepository.save(locked);
            return pauses.pause(seriesId, nowUtc).stream()
                    .map(date -> change(date, Outcome.SALES_PAUSED)).toList();
        });
        publicEventCatalog.invalidateAll();
        log.info("Series paused seriesId={} dates={}", seriesId, changes.size());
        return result(organizerUuid, staff, seriesId, changes);
    }

    /** Resumes a paused series: dates are made again, and every date the pause closed sells again. */
    public SeriesChangeDTO resume(UUID organizerUuid, boolean staff, UUID seriesId) {
        seriesService.requireEnabled();
        EventSeries series = seriesService.readable(organizerUuid, staff, seriesId);
        requireNotEnded(series);
        if (series.getStatus() != EventSeries.Status.PAUSED) {
            throw new ConflictException(NOT_PAUSED);
        }
        Event template = eventRepository.findById(series.getTemplateEventId()).orElse(null);
        if (template != null && template.isRejected()) {
            throw new ConflictException(REJECTED);
        }
        Long version = series.getVersion();
        List<DateChange> changes = inTx(() -> {
            EventSeries locked = lockedSeries(seriesId, version);
            locked.setStatus(EventSeries.Status.ACTIVE);
            seriesRepository.save(locked);
            return pauses.restore(seriesId).stream()
                    .map(date -> change(date, Outcome.SALES_RESUMED)).toList();
        });
        publicEventCatalog.invalidateAll();
        log.info("Series resumed seriesId={} dates={}", seriesId, changes.size());
        return result(organizerUuid, staff, seriesId, changes);
    }

    /**
     * Ends a series for good: no new dates; every future date nobody holds a
     * ticket for is removed; a date people hold tickets for goes ahead as
     * before (a paused one sells again). The same as deleting the template,
     * plus the removal of the unbooked dates.
     */
    public SeriesChangeDTO end(UUID organizerUuid, boolean staff, UUID seriesId) {
        seriesService.requireEnabled();
        EventSeries series = seriesService.readable(organizerUuid, staff, seriesId);
        requireNotEnded(series);
        Long version = series.getVersion();
        List<UUID> future = eventRepository.findFutureSeriesDates(seriesId, nowUtc()).stream()
                .map(Event::getEventId).toList();
        List<DateChange> changes = removeUnbooked(future, false, () -> {
            EventSeries locked = lockedSeries(seriesId, version);
            if (locked.getStatus() == EventSeries.Status.PAUSED) {
                pauses.restore(seriesId);
            }
            locked.setStatus(EventSeries.Status.ENDED);
            seriesRepository.save(locked);
            eventRepository.findById(locked.getTemplateEventId())
                    .filter(template -> !template.isDeleted())
                    .ifPresent(template -> {
                        template.setDeleted(true);
                        template.setActive(false);
                        eventRepository.save(template);
                    });
        });
        publicEventCatalog.invalidateAll();
        log.info("Series ended seriesId={} datesRemoved={} datesKept={}", seriesId,
                count(changes, Outcome.REMOVED), count(changes, Outcome.KEPT_HAS_BOOKINGS));
        return result(organizerUuid, staff, seriesId, changes);
    }

    // ---- Removing dates nobody booked -------------------------------------------

    /** A date being considered for removal, and whether it was published before. */
    private record Candidate(UUID eventId, boolean wasActive) {
    }

    /**
     * Removes the dates nobody holds a ticket for, in three steps, with no
     * remote call inside a transaction:
     * <ol>
     *   <li>one transaction takes each date off sale (unpublished), so no
     *       booking can start while its bookings are counted (booking-service
     *       asks event-service live before every booking);</li>
     *   <li>booking-service counts each date's active bookings, FAIL CLOSED
     *       ({@link BookingGateway#activeCountsIfKnown});</li>
     *   <li>one transaction runs {@code alsoInTx} (the change the removal
     *       belongs to), removes each date with none, and puts every other
     *       back as it was.</li>
     * </ol>
     * When the bookings can't be counted, or step 3 fails, every date goes back
     * as it was and nothing else changes. A removed date is soft-deleted; with
     * {@code detach} it also leaves the series, so a later schedule can make
     * its start again.
     */
    List<DateChange> removeUnbooked(List<UUID> dateIds, boolean detach, Runnable alsoInTx) {
        LocalDateTime nowUtc = nowUtc();
        List<Candidate> candidates = dateIds.isEmpty() ? List.of() : inTx(() -> {
            List<Candidate> taken = new ArrayList<>();
            for (UUID id : dateIds) {
                Event date = eventRepository.lockById(id).orElse(null);
                if (date == null || date.isDeleted() || !date.getStartDateTime().isAfter(nowUtc)) {
                    continue;
                }
                taken.add(new Candidate(id, date.isActive()));
                if (date.isActive()) {
                    date.setActive(false);
                    eventRepository.save(date);
                }
            }
            return taken;
        });
        if (!candidates.isEmpty()) {
            publicEventCatalog.invalidateAll();
        }
        Map<UUID, Long> counts;
        if (candidates.isEmpty()) {
            counts = Map.of();
        } else {
            Optional<Map<UUID, Long>> known = bookingGateway.activeCountsIfKnown(
                    candidates.stream().map(Candidate::eventId).toList());
            if (known.isEmpty()) {
                putBack(candidates);
                log.warn("Series dates not removed, bookings could not be counted dates={}", candidates.size());
                throw new ServiceUnavailableException(COUNTS_UNAVAILABLE);
            }
            counts = known.get();
        }
        try {
            return inTx(() -> {
                if (alsoInTx != null) {
                    alsoInTx.run();
                }
                List<DateChange> rows = new ArrayList<>();
                for (Candidate candidate : candidates) {
                    Event date = eventRepository.lockById(candidate.eventId()).orElse(null);
                    if (date == null) {
                        continue;
                    }
                    long held = counts.getOrDefault(candidate.eventId(), 0L);
                    LocalDateTime start = date.getStartDateTime();
                    if (held > 0) {
                        date.setActive(candidate.wasActive());
                        eventRepository.save(date);
                        rows.add(DateChange.builder().eventId(date.getEventId()).startDateTime(start)
                                .outcome(Outcome.KEPT_HAS_BOOKINGS).activeBookings(held).build());
                        continue;
                    }
                    date.setDeleted(true);
                    date.setActive(false);
                    if (detach) {
                        date.setSeriesId(null);
                        date.setSeriesState(null);
                    }
                    eventRepository.save(date);
                    rows.add(DateChange.builder().eventId(date.getEventId()).startDateTime(start)
                            .outcome(Outcome.REMOVED).activeBookings(0L).build());
                }
                return rows;
            });
        } catch (RuntimeException e) {
            putBack(candidates);
            throw e;
        }
    }

    /** Puts every candidate back as it was before step 1. */
    private void putBack(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        try {
            inTx(() -> {
                for (Candidate candidate : candidates) {
                    eventRepository.lockById(candidate.eventId())
                            .filter(date -> !date.isDeleted() && date.isActive() != candidate.wasActive())
                            .ifPresent(date -> {
                                date.setActive(candidate.wasActive());
                                eventRepository.save(date);
                            });
                }
                return null;
            });
        } finally {
            publicEventCatalog.invalidateAll();
        }
    }

    // ---- Helpers ------------------------------------------------------------------

    /**
     * Sets the series' banner (TIC-121): the template's, and that of every date
     * that has not started and still shows the series' banner. A date given its
     * own banner (through {@code POST /events/{id}/banner}) keeps it, reported
     * as {@link Outcome#KEPT_OWN}. "Still shows the series' banner" is the
     * date's bytes being exactly the template's before this change, compared by
     * md5 inside Postgres; a series with no banner yet reaches every date with
     * none. The bytes are copied from the template inside Postgres, never read
     * into the service.
     */
    public SeriesChangeDTO replaceSeriesBanner(UUID organizerUuid, boolean staff, UUID seriesId,
                                               MultipartFile banner) {
        seriesService.requireEnabled();
        EventSeries series = seriesService.readable(organizerUuid, staff, seriesId);
        requireNotEnded(series);
        EventService.ValidatedBanner validated = EventService.requiredBanner(banner);
        return setSeriesBanner(organizerUuid, staff, series, validated);
    }

    /**
     * Removes the series' banner from the template and from every date that has
     * not started and still shows it; a date with its own banner keeps it.
     * Safe to repeat: a series with no banner changes only the dates that have
     * none either, which is no change.
     */
    public SeriesChangeDTO clearSeriesBanner(UUID organizerUuid, boolean staff, UUID seriesId) {
        seriesService.requireEnabled();
        EventSeries series = seriesService.readable(organizerUuid, staff, seriesId);
        requireNotEnded(series);
        return setSeriesBanner(organizerUuid, staff, series, null);
    }

    private SeriesChangeDTO setSeriesBanner(UUID organizerUuid, boolean staff, EventSeries series,
                                            EventService.ValidatedBanner banner) {
        UUID seriesId = series.getSeriesId();
        Long version = series.getVersion();
        LocalDateTime nowUtc = nowUtc();
        String contentType = banner == null ? null : banner.contentType();
        List<DateChange> changes = inTx(() -> {
            EventSeries locked = lockedSeries(seriesId, version);
            // The template row is locked first, so two banner changes queue and
            // the second compares the dates with the first one's banner.
            Event template = eventRepository.lockById(locked.getTemplateEventId())
                    .filter(t -> !t.isDeleted())
                    .orElseThrow(() -> new NotFoundException(EventSeriesService.SERIES_NOT_FOUND));
            String seriesHash = eventRepository.findBannerHash(template.getEventId()).orElse(null);
            String seriesType = template.getBannerContentType();
            Set<UUID> following = new HashSet<>(
                    eventRepository.findFutureSeriesDatesWithBanner(seriesId, nowUtc, seriesHash));
            if (banner == null) {
                eventRepository.clearBannerImage(template.getEventId());
            } else {
                eventRepository.writeBannerImage(template.getEventId(), banner.bytes());
            }
            // The bytes are unmapped, so stamp updatedAt: it versions bannerUrl.
            template.setBannerContentType(contentType);
            template.setUpdatedAt(nowUtc);
            eventRepository.save(template);
            List<DateChange> rows = new ArrayList<>();
            for (Event date : eventRepository.findFutureSeriesDates(seriesId, nowUtc)) {
                if (!following.contains(date.getEventId())
                        || !Objects.equals(date.getBannerContentType(), seriesType)) {
                    rows.add(DateChange.builder().eventId(date.getEventId())
                            .startDateTime(date.getStartDateTime()).outcome(Outcome.KEPT_OWN)
                            .keptOwn(List.of(KEPT_BANNER)).build());
                    continue;
                }
                if (banner == null) {
                    eventRepository.clearBannerImage(date.getEventId());
                } else {
                    eventRepository.copyBannerImage(template.getEventId(), date.getEventId());
                }
                date.setBannerContentType(contentType);
                date.setUpdatedAt(nowUtc);
                eventRepository.save(date);
                rows.add(change(date, Outcome.UPDATED));
            }
            return rows;
        });
        publicEventCatalog.invalidateAll();
        log.info("Series banner {} seriesId={} dates={} updated={} keptOwn={}",
                banner == null ? "cleared" : "replaced", seriesId, changes.size(),
                count(changes, Outcome.UPDATED), count(changes, Outcome.KEPT_OWN));
        return result(organizerUuid, staff, seriesId, changes);
    }

    private static void requireNotEnded(EventSeries series) {
        if (series.getStatus() == EventSeries.Status.ENDED) {
            throw new ConflictException(SERIES_ENDED);
        }
    }

    /**
     * The series row for a write, refused when someone changed it since the
     * caller read it (its {@code @Version} also guards the save) or when it has
     * ended meanwhile.
     */
    private EventSeries lockedSeries(UUID seriesId, Long version) {
        EventSeries locked = seriesRepository.findById(seriesId)
                .orElseThrow(() -> new NotFoundException(EventSeriesService.SERIES_NOT_FOUND));
        if (!Objects.equals(locked.getVersion(), version)) {
            throw new ConflictException(SERIES_CHANGED);
        }
        requireNotEnded(locked);
        return locked;
    }

    private static DateChange change(Event date, Outcome outcome) {
        return DateChange.builder().eventId(date.getEventId()).startDateTime(date.getStartDateTime())
                .outcome(outcome).build();
    }

    private static long count(List<DateChange> changes, Outcome outcome) {
        return changes.stream().filter(c -> c.getOutcome() == outcome).count();
    }

    private SeriesChangeDTO result(UUID organizerUuid, boolean staff, UUID seriesId, List<DateChange> changes) {
        EventSeriesResponseDTO series = seriesService.get(organizerUuid, staff, seriesId);
        return SeriesChangeDTO.builder().series(series).dates(changes).build();
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private <T> T inTx(Supplier<T> work) {
        return writeTx == null ? work.get() : writeTx.execute(status -> work.get());
    }
}
