package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.Booking;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    // The booking row's write lock. Every path that changes a booking's stock
    // takes it FIRST, before any category row (StockLedger has the order):
    // confirm (and its resurrect phase), cancel, extend-hold and the write
    // phase of reverse. No fetch join and no
    // DISTINCT, because Postgres refuses FOR UPDATE with DISTINCT and on the
    // nullable side of an outer join, so findByIdWithItems cannot be locked.
    // The caller loads the items afterwards in the same transaction (one
    // query). Waits for a lock another transaction holds.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> lockById(@Param("id") UUID id);

    // The expiry sweep's variant: a row another transaction holds (a confirm,
    // a cancel, a reversal mid-write) is SKIPPED, not waited for, so the sweep
    // never queues behind a buyer. Empty when the row is locked or gone; the
    // next sweep picks it up if it is still due.
    @Query(value = "SELECT * FROM bookings WHERE id = :id FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Booking> lockByIdSkipLocked(@Param("id") UUID id);

    // The expiry sweep's candidates, read with NO lock: PENDING holds that
    // lapsed before :now, oldest first, (expires_at, id) as a keyset cursor so
    // a booking the sweep has to skip never stops it reaching the ones after
    // it. Served by idx_bookings_pending_expires_at (V9). First page here,
    // later pages from findExpiryCandidatesAfter.
    @Query("""
        SELECT new com.innbucks.bookingservice.repository.ExpiryCandidate(b.id, b.expiresAt)
        FROM Booking b
        WHERE b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.PENDING
          AND b.expiresAt < :now
        ORDER BY b.expiresAt, b.id
    """)
    List<ExpiryCandidate> findExpiryCandidates(@Param("now") LocalDateTime now, Limit limit);

    @Query("""
        SELECT new com.innbucks.bookingservice.repository.ExpiryCandidate(b.id, b.expiresAt)
        FROM Booking b
        WHERE b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.PENDING
          AND b.expiresAt < :now
          AND (b.expiresAt > :afterExpiresAt OR (b.expiresAt = :afterExpiresAt AND b.id > :afterId))
        ORDER BY b.expiresAt, b.id
    """)
    List<ExpiryCandidate> findExpiryCandidatesAfter(@Param("now") LocalDateTime now,
                                                    @Param("afterExpiresAt") LocalDateTime afterExpiresAt,
                                                    @Param("afterId") UUID afterId,
                                                    Limit limit);

    // The sweep's state change, as one bulk statement under the row lock the
    // sweep already holds. Native, so no entity callback runs: it stamps
    // updated_at itself (:now, bound from Java in UTC) and bumps version, so an
    // optimistic writer that read the row before (extend-hold) loses with a
    // 409 rather than writing PENDING back. Clears the persistence context so
    // the locked entity, still PENDING in memory, can never be flushed over it.
    // Records the reason (V25): an expired hold may be resurrected by a payment
    // that lands late.
    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE bookings
           SET status = 'CANCELLED', cancel_reason = 'EXPIRED', expires_at = NULL, updated_at = :now,
               version = version + 1
         WHERE id = :id AND status = 'PENDING'
    """, nativeQuery = true)
    int markExpired(@Param("id") UUID id, @Param("now") LocalDateTime now);

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

    // Booking + items in one round trip. Booking.items is LAZY and
    // open-in-view is off, so every single-booking path that renders tickets
    // loads through here: the ticket delivery listener and the manual resend
    // (which read the items OUTSIDE any transaction — no connection is held
    // across the WhatsApp/email calls), the by-id views and the reverse's
    // read-only first phase. Not the locking writes: see lockById. DISTINCT
    // collapses the
    // fetch-join fan-out. Never add a fetch-joined collection to a PAGED
    // query: Hibernate would page in memory (HHH90003004).
    @Query("""
        SELECT DISTINCT b FROM Booking b
        LEFT JOIN FETCH b.items
        WHERE b.id = :id
    """)
    Optional<Booking> findByIdWithItems(@Param("id") UUID id);

    // Same, keyed by confirmation number: the owner lookup
    // (GET /bookings/confirmation/{n}) and the gate lookup both render the
    // booking's tickets.
    @Query("""
        SELECT DISTINCT b FROM Booking b
        LEFT JOIN FETCH b.items
        WHERE b.confirmationNumber = :confirmationNumber
    """)
    Optional<Booking> findByConfirmationNumberWithItems(@Param("confirmationNumber") String confirmationNumber);

    // --- promo codes (V27) ---

    /** Bookings that used the code and still count (not CANCELLED), for its max_uses. */
    long countByPromoCodeIdAndStatusNot(UUID promoCodeId, Booking.BookingStatus status);

    /** The same for one buyer, by phone, for max_uses_per_customer. */
    long countByPromoCodeIdAndPhoneNumberAndStatusNot(UUID promoCodeId, String phoneNumber,
                                                      Booking.BookingStatus status);

    /** The same by email, for a booking that carries no phone. */
    long countByPromoCodeIdAndUserEmailAndStatusNot(UUID promoCodeId, String userEmail,
                                                    Booking.BookingStatus status);

    /** Uses and the discount given, per code, for the organizer's list. */
    @Query("""
        SELECT b.promoCodeId AS promoCodeId, COUNT(b) AS uses, SUM(b.discountAmount) AS discountGiven
        FROM Booking b
        WHERE b.promoCodeId IN :promoCodeIds
          AND b.status <> :cancelledStatus
        GROUP BY b.promoCodeId
    """)
    List<PromoCodeUsage> summarizePromoCodeUses(@Param("promoCodeIds") java.util.Collection<UUID> promoCodeIds,
                                                @Param("cancelledStatus") Booking.BookingStatus cancelledStatus);

    interface PromoCodeUsage {
        UUID getPromoCodeId();
        Long getUses();
        java.math.BigDecimal getDiscountGiven();
    }
}
