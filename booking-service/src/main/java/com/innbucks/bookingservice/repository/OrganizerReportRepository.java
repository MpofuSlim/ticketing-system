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
     * set ONLY by reverseConfirmedBooking and a box office void (V35: the cash
     * handed back), both of which return money, so it cleanly distinguishes a
     * refund from a PENDING hold that merely expired (never paid). Items are not
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

    /**
     * Box office cash sales (V35) sold OR voided in the window, with their
     * tickets, for the cash-up: a sale counts on the day the cash was taken,
     * a void on the day it was handed back. A box office ONLINE sale (V36)
     * names its seller too, but no cash changed hands, so the rail decides.
     */
    @Query("""
        SELECT DISTINCT b FROM Booking b
        LEFT JOIN FETCH b.items
        WHERE b.soldByUserUuid IS NOT NULL
          AND b.paymentRail = 'CASH'
          AND (:organizerUuid IS NULL OR b.tenantUserUuid = :organizerUuid)
          AND (:eventId IS NULL OR b.eventId = :eventId)
          AND ((b.createdAt >= :start AND b.createdAt < :end)
               OR (b.voidedAt >= :start AND b.voidedAt < :end))
        ORDER BY b.createdAt ASC
    """)
    List<Booking> findCashSalesWithItems(@Param("organizerUuid") @Nullable UUID organizerUuid,
                                         @Param("eventId") UUID eventId,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    /**
     * Sales agents' sales (V36) made OR voided in the window, with their
     * tickets, whatever their status: the sales-agent reports work out sold,
     * voided, online and commission from them. Scoped by organizer (null =
     * every organizer, platform staff) and, for an agent's own report, by the
     * account that sold ({@code sellerUserUuid}; null = every agent).
     */
    @Query("""
        SELECT DISTINCT b FROM Booking b
        LEFT JOIN FETCH b.items
        WHERE b.soldByAgentId IS NOT NULL
          AND (:organizerUuid IS NULL OR b.tenantUserUuid = :organizerUuid)
          AND (:sellerUserUuid IS NULL OR b.soldByUserUuid = :sellerUserUuid)
          AND (:eventId IS NULL OR b.eventId = :eventId)
          AND ((b.createdAt >= :start AND b.createdAt < :end)
               OR (b.voidedAt >= :start AND b.voidedAt < :end))
        ORDER BY b.createdAt ASC
    """)
    List<Booking> findAgentSalesWithItems(@Param("organizerUuid") @Nullable UUID organizerUuid,
                                          @Param("sellerUserUuid") @Nullable UUID sellerUserUuid,
                                          @Param("eventId") @Nullable UUID eventId,
                                          @Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);

    /** The agent rows an account has sold under (V36): whose handovers are that account's. */
    @Query("""
        SELECT DISTINCT b.soldByAgentId FROM Booking b
        WHERE b.soldByAgentId IS NOT NULL AND b.soldByUserUuid = :sellerUserUuid
    """)
    List<UUID> findAgentIdsSoldBy(@Param("sellerUserUuid") UUID sellerUserUuid);

    /**
     * Whether this agent row has sold for the organizer (V36), and, with an
     * event, for that event: what a handover is recorded against. Read from
     * the bookings, so it asks no other service.
     */
    @Query("""
        SELECT COUNT(b) > 0 FROM Booking b
        WHERE b.soldByAgentId = :agentId
          AND b.tenantUserUuid = :organizerUuid
          AND (:eventId IS NULL OR b.eventId = :eventId)
    """)
    boolean agentHasSoldFor(@Param("organizerUuid") UUID organizerUuid,
                            @Param("agentId") UUID agentId,
                            @Param("eventId") @Nullable UUID eventId);

    /** One agent row's name, as its latest sale stored it. */
    interface AgentName {
        UUID getAgentId();
        String getName();
    }

    /**
     * The name each of these agent rows last sold under (sold_by_name, the
     * name the organizer gave the agent), for a report row with no sale in
     * its window (only a handover).
     */
    @Query(value = """
        SELECT DISTINCT ON (sold_by_agent_id) sold_by_agent_id AS agentId, sold_by_name AS name
        FROM bookings
        WHERE sold_by_agent_id IN (:agentIds)
        ORDER BY sold_by_agent_id, created_at DESC
    """, nativeQuery = true)
    List<AgentName> findAgentNames(@Param("agentIds") java.util.Collection<UUID> agentIds);
}
