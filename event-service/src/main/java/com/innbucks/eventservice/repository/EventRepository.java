package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
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

    // Note on the CAST(:param AS <type>) wrapping:
    // when these parameters arrive as Java null, the PostgreSQL JDBC driver
    // can't infer a Java-side type. For strings the binding falls through to
    // `bytea` and Postgres rejects `lower(bytea)` ("function lower(bytea)
    // does not exist"); for other types the binding is `unknown` and Postgres
    // fails to plan with "could not determine data type of parameter $n".
    // Either way the IS NULL guard would short-circuit at runtime, but
    // Postgres type-checks the entire boolean tree at plan time so it never
    // gets there. Adding the cast forces a typed bind; the cast is a no-op
    // for non-null values, and `cast(null as timestamp/varchar)` is still
    // NULL so the IS NULL branch behaves identically.

    // Get all non-deleted events with optional date filter — paginated
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND (CAST(:from AS timestamp) IS NULL OR e.startDateTime >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR e.startDateTime <= :to)
        AND (CAST(:venue AS string) IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', CAST(:venue AS string), '%')))
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
        AND (CAST(:from AS timestamp) IS NULL OR e.startDateTime >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR e.startDateTime <= :to)
        AND (CAST(:venue AS string) IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', CAST(:venue AS string), '%')))
        AND (CAST(:country AS string) IS NULL OR LOWER(e.country) = LOWER(CAST(:country AS string)))
    """)
    Page<Event> findAllActiveOnly(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            @Param("country") String country,
            Pageable pageable
    );

    // Same as findAllActiveOnly but additionally filters by category. Split into
    // its own method (rather than an optional :category IS NULL guard) so the
    // enum bind is never null — a null enum param can't be type-inferred by the
    // Postgres planner ("could not determine data type of parameter"), the same
    // failure mode the :venue/:from CASTs guard against. Callers invoke this
    // only when a category filter is actually supplied.
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.active = true
        AND e.category = :category
        AND (CAST(:from AS timestamp) IS NULL OR e.startDateTime >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR e.startDateTime <= :to)
        AND (CAST(:venue AS string) IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', CAST(:venue AS string), '%')))
        AND (CAST(:country AS string) IS NULL OR LOWER(e.country) = LOWER(CAST(:country AS string)))
    """)
    Page<Event> findAllActiveOnlyByCategory(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            @Param("country") String country,
            @Param("category") EventCategory category,
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
        AND (CAST(:from AS timestamp) IS NULL OR e.startDateTime >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR e.startDateTime <= :to)
        AND (CAST(:venue AS string) IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', CAST(:venue AS string), '%')))
    """)
    Page<Event> findByTenantId(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            Pageable pageable
    );

    // Same as findByTenantId but additionally filters active=true. Used so an
    // EVENT_ORGANIZER hitting /events/active only sees their own bookable events.
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.active = true
        AND e.tenantId = :tenantId
        AND (CAST(:from AS timestamp) IS NULL OR e.startDateTime >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR e.startDateTime <= :to)
        AND (CAST(:venue AS string) IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', CAST(:venue AS string), '%')))
        AND (CAST(:country AS string) IS NULL OR LOWER(e.country) = LOWER(CAST(:country AS string)))
    """)
    Page<Event> findByTenantIdActiveOnly(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            @Param("country") String country,
            Pageable pageable
    );

    // Category-filtered counterpart of findByTenantIdActiveOnly. Separate method
    // so the enum bind is always non-null (see findAllActiveOnlyByCategory).
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.active = true
        AND e.tenantId = :tenantId
        AND e.category = :category
        AND (CAST(:from AS timestamp) IS NULL OR e.startDateTime >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR e.startDateTime <= :to)
        AND (CAST(:venue AS string) IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', CAST(:venue AS string), '%')))
        AND (CAST(:country AS string) IS NULL OR LOWER(e.country) = LOWER(CAST(:country AS string)))
    """)
    Page<Event> findByTenantIdActiveOnlyByCategory(
            @Param("tenantId") String tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            @Param("country") String country,
            @Param("category") EventCategory category,
            Pageable pageable
    );

    // Upcoming events in a country: non-deleted, tenant-active, and starting
    // at or after the supplied cutoff (usually "now"). Country match is
    // case-insensitive since it's free text carried over from the JWT claim.
    // Used by /events/by-country.
    Page<Event> findByCountryIgnoreCaseAndDeletedFalseAndActiveTrueAndStartDateTimeGreaterThanEqual(
            String country, LocalDateTime cutoff, Pageable pageable
    );

    // Detects an already-created event by the same tenant with identical title,
    // venue, and scheduled dateTime. Used as a duplicate-create guard.
    boolean existsByTenantIdAndTitleAndVenueAndStartDateTimeAndDeletedFalse(
            String tenantId, String title, String venue, LocalDateTime startDateTime
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
            LOWER(e.title)       LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
         OR LOWER(e.description) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
         OR LOWER(e.venue)       LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
        )
    """)
    Page<Event> searchByKeyword(@Param("q") String q, Pageable pageable);

    // Bulk-expires events whose end time is before the given cutoff and are
    // still flagged active=true. Used by the nightly expiry scheduler — an
    // event stays active until it has actually ended (endDateTime), not when
    // it starts.
    @Modifying
    @Query("""
        UPDATE Event e
        SET e.active = false, e.updatedAt = :now
        WHERE e.deleted = false
        AND e.active = true
        AND e.endDateTime < :cutoff
    """)
    int deactivateExpiredEvents(@Param("cutoff") LocalDateTime cutoff,
                                @Param("now") LocalDateTime now);

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
