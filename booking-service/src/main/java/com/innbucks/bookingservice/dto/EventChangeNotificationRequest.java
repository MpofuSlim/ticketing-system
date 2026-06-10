package com.innbucks.bookingservice.dto;

/**
 * Internal request body for {@code POST /bookings/internal/events/{eventId}/
 * change-notification}, sent by event-service when an organizer changes an
 * event's time/venue or cancels it. booking-service fans the message out to the
 * event's confirmed attendees.
 *
 * <p>The body is pre-decided by event-service: {@code newStartDateTime} and
 * {@code newVenue} are only populated (UPDATED only) when that field actually
 * changed, so a null means "don't mention it". For CANCELLED both are null.
 *
 * @param changeType       {@code "UPDATED"} or {@code "CANCELLED"}.
 * @param eventTitle       Human-readable event title for the message.
 * @param newStartDateTime New start date/time (ISO-8601 string) if it changed; else null.
 * @param newVenue         New venue if it changed; else null.
 */
public record EventChangeNotificationRequest(
        String changeType,
        String eventTitle,
        String newStartDateTime,
        String newVenue
) {}
