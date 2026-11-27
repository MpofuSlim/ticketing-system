package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.SeriesDateIdsDTO;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.DependencyUnavailableException;
import com.innbucks.bookingservice.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.UUID;

/**
 * Turns a report's {@code seriesId} into the series' dates and the organizer
 * whose bookings to read (TIC-72). Asks event-service live, with no
 * transaction open (the controller calls this before the report), and fails
 * closed: no answer is a 503, never a report over nothing.
 *
 * <ul>
 *   <li>An organizer reads only their own series. Anyone else's, and an
 *       unknown one, is the same 404 {@value #SERIES_NOT_FOUND}.</li>
 *   <li>Platform staff read any series, scoped to its owner, so the fetch is
 *       one organizer's bookings, as an organizer's own report is.</li>
 * </ul>
 */
@Service
@Slf4j
public class SeriesReportScope {

    public static final String SERIES_NOT_FOUND = "Series not found";
    public static final String EVENT_AND_SERIES = "Send eventId or seriesId, not both.";

    private final EventServiceClient eventClient;
    private final String internalToken;

    public SeriesReportScope(EventServiceClient eventClient,
                             @Value("${innbucks.internal-api-token:}") String internalToken) {
        this.eventClient = eventClient;
        this.internalToken = internalToken;
    }

    /** The organizer to read and the events to keep. */
    public record Resolved(@Nullable UUID organizerUuid, ReportEvents events) { }

    /**
     * @param organizerUuid the caller's scope: their organizer uuid, or null for platform staff
     * @param eventId       the request's eventId, or null
     * @param seriesId      the request's seriesId, or null
     */
    public Resolved resolve(@Nullable UUID organizerUuid, @Nullable UUID eventId, @Nullable UUID seriesId) {
        if (seriesId == null) {
            return new Resolved(organizerUuid, ReportEvents.event(eventId));
        }
        if (eventId != null) {
            throw new BadRequestException(EVENT_AND_SERIES);
        }
        SeriesDateIdsDTO dates = ask(seriesId);
        if (!dates.isFound() || dates.getTenantUserUuid() == null
                || (organizerUuid != null && !organizerUuid.equals(dates.getTenantUserUuid()))) {
            throw new NotFoundException(SERIES_NOT_FOUND);
        }
        return new Resolved(dates.getTenantUserUuid(), ReportEvents.series(seriesId,
                dates.getEventIds() == null ? java.util.Set.of() : new HashSet<>(dates.getEventIds())));
    }

    private SeriesDateIdsDTO ask(UUID seriesId) {
        ApiResult<SeriesDateIdsDTO> answer;
        try {
            answer = eventClient.seriesDates(seriesId, internalToken);
        } catch (RuntimeException e) {
            log.warn("Series report: event-service could not be asked seriesId={}: {}", seriesId, e.toString());
            answer = null;
        }
        if (answer == null || answer.getData() == null) {
            throw new DependencyUnavailableException("event-service could not list the dates of series " + seriesId);
        }
        return answer.getData();
    }
}
