package com.innbucks.bookingservice.dto;

import com.innbucks.bookingservice.entity.Booking;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// One row per booked seat in a given category. Returned by
// GET /bookings/by-category/{id} for cross-service analytics. Includes both
// booking-level fields (who, when, status) and seat-level fields
// (seatId, ticketNumber) so the consumer can build a full picture.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryBookingDTO {

    private UUID bookingId;
    private String userEmail;
    /**
     * The PURCHASER's full name (V22) — who paid for the booking this ticket
     * belongs to. Null on bookings that pre-date the column.
     */
    private String customerName;
    /**
     * The purchaser's phone number, as captured on the booking. Surfaced so an
     * organizer can actually contact the buyer from the bookings report —
     * guest checkouts frequently have no {@code userEmail}, which left the
     * report with no way to identify a customer at all.
     *
     * <p>PII: the endpoints returning this are authenticated and scoped to the
     * requesting organizer's OWN events (SUPER_ADMIN excepted), so the number
     * only ever reaches the merchant whose customer it is. Never log it — the
     * service logs ids only, and {@code MsisdnMasking} exists for anywhere it
     * must appear in a log line.
     */
    private String phoneNumber;
    private UUID eventId;
    private Booking.BookingStatus status;
    private String confirmationNumber;
    private UUID seatId;
    private UUID categoryId;
    private String categoryName;
    private String rowLabel;
    private Integer seatNumber;
    private String ticketNumber;
    private BigDecimal priceAtBooking;
    /**
     * Named attendee for THIS ticket (V22), when the purchaser gave one; null
     * = the purchaser's own ticket. This is the "who is actually coming"
     * answer: one row per ticket, so a 3-ticket booking with two named guests
     * yields three rows — one with no attendee (the buyer) and two named.
     * Contact fields follow the same organizer-scoped PII rule as
     * {@link #phoneNumber}.
     */
    private String attendeeName;
    private String attendeeEmail;
    private String attendeePhone;
    /**
     * Convenience: {@code attendeeName} when set, else {@code customerName} —
     * the name to print on a guest list without the consumer re-deriving it.
     */
    private String holderName;
    private LocalDateTime bookedAt;
    private LocalDateTime updatedAt;
    // For PENDING bookings: the instant the seat hold lapses. Null for
    // CONFIRMED (paid) and CANCELLED bookings.
    private LocalDateTime expiresAt;
}
