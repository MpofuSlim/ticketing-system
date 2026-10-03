package com.innbucks.bookingservice.event;

import com.innbucks.bookingservice.entity.Booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain events published by booking-service after a successful DB commit.
 * Sealed because the publisher dispatches by exact subtype to a topic per
 * event class; adding a new event must opt in explicitly.
 */
public sealed interface BookingDomainEvent
        permits BookingDomainEvent.BookingCreated,
                BookingDomainEvent.BookingConfirmed,
                BookingDomainEvent.BookingCancelled {

    UUID bookingId();
    Instant occurredAt();

    record BookingCreated(
            UUID bookingId,
            UUID eventId,
            String userEmail,
            String confirmationNumber,
            BigDecimal totalAmount,
            List<UUID> seatIds,
            Instant occurredAt
    ) implements BookingDomainEvent {
        public static BookingCreated of(Booking b, List<UUID> seatIds) {
            return new BookingCreated(
                    b.getId(),
                    b.getEventId(),
                    b.getUserEmail(),
                    b.getConfirmationNumber(),
                    b.getTotalAmount(),
                    seatIds,
                    Instant.now());
        }
    }

    /**
     * {@code eventId} + {@code ticketCount} let the post-commit availability
     * decrement ({@code EventAvailabilityConsumeListener}) run without reading
     * the booking back. Published ONLY on a real PENDING -> CONFIRMED flip,
     * never on an idempotent replay.
     */
    record BookingConfirmed(
            UUID bookingId,
            String userEmail,
            String confirmationNumber,
            Instant occurredAt,
            UUID eventId,
            int ticketCount
    ) implements BookingDomainEvent {
        /** Pre-availability shape — no event id, nothing to decrement. */
        public BookingConfirmed(UUID bookingId, String userEmail, String confirmationNumber,
                                Instant occurredAt) {
            this(bookingId, userEmail, confirmationNumber, occurredAt, null, 0);
        }

        public static BookingConfirmed of(Booking b) {
            return new BookingConfirmed(
                    b.getId(),
                    b.getUserEmail(),
                    b.getConfirmationNumber(),
                    Instant.now(),
                    b.getEventId(),
                    b.getItems() == null ? 0 : b.getItems().size());
        }
    }

    record BookingCancelled(
            UUID bookingId,
            String userEmail,
            String confirmationNumber,
            Instant occurredAt
    ) implements BookingDomainEvent {
        public static BookingCancelled of(Booking b) {
            return new BookingCancelled(
                    b.getId(),
                    b.getUserEmail(),
                    b.getConfirmationNumber(),
                    Instant.now());
        }
    }
}
