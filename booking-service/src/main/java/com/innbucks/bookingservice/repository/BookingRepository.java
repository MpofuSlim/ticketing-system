package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByUserEmail(String userEmail);

    Optional<Booking> findByConfirmationNumber(String confirmationNumber);

    List<Booking> findByEventId(UUID eventId);

    // Attendees to notify on an event change/cancel: only CONFIRMED bookings
    // (PENDING holds and CANCELLED bookings aren't ticket holders).
    List<Booking> findByEventIdAndStatus(UUID eventId, Booking.BookingStatus status);

    // Phone-number lookup (most-recent first). One phone may have many
    // bookings across events / over time, so this returns a list rather than
    // an Optional. See GET /bookings/phone/{phoneNumber}.
    List<Booking> findByPhoneNumberOrderByCreatedAtDesc(String phoneNumber);

    // PENDING bookings whose seat hold has lapsed. Used by the expiration
    // scheduler to auto-cancel them and publish BookingCancelled events.
    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.PENDING
          AND b.expiresAt IS NOT NULL
          AND b.expiresAt < :now
    """)
    List<Booking> findExpiredPending(@Param("now") LocalDateTime now);

    // EventReminderScheduler scan: which events still have CONFIRMED bookings
    // that were never considered for the pre-event reminder. Bounded by the
    // partial index idx_bookings_reminder_pending (V18).
    @Query("""
            select distinct b.eventId from Booking b
            where b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.CONFIRMED
              and b.reminderSentAt is null
            """)
    List<UUID> findEventIdsWithUnremindedConfirmed();

    List<Booking> findByEventIdAndStatusAndReminderSentAtIsNull(
            UUID eventId, Booking.BookingStatus status);

    // Same scan for the T-2-DAYS reminder stage (SMS + email). Bounded by
    // idx_bookings_reminder2d_pending (V19).
    @Query("""
            select distinct b.eventId from Booking b
            where b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.CONFIRMED
              and b.reminder2dSentAt is null
            """)
    List<UUID> findEventIdsWithUn2dRemindedConfirmed();

    List<Booking> findByEventIdAndStatusAndReminder2dSentAtIsNull(
            UUID eventId, Booking.BookingStatus status);

    // OrganizerEventReminderScheduler scan: events with CONFIRMED bookings
    // whose organizer hasn't had the day-before headline email yet (no marker
    // row in organizer_event_reminders, V21).
    @Query("""
            select distinct b.eventId from Booking b
            where b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.CONFIRMED
              and b.eventId not in (
                  select r.eventId from OrganizerEventReminder r)
            """)
    List<UUID> findEventIdsForOrganizerReminder();

    long countByEventIdAndStatus(UUID eventId, Booking.BookingStatus status);

    // Tickets sold for one event = booking items of its CONFIRMED bookings.
    @Query("""
            select count(i) from Booking b join b.items i
            where b.eventId = :eventId
              and b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.CONFIRMED
            """)
    long countConfirmedTickets(@Param("eventId") UUID eventId);

    // Booking + items in one round trip, for the manual ticket-resend path:
    // delivery reads the items OUTSIDE any transaction (no connection held
    // across the WhatsApp/email network calls), so lazy loading is not an
    // option there. DISTINCT collapses the fetch-join fan-out.
    @Query("""
        SELECT DISTINCT b FROM Booking b
        LEFT JOIN FETCH b.items
        WHERE b.id = :id
    """)
    Optional<Booking> findByIdWithItems(@Param("id") UUID id);
}
