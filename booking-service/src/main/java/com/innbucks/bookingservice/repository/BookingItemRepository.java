package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
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

    interface EventActiveItemCount {
        UUID getEventId();
        Long getCount();
    }

    interface CategoryActiveItemCount {
        UUID getCategoryId();
        Long getCount();
    }
}
