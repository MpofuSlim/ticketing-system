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

    // Counts CONFIRMED booking items grouped by event. Powers event-service's
    // availableTickets safety net: events return availableTickets = totalCapacity
    // − confirmedCount when the stored field falls behind (e.g. a confirm where
    // the event-service availability call failed). PENDING bookings don't count
    // because the user only sees the seat consumed once they've paid.
    @Query("""
        SELECT b.eventId AS eventId, COUNT(i) AS count
        FROM BookingItem i
        JOIN i.booking b
        WHERE b.eventId IN :eventIds
          AND b.status = :confirmedStatus
        GROUP BY b.eventId
    """)
    List<EventActiveItemCount> countActiveItemsByEventIds(
            @Param("eventIds") Collection<UUID> eventIds,
            @Param("confirmedStatus") Booking.BookingStatus confirmedStatus
    );

    interface EventActiveItemCount {
        UUID getEventId();
        Long getCount();
    }
}
