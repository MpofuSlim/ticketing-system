package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.repository.projection.OrganizerEventRevenueRow;
import com.innbucks.bookingservice.repository.projection.OrganizerEventTicketRow;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-only aggregate access over {@link Booking} for invoice generation.
 *
 * <p>Confirmed ticket revenue is rolled up per (organizer, event) in two
 * deliberately separate GROUP BYs:
 * <ul>
 *   <li>{@link #aggregateConfirmedRevenue} SUMs {@code totalAmount} <b>without</b>
 *       joining items — summing money across an item join would multiply each
 *       booking's total by its ticket count (fan-out).</li>
 *   <li>{@link #aggregateTicketCounts} COUNTs over the items join — COUNT is
 *       immune to the money fan-out, and ticket count is informational on the
 *       invoice line.</li>
 * </ul>
 * The service merges the two by (organizer, event). Both are scoped to CONFIRMED
 * bookings created in {@code [start, end)} and (optionally) one organizer.
 */
public interface InvoiceAggregationRepository extends Repository<Booking, UUID> {

    @Query("""
        SELECT b.tenantUserUuid AS organizerUuid,
               b.eventId AS eventId,
               COUNT(b) AS confirmedBookings,
               COALESCE(SUM(b.totalAmount), 0) AS grossSales
        FROM Booking b
        WHERE b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.CONFIRMED
          AND b.tenantUserUuid IS NOT NULL
          AND b.createdAt >= :start AND b.createdAt < :end
          AND (:organizerUuid IS NULL OR b.tenantUserUuid = :organizerUuid)
        GROUP BY b.tenantUserUuid, b.eventId
    """)
    List<OrganizerEventRevenueRow> aggregateConfirmedRevenue(@Param("organizerUuid") UUID organizerUuid,
                                                             @Param("start") LocalDateTime start,
                                                             @Param("end") LocalDateTime end);

    @Query("""
        SELECT b.tenantUserUuid AS organizerUuid,
               b.eventId AS eventId,
               COUNT(i) AS ticketsSold
        FROM Booking b JOIN b.items i
        WHERE b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.CONFIRMED
          AND b.tenantUserUuid IS NOT NULL
          AND b.createdAt >= :start AND b.createdAt < :end
          AND (:organizerUuid IS NULL OR b.tenantUserUuid = :organizerUuid)
        GROUP BY b.tenantUserUuid, b.eventId
    """)
    List<OrganizerEventTicketRow> aggregateTicketCounts(@Param("organizerUuid") UUID organizerUuid,
                                                        @Param("start") LocalDateTime start,
                                                        @Param("end") LocalDateTime end);
}
