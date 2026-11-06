package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingItemRepository extends JpaRepository<BookingItem, UUID> {

    // Returns booking items for any of the given seats whose parent booking
    // is still active (not CANCELLED). Used as a duplicate-create guard.
    @Query("""
        SELECT i FROM BookingItem i
        WHERE i.seatId IN :seatIds
          AND i.booking.status <> :cancelledStatus
    """)
    List<BookingItem> findActiveBySeatIds(
            @Param("seatIds") Collection<UUID> seatIds,
            @Param("cancelledStatus") Booking.BookingStatus cancelledStatus
    );

    // Returns every booking item whose seat belongs to the given category.
    // Joins the parent booking eagerly so callers can read userEmail/status/
    // createdAt without triggering N+1 lazy-loads. Used by seat-service's
    // category analytics endpoint.
    @Query("""
        SELECT i FROM BookingItem i
        JOIN FETCH i.booking
        WHERE i.categoryId = :categoryId
    """)
    List<BookingItem> findByCategoryIdWithBooking(@Param("categoryId") UUID categoryId);

    // Returns every booking item attached to a booking for the given event.
    // Used by seat-service's event-level analytics so one HTTP call covers
    // all categories at once instead of fanning out per-category.
    @Query("""
        SELECT i FROM BookingItem i
        JOIN FETCH i.booking b
        WHERE b.eventId = :eventId
    """)
    List<BookingItem> findByEventIdWithBooking(@Param("eventId") UUID eventId);

    /**
     * A booking's tickets, locked (V35 cash void): taken after the booking
     * row and before the scan check, so a scan's claim of one of them waits
     * for the void and then finds it inactive.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM BookingItem i WHERE i.booking.id = :bookingId ORDER BY i.id")
    List<BookingItem> lockByBookingId(@Param("bookingId") UUID bookingId);

    /** An assigned number's tickets within one event (V34), any letter case. */
    @Query("""
        SELECT i FROM BookingItem i
        JOIN FETCH i.booking b
        WHERE b.eventId = :eventId AND UPPER(i.assignedNumber) = UPPER(:assignedNumber)
        ORDER BY i.categoryName, i.id
    """)
    List<BookingItem> findByEventIdAndAssignedNumberWithBooking(@Param("eventId") UUID eventId,
                                                                 @Param("assignedNumber") String assignedNumber);

    // Counts active (non-CANCELLED, i.e. PENDING + CONFIRMED) booking items
    // grouped by event. Powers event-service's availableTickets safety net:
    // events return availableTickets = totalCapacity − activeCount, so the
    // public listing reflects holds the moment a customer starts checkout —
    // not only after they pay. This matches seat-service's view of reality
    // (the seats are LOCKED in seat-service the instant a booking goes
    // PENDING, so the per-category counter already drops by N). Counting
    // only CONFIRMED here lets the event-level count drift above the
    // seat-level count and confuses the public:  the categories show "10
    // left" while the event card still says "12 left". CANCELLED items are
    // excluded so released holds (expired or refunded) free up capacity
    // immediately.
    @Query("""
        SELECT b.eventId AS eventId, COUNT(i) AS count
        FROM BookingItem i
        JOIN i.booking b
        WHERE b.eventId IN :eventIds
          AND b.status <> :cancelledStatus
        GROUP BY b.eventId
    """)
    List<EventActiveItemCount> countActiveItemsByEventIds(
            @Param("eventIds") Collection<UUID> eventIds,
            @Param("cancelledStatus") Booking.BookingStatus cancelledStatus
    );

    // Counts active (non-CANCELLED) booking items for one category. Used to
    // seed the per-category inventory counter (remaining = total_seats −
    // activeCount) the first time a category is booked after the GA counter
    // landed — so an already-partially-booked category seeds to the correct
    // remainder, not full capacity.
    @Query("""
        SELECT COUNT(i) FROM BookingItem i
        WHERE i.categoryId = :categoryId
          AND i.booking.status <> :cancelledStatus
    """)
    long countActiveByCategoryId(
            @Param("categoryId") UUID categoryId,
            @Param("cancelledStatus") Booking.BookingStatus cancelledStatus
    );

    /**
     * Tickets a sales agent holds or has sold for one event (V36): items of
     * the agent's bookings for it that are not CANCELLED, so a voided or
     * expired sale frees its tickets. The agent's ticket cap is checked
     * against this, read under the per-(agent, event) advisory lock
     * ({@link BookingRepository#lockAgentSales}) so two sales of one agent
     * cannot both pass it. Served by idx_bookings_sold_by_agent_event.
     */
    @Query("""
        SELECT COUNT(i) FROM BookingItem i
        WHERE i.booking.soldByAgentId = :agentId
          AND i.booking.eventId = :eventId
          AND i.booking.status <> :cancelledStatus
    """)
    long countActiveByAgentAndEvent(
            @Param("agentId") UUID agentId,
            @Param("eventId") UUID eventId,
            @Param("cancelledStatus") Booking.BookingStatus cancelledStatus
    );

    // Batch sibling of countActiveByCategoryId, grouped by category. Powers
    // seat-service's live per-category availableSeats: a category returns
    // availableSeats = totalSeats − activeCount, the same totalCapacity −
    // activeCount formula event-service already uses at the event level (see
    // countActiveItemsByEventIds). Counting PENDING + CONFIRMED — not just
    // CONFIRMED — keeps the per-category "left" number and the event card's
    // availableTickets in lock-step the instant a customer starts checkout.
    // Categories with no active items are simply absent from the result; the
    // caller treats a missing categoryId as "full capacity remaining".
    @Query("""
        SELECT i.categoryId AS categoryId, COUNT(i) AS count
        FROM BookingItem i
        JOIN i.booking b
        WHERE i.categoryId IN :categoryIds
          AND b.status <> :cancelledStatus
        GROUP BY i.categoryId
    """)
    List<CategoryActiveItemCount> countActiveItemsByCategoryIds(
            @Param("categoryIds") Collection<UUID> categoryIds,
            @Param("cancelledStatus") Booking.BookingStatus cancelledStatus
    );

    /**
     * Resolves a ticket by its number, eagerly loading the parent booking
     * so the scan handler can authorise (tenant_user_uuid + status) and
     * write back redemption fields in one round trip. Returns Optional —
     * the scan path translates absent to "TICKET_NOT_FOUND".
     */
    @Query("""
        SELECT i FROM BookingItem i
        JOIN FETCH i.booking
        WHERE i.ticketNumber = :ticketNumber
    """)
    Optional<BookingItem> findByTicketNumberWithBooking(@Param("ticketNumber") String ticketNumber);

    /**
     * One ticket by its row id, with its booking in the same statement: the
     * holder's own ticket page and QR read the booking's status and nothing
     * else of it.
     */
    @Query("""
        SELECT i FROM BookingItem i
        JOIN FETCH i.booking
        WHERE i.id = :id
    """)
    Optional<BookingItem> findByIdWithBooking(@Param("id") UUID id);

    /**
     * One ticket by its link token (V31), with its booking: the holder's page
     * and every QR image we link to. Same shape as {@link #findByIdWithBooking}.
     */
    @Query("""
        SELECT i FROM BookingItem i
        JOIN FETCH i.booking
        WHERE i.linkToken = :token
    """)
    Optional<BookingItem> findByLinkTokenWithBooking(@Param("token") String token);

    /**
     * Atomically claims the ticket for the calling scanner. The WHERE
     * clause makes redemption single-shot: once {@link BookingItem#getRedeemedAt()}
     * is non-null, this UPDATE matches zero rows on every subsequent call
     * and the scan handler returns ALREADY_REDEEMED with the original
     * audit fields (loaded via {@link #findByTicketNumberWithBooking}).
     * Returns the number of rows updated (0 = already redeemed, 1 = success).
     */
    @Modifying
    @Query("""
        UPDATE BookingItem i
        SET i.redeemedAt = :redeemedAt,
            i.redeemedByUserUuid = :redeemedByUserUuid,
            i.redeemedByName = :redeemedByName
        WHERE i.id = :id
        AND i.redeemedAt IS NULL
        AND i.isActive = true
    """)
    int claimRedemption(
            @Param("id") UUID id,
            @Param("redeemedAt") LocalDateTime redeemedAt,
            @Param("redeemedByUserUuid") UUID redeemedByUserUuid,
            @Param("redeemedByName") String redeemedByName
    );

    interface EventActiveItemCount {
        UUID getEventId();
        Long getCount();
    }

    interface CategoryActiveItemCount {
        UUID getCategoryId();
        Long getCount();
    }

    /**
     * Active (non-CANCELLED) tickets sold at each of {@code tierIds} in one
     * category. BookingService.persistBooking calls it AFTER claiming the
     * category's category_inventory row in the same transaction: every
     * booking, cancellation and expiry of the category takes that lock first,
     * so the count cannot change before this booking's items are inserted.
     * That is what makes a tier's quantity ("the first 100") exact. A tier
     * with nothing sold is absent from the result.
     */
    @Query("""
        SELECT i.priceTierId AS priceTierId, COUNT(i) AS count
        FROM BookingItem i
        JOIN i.booking b
        WHERE i.categoryId = :categoryId
          AND i.priceTierId IN :tierIds
          AND b.status <> :cancelledStatus
        GROUP BY i.priceTierId
    """)
    List<TierActiveItemCount> countActiveByCategoryAndPriceTiers(
            @Param("categoryId") UUID categoryId,
            @Param("tierIds") Collection<UUID> tierIds,
            @Param("cancelledStatus") Booking.BookingStatus cancelledStatus
    );

    /**
     * Active tickets per price tier for several categories, for seat-service's
     * display of each tier's sold / remaining (GET
     * /bookings/internal/categories/tier-counts). Full-price tickets are left
     * out; a tier with nothing sold is absent.
     */
    @Query("""
        SELECT i.categoryId AS categoryId, i.priceTierId AS priceTierId, COUNT(i) AS count
        FROM BookingItem i
        JOIN i.booking b
        WHERE i.categoryId IN :categoryIds
          AND i.priceTierId IS NOT NULL
          AND b.status <> :cancelledStatus
        GROUP BY i.categoryId, i.priceTierId
    """)
    List<CategoryTierActiveItemCount> countActiveItemsByCategoryAndPriceTier(
            @Param("categoryIds") Collection<UUID> categoryIds,
            @Param("cancelledStatus") Booking.BookingStatus cancelledStatus
    );

    /**
     * Tickets of {@code tierIds} in one category whose booking was reversed
     * after payment (CANCELLED, {@code ADMIN_REVERSED}). They count against a
     * tier's quantity when the tier does not reopen on refund. Read under the
     * category's stock lock, like the active count: the reversal takes it too.
     */
    @Query("""
        SELECT i.priceTierId AS priceTierId, COUNT(i) AS count
        FROM BookingItem i
        JOIN i.booking b
        WHERE i.categoryId = :categoryId
          AND i.priceTierId IN :tierIds
          AND b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.CANCELLED
          AND b.cancelReason = com.innbucks.bookingservice.entity.Booking.CancelReason.ADMIN_REVERSED
        GROUP BY i.priceTierId
    """)
    List<TierActiveItemCount> countRefundedByCategoryAndPriceTiers(
            @Param("categoryId") UUID categoryId,
            @Param("tierIds") Collection<UUID> tierIds
    );

    /**
     * Active tickets one buyer holds at each of {@code tierIds} in one
     * category, by the purchaser's phone, for a tier's per-customer cap. Read
     * under the category's stock lock, so two bookings by the same buyer
     * cannot both take the last place under the cap.
     */
    @Query("""
        SELECT i.priceTierId AS priceTierId, COUNT(i) AS count
        FROM BookingItem i
        JOIN i.booking b
        WHERE i.categoryId = :categoryId
          AND i.priceTierId IN :tierIds
          AND b.status <> :cancelledStatus
          AND b.phoneNumber = :phoneNumber
        GROUP BY i.priceTierId
    """)
    List<TierActiveItemCount> countActiveForPhoneByCategoryAndPriceTiers(
            @Param("categoryId") UUID categoryId,
            @Param("tierIds") Collection<UUID> tierIds,
            @Param("phoneNumber") String phoneNumber,
            @Param("cancelledStatus") Booking.BookingStatus cancelledStatus
    );

    /** The same per-customer count by the purchaser's email, for a booking that carries no phone. */
    @Query("""
        SELECT i.priceTierId AS priceTierId, COUNT(i) AS count
        FROM BookingItem i
        JOIN i.booking b
        WHERE i.categoryId = :categoryId
          AND i.priceTierId IN :tierIds
          AND b.status <> :cancelledStatus
          AND b.userEmail = :userEmail
        GROUP BY i.priceTierId
    """)
    List<TierActiveItemCount> countActiveForEmailByCategoryAndPriceTiers(
            @Param("categoryId") UUID categoryId,
            @Param("tierIds") Collection<UUID> tierIds,
            @Param("userEmail") String userEmail,
            @Param("cancelledStatus") Booking.BookingStatus cancelledStatus
    );

    /** Refunded tickets per price tier for several categories, for seat-service's display. */
    @Query("""
        SELECT i.categoryId AS categoryId, i.priceTierId AS priceTierId, COUNT(i) AS count
        FROM BookingItem i
        JOIN i.booking b
        WHERE i.categoryId IN :categoryIds
          AND i.priceTierId IS NOT NULL
          AND b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.CANCELLED
          AND b.cancelReason = com.innbucks.bookingservice.entity.Booking.CancelReason.ADMIN_REVERSED
        GROUP BY i.categoryId, i.priceTierId
    """)
    List<CategoryTierActiveItemCount> countRefundedItemsByCategoryAndPriceTier(
            @Param("categoryIds") Collection<UUID> categoryIds
    );

    interface TierActiveItemCount {
        UUID getPriceTierId();
        Long getCount();
    }

    interface CategoryTierActiveItemCount {
        UUID getCategoryId();
        UUID getPriceTierId();
        Long getCount();
    }

    // ---- Booking answers (V32) ---------------------------------------------------

    /**
     * Every ticket of the event's CONFIRMED bookings with its booking, newest
     * booking first: the booking-questions report reads their answers and terms.
     * Platform-wide: staff. The organizer's own read is the next query.
     */
    @Query("SELECT i FROM BookingItem i JOIN FETCH i.booking b WHERE b.eventId = :eventId "
            + "AND b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.CONFIRMED "
            + "ORDER BY b.createdAt DESC, i.id")
    List<BookingItem> findConfirmedWithBookingByEventId(@Param("eventId") UUID eventId);

    @Query("SELECT i FROM BookingItem i JOIN FETCH i.booking b WHERE b.eventId = :eventId "
            + "AND b.tenantUserUuid = :organizerUuid "
            + "AND b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.CONFIRMED "
            + "ORDER BY b.createdAt DESC, i.id")
    List<BookingItem> findConfirmedWithBookingByEventIdAndOrganizer(@Param("eventId") UUID eventId,
                                                                    @Param("organizerUuid") UUID organizerUuid);

    /** Deletes the answers of the event's bookings still due for it (BookingAnswersRetentionJob). */
    @Modifying
    @Query(value = "UPDATE booking_items SET answers = NULL WHERE answers IS NOT NULL AND booking_id IN "
            + "(SELECT id FROM bookings WHERE event_id = :eventId AND answers_purge_after IS NOT NULL)",
            nativeQuery = true)
    int clearAnswersForEvent(@Param("eventId") UUID eventId);

    // ---- Add-ons (V33) ----------------------------------------------------------

    /**
     * Add-ons on tickets of bookings that are not CANCELLED, per category and
     * add-on: what counts against each add-on's limit. Read under the
     * categories' stock row locks at booking (exact), and for seat-service's
     * display (best effort).
     */
    @Query(value = "SELECT i.category_id AS categoryId, (a.elem ->> 'addOnId') AS addOnId, COUNT(*) AS count "
            + "FROM booking_items i JOIN bookings b ON b.id = i.booking_id "
            + "CROSS JOIN LATERAL jsonb_array_elements(i.add_ons::jsonb) AS a(elem) "
            + "WHERE i.add_ons IS NOT NULL AND i.category_id IN (:categoryIds) AND b.status <> 'CANCELLED' "
            + "GROUP BY i.category_id, (a.elem ->> 'addOnId')", nativeQuery = true)
    List<CategoryAddOnCount> countActiveAddOnsByCategories(@Param("categoryIds") Collection<UUID> categoryIds);

    interface CategoryAddOnCount {
        UUID getCategoryId();
        String getAddOnId();
        Long getCount();
    }

}
