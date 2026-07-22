package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.Booking;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.Nullable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-only aggregate access for the EVENT_ORGANIZER financial reports.
 *
 * <p>Separate from {@link BookingRepository} to keep the reporting surface
 * (organizer-scoped, period-bounded fetches) apart from the transactional
 * booking CRUD. Every query is scoped by {@code tenantUserUuid} — the
 * organizer's stable user_uuid, which is also the JWT {@code organizerUuid}
 * claim — so an organizer can only ever read their own events' figures.
 * A {@code null} organizerUuid means fleet-wide scope: reserved for
 * SUPER_ADMIN callers, whose tokens carry no organizer claim (the
 * controller resolves the scope; organizers can never pass null because
 * their claim is required there).
 *
 * <p>Money is aggregated in the service layer from these fetches rather than
 * with SQL SUMs over an item join: summing {@code Booking.totalAmount} across a
 * join to {@code items} would multiply each booking's total by its ticket
 * count (fan-out). Item-level money (per-category) is summed from
 * {@code BookingItem.priceAtBooking}, where one row is genuinely one ticket.
 */
public interface OrganizerReportRepository extends Repository<Booking, UUID> {

    /**
     * CONFIRMED bookings for the organizer in [start, end), with items fetched
     * so the service can count tickets and group by category without an N+1.
     * DISTINCT collapses the fetch-join cartesian product.
     */
    @Query("""
        SELECT DISTINCT b FROM Booking b
        LEFT JOIN FETCH b.items
        WHERE b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.CONFIRMED
          AND (:organizerUuid IS NULL OR b.tenantUserUuid = :organizerUuid)
          AND (:eventId IS NULL OR b.eventId = :eventId)
          AND b.createdAt >= :start AND b.createdAt < :end
        ORDER BY b.createdAt ASC
    """)
    List<Booking> findConfirmedWithItems(@Param("organizerUuid") @Nullable UUID organizerUuid,
                                         @Param("eventId") UUID eventId,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    /**
     * Reversed (refunded) bookings: CONFIRMED then admin-reversed, which flips
     * the status to CANCELLED and sets availabilityReleased=true. That flag is
     * set ONLY by reverseConfirmedBooking, so it cleanly distinguishes a refund
     * from a PENDING hold that merely expired (never paid). Items are not
     * needed here — only the refunded value — so no fetch join.
     */
    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.CANCELLED
          AND b.availabilityReleased = true
          AND (:organizerUuid IS NULL OR b.tenantUserUuid = :organizerUuid)
          AND (:eventId IS NULL OR b.eventId = :eventId)
          AND b.createdAt >= :start AND b.createdAt < :end
    """)
    List<Booking> findReversed(@Param("organizerUuid") @Nullable UUID organizerUuid,
                               @Param("eventId") UUID eventId,
                               @Param("start") LocalDateTime start,
                               @Param("end") LocalDateTime end);
}
