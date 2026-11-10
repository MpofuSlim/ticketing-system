package com.innbucks.eventservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * Whether an event's tickets can be bought right now. Worked out at the moment
 * a response is built, never stored and never cached: nothing runs when a sales
 * time arrives, so every read applies the same rule to the current time.
 *
 * <p>The ONE rule, used by every event response, by the organizer list's
 * {@code salesStatus} filter (EventRepository, the same rule in SQL) and by the
 * internal sales window booking-service asks before each sale:
 * <ol>
 *   <li>not active, or rejected: {@link #NOT_PUBLISHED};</li>
 *   <li>at or after the event's end: {@link #ENDED};</li>
 *   <li>at or after the sales end (when one is set): {@link #SALES_CLOSED};</li>
 *   <li>before the sales start (when one is set): {@link #NOT_YET_ON_SALE};</li>
 *   <li>otherwise {@link #ON_SALE}.</li>
 * </ol>
 * With no sales end, sales close when the event ENDS, not when it starts, so
 * door sales keep working. The sales end is always after the sales start and
 * no later than the event's end (EventService refuses anything else), so steps
 * 3 and 4 never both apply. Times are UTC wall clocks, as stored.
 */
@Schema(description = """
        Whether tickets can be bought right now, worked out when the response was built.
        ON_SALE: buyable. NOT_YET_ON_SALE: published, sales open at salesStartDateTime.
        SALES_CLOSED: published and not over, but sales closed at salesEndDateTime.
        ENDED: the event's end has passed. NOT_PUBLISHED: a draft or a rejected event (only its owner and staff see it).
        """)
public enum SalesStatus {
    ON_SALE,
    NOT_YET_ON_SALE,
    SALES_CLOSED,
    ENDED,
    NOT_PUBLISHED;

    public static SalesStatus of(boolean active, boolean rejected, LocalDateTime salesStartAt,
                                 LocalDateTime salesEndAt, LocalDateTime endAt, LocalDateTime nowUtc) {
        if (!active || rejected) {
            return NOT_PUBLISHED;
        }
        if (endAt != null && !nowUtc.isBefore(endAt)) {
            return ENDED;
        }
        if (salesEndAt != null && !nowUtc.isBefore(salesEndAt)) {
            return SALES_CLOSED;
        }
        if (salesStartAt != null && nowUtc.isBefore(salesStartAt)) {
            return NOT_YET_ON_SALE;
        }
        return ON_SALE;
    }
}
