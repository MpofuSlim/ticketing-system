package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.Province;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {

    // Get all non-deleted events with optional date filter — paginated
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND (:from IS NULL OR e.dateTime >= :from)
        AND (:to IS NULL OR e.dateTime <= :to)
        AND (:venue IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', :venue, '%')))
    """)
    Page<Event> findAllActive(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            Pageable pageable
    );

    // Get only events flagged active=true (and non-deleted) with optional filters — paginated
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.active = true
        AND (:from IS NULL OR e.dateTime >= :from)
        AND (:to IS NULL OR e.dateTime <= :to)
        AND (:venue IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', :venue, '%')))
    """)
    Page<Event> findAllActiveOnly(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            Pageable pageable
    );

    // Only return event if it is not soft deleted
    Optional<Event> findByEventIdAndDeletedFalse(UUID eventId);

    // Paginated list of an organizer's own (non-deleted) events. Used by
    // GET /events/my so an EVENT_ORGANIZER only sees the events they created.
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.tenantId = :tenantId
        AND (:from IS NULL OR e.dateTime >= :from)
        AND (:to IS NULL OR e.dateTime <= :to)
        AND (:venue IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', :venue, '%')))
    """)
    Page<Event> findByTenantId(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            Pageable pageable
    );

    Page<Event> findByProvinceAndDeletedFalse(Province province, Pageable pageable);

    // Upcoming events in a province: non-deleted, tenant-active, and scheduled
    // at or after the supplied cutoff (usually "now"). Used by /events/by-province.
    Page<Event> findByProvinceAndDeletedFalseAndActiveTrueAndDateTimeGreaterThanEqual(
            Province province, LocalDateTime cutoff, Pageable pageable
    );

    // Detects an already-created event by the same tenant with identical title,
    // venue, and scheduled dateTime. Used as a duplicate-create guard.
    boolean existsByTenantIdAndTitleAndVenueAndDateTimeAndDeletedFalse(
            String tenantId, String title, String venue, LocalDateTime dateTime
    );

    // Free-text search across title, description and venue. Case-insensitive
    // substring match — typing "H" matches every event with H anywhere in
    // those fields; typing "Harare" narrows to events whose title/description/
    // venue mentions Harare. Excludes soft-deleted and inactive events so the
    // public search bar only surfaces bookable events.
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.active = true
        AND (
            LOWER(e.title)       LIKE LOWER(CONCAT('%', :q, '%'))
         OR LOWER(e.description) LIKE LOWER(CONCAT('%', :q, '%'))
         OR LOWER(e.venue)       LIKE LOWER(CONCAT('%', :q, '%'))
        )
    """)
    Page<Event> searchByKeyword(@Param("q") String q, Pageable pageable);

    // Atomically decrements availableTickets without ever going below zero.
    // Returns the number of rows updated (1 on success, 0 if the event is
    // missing/deleted or the requested count would underflow). Atomic at the
    // SQL level so concurrent confirms can't race past zero.
    @Modifying
    @Query("""
        UPDATE Event e
        SET e.availableTickets = e.availableTickets - :count
        WHERE e.eventId = :eventId
          AND e.deleted = false
          AND e.availableTickets >= :count
    """)
    int decrementAvailableTickets(@Param("eventId") UUID eventId, @Param("count") int count);
}
