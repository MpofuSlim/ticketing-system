package com.innbucks.eventservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * Whether an event's tickets can be bought right now. Worked out at the moment
 * a response is built, never stored and never cached: nothing runs when the
 * sales time arrives, so every read applies the same rule to the current time.
 *
 * <p>The ONE rule, used by every event response and by the internal sales
 * window booking-service asks before each sale:
 * <ol>
 *   <li>not active, or rejected: {@link #NOT_PUBLISHED};</li>
 *   <li>at or after the event's end: {@link #ENDED};</li>
 *   <li>before the sales start (when one is set): {@link #NOT_YET_ON_SALE};</li>
 *   <li>otherwise {@link #ON_SALE}.</li>
 * </ol>
 * Sales close when the event ENDS, not when it starts, so door sales keep
 * working. Times are UTC wall clocks, as stored.
 */
@Schema(description = """
        Whether tickets can be bought right now, worked out when the response was built.
        ON_SALE: buyable. NOT_YET_ON_SALE: published, sales open at salesStartDateTime.
        ENDED: the event's end has passed. NOT_PUBLISHED: a draft or a rejected event (only its owner and staff see it).
        """)
public enum SalesStatus {
    ON_SALE,
    NOT_YET_ON_SALE,
    ENDED,
    NOT_PUBLISHED;

    public static SalesStatus of(boolean active, boolean rejected, LocalDateTime salesStartAt,
                                 LocalDateTime endAt, LocalDateTime nowUtc) {
        if (!active || rejected) {
            return NOT_PUBLISHED;
        }
        if (endAt != null && !nowUtc.isBefore(endAt)) {
            return ENDED;
        }
        if (salesStartAt != null && nowUtc.isBefore(salesStartAt)) {
            return NOT_YET_ON_SALE;
        }
        return ON_SALE;
    }
}
