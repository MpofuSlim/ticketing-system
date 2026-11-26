package com.innbucks.eventservice.service;

import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventSalesWindowChange;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventSalesWindowChangeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stopping and restarting sales on a series' dates (V25, TIC-71). Called inside
 * the caller's transaction, beside the series' own status change.
 *
 * <p><b>A pause closes sales; it does not unpublish.</b> Each date's sales end
 * moves to the moment of the pause, so the date reads {@code SALES_CLOSED}
 * everywhere (booking-service refuses a new booking or payment for it), stays
 * listed, and its ticket holders and gate scans are unaffected. Unpublishing
 * would take the date off every list and off its holders' screens. The date's
 * own sales end is kept and put back on resume. Both moves are recorded in the
 * date's sales-window history like any other change.
 */
@Component
@Slf4j
public class SeriesPauses {

    private final EventRepository eventRepository;
    private final EventSalesWindowChangeRepository salesWindowChanges;

    public SeriesPauses(EventRepository eventRepository, EventSalesWindowChangeRepository salesWindowChanges) {
        this.eventRepository = eventRepository;
        this.salesWindowChanges = salesWindowChanges;
    }

    /**
     * Closes sales on every date of the series that has not ended and that it
     * has not already paused. A date whose sales already closed keeps its own
     * end. Returns the dates paused.
     */
    public List<Event> pause(UUID seriesId, LocalDateTime nowUtc) {
        List<Event> dates = eventRepository.findSeriesDatesToPause(seriesId, nowUtc);
        for (Event date : dates) {
            pause(date, nowUtc);
        }
        eventRepository.saveAll(dates);
        log.info("Series sales paused seriesId={} dates={}", seriesId, dates.size());
        return dates;
    }

    /** Closes one date's sales now, remembering its own end. */
    public void pause(Event date, LocalDateTime nowUtc) {
        LocalDateTime ownEnd = date.getSalesEndAt();
        LocalDateTime closedAt = ownEnd != null && !ownEnd.isAfter(nowUtc) ? ownEnd : nowUtc;
        date.setSeriesPausedAt(nowUtc);
        date.setSeriesPausedSalesEndAt(ownEnd);
        date.setSalesEndAt(closedAt);
        record(date, ownEnd, closedAt);
    }

    /** Puts back the sales end of every date the series paused. Returns the dates. */
    public List<Event> restore(UUID seriesId) {
        List<Event> dates = eventRepository.findSeriesPausedDates(seriesId);
        for (Event date : dates) {
            LocalDateTime pausedEnd = date.getSalesEndAt();
            LocalDateTime ownEnd = date.getSeriesPausedSalesEndAt();
            date.setSalesEndAt(ownEnd);
            date.setSeriesPausedAt(null);
            date.setSeriesPausedSalesEndAt(null);
            record(date, pausedEnd, ownEnd);
        }
        eventRepository.saveAll(dates);
        log.info("Series sales resumed seriesId={} dates={}", seriesId, dates.size());
        return dates;
    }

    private void record(Event date, LocalDateTime oldEnd, LocalDateTime newEnd) {
        if (Objects.equals(oldEnd, newEnd)) {
            return;
        }
        salesWindowChanges.save(EventSalesWindowChange.builder()
                .eventId(date.getEventId())
                .changeType(EventSalesWindowChange.ChangeType.CHANGED)
                .oldSalesStartAt(date.getSalesStartAt())
                .newSalesStartAt(date.getSalesStartAt())
                .oldSalesEndAt(oldEnd)
                .newSalesEndAt(newEnd)
                .build());
    }
}
