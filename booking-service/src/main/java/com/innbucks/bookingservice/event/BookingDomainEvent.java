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

    record BookingConfirmed(
            UUID bookingId,
            String userEmail,
            String confirmationNumber,
            Instant occurredAt
    ) implements BookingDomainEvent {
        public static BookingConfirmed of(Booking b) {
            return new BookingConfirmed(
                    b.getId(),
                    b.getUserEmail(),
                    b.getConfirmationNumber(),
                    Instant.now());
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
