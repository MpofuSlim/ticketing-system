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
 * The service merges the two by (organizer, event) and (optionally) one organizer.
 *
 * <p><b>Scoping changed with event-completion billing.</b> The {@code ...ForEvents}
 * pair below scopes by the EVENTS that ended in the billing period, with no date
 * filter on the booking at all — every confirmed booking for a completed event is
 * billable, whenever it sold. The older {@code ...InWindow} pair, which scoped on
 * {@code booking.createdAt}, is kept only for the ad-hoc
 * {@code POST /invoices/generate} path where an operator names an explicit
 * sales period.
 *
 * <p>Deliberately no date filter on the booking in the event-scoped queries. A
 * ticket bought in July for a November event belongs to November's invoice, and
 * adding a window would silently drop it — which is the whole bug this replaced.
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

    // Event-completion billing: every CONFIRMED booking for the given events,
    // regardless of when it sold. The caller passes the ids of events that ended
    // in the billing period (resolved from event-service).
    //
    // NEVER call these with an empty collection — `IN ()` is invalid SQL on
    // Postgres and Hibernate's rendering of an empty list is not something to
    // rely on. InvoiceService short-circuits before it gets here.
    @Query("""
        SELECT b.tenantUserUuid AS organizerUuid,
               b.eventId AS eventId,
               COUNT(b) AS confirmedBookings,
               COALESCE(SUM(b.totalAmount), 0) AS grossSales
        FROM Booking b
        WHERE b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.CONFIRMED
          AND b.tenantUserUuid IS NOT NULL
          AND b.eventId IN :eventIds
          AND (:organizerUuid IS NULL OR b.tenantUserUuid = :organizerUuid)
        GROUP BY b.tenantUserUuid, b.eventId
    """)
    List<OrganizerEventRevenueRow> aggregateConfirmedRevenueForEvents(@Param("organizerUuid") UUID organizerUuid,
                                                                      @Param("eventIds") List<UUID> eventIds);

    @Query("""
        SELECT b.tenantUserUuid AS organizerUuid,
               b.eventId AS eventId,
               COUNT(i) AS ticketsSold
        FROM Booking b JOIN b.items i
        WHERE b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.CONFIRMED
          AND b.tenantUserUuid IS NOT NULL
          AND b.eventId IN :eventIds
          AND (:organizerUuid IS NULL OR b.tenantUserUuid = :organizerUuid)
        GROUP BY b.tenantUserUuid, b.eventId
    """)
    List<OrganizerEventTicketRow> aggregateTicketCountsForEvents(@Param("organizerUuid") UUID organizerUuid,
                                                                 @Param("eventIds") List<UUID> eventIds);
}
