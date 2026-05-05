package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.Province;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
