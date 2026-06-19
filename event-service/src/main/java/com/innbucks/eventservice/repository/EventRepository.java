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

    // Get only events flagged active=true (and non-deleted, not yet ended, and
    // not admin-rejected) with optional filters — paginated. The
    // `e.endDateTime > :now` filter hides events whose end-time has already
    // passed even if the expiry scheduler hasn't run yet, so the read is always
    // correct regardless of scheduler timing. `e.rejected = false` keeps an
    // admin-rejected event out of the public listing (belt-and-suspenders on
    // top of active=false, which reject also sets).
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.active = true
        AND e.rejected = false
        AND e.endDateTime > :now
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
            @Param("now") LocalDateTime now,
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
        AND e.rejected = false
        AND e.endDateTime > :now
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
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    // Non-deleted events flagged active=false — the "inactive" listing
    // (GET /events/inactive). Unlike the active queries this does NOT apply the
    // endDateTime > :now filter (inactive intentionally includes events that
    // have already ended) and does NOT filter on rejected (rejected events are
    // active=false too, so they show up here for an admin to find and approve).
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.active = false
        AND (CAST(:from AS timestamp) IS NULL OR e.startDateTime >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR e.startDateTime <= :to)
        AND (CAST(:venue AS string) IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', CAST(:venue AS string), '%')))
        AND (CAST(:country AS string) IS NULL OR LOWER(e.country) = LOWER(CAST(:country AS string)))
    """)
    Page<Event> findAllInactiveOnly(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            @Param("country") String country,
            Pageable pageable
    );

    // Category-filtered counterpart of findAllInactiveOnly. Separate method so
    // the enum bind is always non-null (see findAllActiveOnlyByCategory).
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.active = false
        AND e.category = :category
        AND (CAST(:from AS timestamp) IS NULL OR e.startDateTime >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR e.startDateTime <= :to)
        AND (CAST(:venue AS string) IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', CAST(:venue AS string), '%')))
        AND (CAST(:country AS string) IS NULL OR LOWER(e.country) = LOWER(CAST(:country AS string)))
    """)
    Page<Event> findAllInactiveOnlyByCategory(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            @Param("country") String country,
            @Param("category") EventCategory category,
            Pageable pageable
    );

    // Only return event if it is not soft deleted
    Optional<Event> findByEventIdAndDeletedFalse(UUID eventId);

    // Paginated list of an organizer's own (non-deleted) events. Keyed on the
    // stable cross-service organizerUuid (was email-as-tenantId until the V6/V7
    // refactor; the tenant_id column was dropped in V8). Used by GET /events/my
    // so an EVENT_ORGANIZER only sees the events they created, and by
    // TEAM_MEMBERs to enumerate their parent organizer's events.
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.tenantUserUuid = :tenantUserUuid
        AND (CAST(:from AS timestamp) IS NULL OR e.startDateTime >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR e.startDateTime <= :to)
        AND (CAST(:venue AS string) IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', CAST(:venue AS string), '%')))
    """)
    Page<Event> findByTenantUserUuid(
            @Param("tenantUserUuid") UUID tenantUserUuid,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            Pageable pageable
    );

    // Like findByTenantUserUuid but additionally restricts the result to a
    // specific allow-list of event IDs. Backs the team-member view of
    // GET /events/my: the team member sees only the events their organizer
    // has explicitly assigned to them (deny-by-default). The organizer
    // filter is kept as defence-in-depth — even if the assignment table
    // somehow held a stale eventId from a different organizer, the
    // tenantUserUuid clause blocks it from leaking through. Callers that
    // have an empty eventIds list MUST short-circuit before calling this
    // (Spring JPA forbids an empty `IN (...)` in some dialects).
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.tenantUserUuid = :tenantUserUuid
        AND e.eventId IN :eventIds
        AND (CAST(:from AS timestamp) IS NULL OR e.startDateTime >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR e.startDateTime <= :to)
        AND (CAST(:venue AS string) IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', CAST(:venue AS string), '%')))
    """)
    Page<Event> findByTenantUserUuidAndEventIdIn(
            @Param("tenantUserUuid") UUID tenantUserUuid,
            @Param("eventIds") java.util.Collection<UUID> eventIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            Pageable pageable
    );

    // Same as findByTenantUserUuid but additionally filters active=true, hides
    // events whose endDateTime has passed (independent of the expiry scheduler
    // so the read is always correct), and excludes admin-rejected events. Used
    // so an EVENT_ORGANIZER hitting /events/active only sees their own bookable
    // events.
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.active = true
        AND e.rejected = false
        AND e.endDateTime > :now
        AND e.tenantUserUuid = :tenantUserUuid
        AND (CAST(:from AS timestamp) IS NULL OR e.startDateTime >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR e.startDateTime <= :to)
        AND (CAST(:venue AS string) IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', CAST(:venue AS string), '%')))
        AND (CAST(:country AS string) IS NULL OR LOWER(e.country) = LOWER(CAST(:country AS string)))
    """)
    Page<Event> findByTenantUserUuidActiveOnly(
            @Param("tenantUserUuid") UUID tenantUserUuid,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            @Param("country") String country,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    // Category-filtered counterpart of findByTenantUserUuidActiveOnly. Separate
    // method so the enum bind is always non-null (see findAllActiveOnlyByCategory).
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.active = true
        AND e.rejected = false
        AND e.endDateTime > :now
        AND e.tenantUserUuid = :tenantUserUuid
        AND e.category = :category
        AND (CAST(:from AS timestamp) IS NULL OR e.startDateTime >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR e.startDateTime <= :to)
        AND (CAST(:venue AS string) IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', CAST(:venue AS string), '%')))
        AND (CAST(:country AS string) IS NULL OR LOWER(e.country) = LOWER(CAST(:country AS string)))
    """)
    Page<Event> findByTenantUserUuidActiveOnlyByCategory(
            @Param("tenantUserUuid") UUID tenantUserUuid,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            @Param("country") String country,
            @Param("category") EventCategory category,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    // An organizer's own inactive (active=false) events. Counterpart of
    // findAllInactiveOnly scoped to a single organizer, used so an
    // EVENT_ORGANIZER hitting /events/inactive only sees their own. No
    // endDateTime/rejected filter — same rationale as findAllInactiveOnly.
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.active = false
        AND e.tenantUserUuid = :tenantUserUuid
        AND (CAST(:from AS timestamp) IS NULL OR e.startDateTime >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR e.startDateTime <= :to)
        AND (CAST(:venue AS string) IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', CAST(:venue AS string), '%')))
        AND (CAST(:country AS string) IS NULL OR LOWER(e.country) = LOWER(CAST(:country AS string)))
    """)
    Page<Event> findByTenantUserUuidInactiveOnly(
            @Param("tenantUserUuid") UUID tenantUserUuid,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            @Param("country") String country,
            Pageable pageable
    );

    // Category-filtered counterpart of findByTenantUserUuidInactiveOnly.
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.active = false
        AND e.tenantUserUuid = :tenantUserUuid
        AND e.category = :category
        AND (CAST(:from AS timestamp) IS NULL OR e.startDateTime >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR e.startDateTime <= :to)
        AND (CAST(:venue AS string) IS NULL OR LOWER(e.venue) LIKE LOWER(CONCAT('%', CAST(:venue AS string), '%')))
        AND (CAST(:country AS string) IS NULL OR LOWER(e.country) = LOWER(CAST(:country AS string)))
    """)
    Page<Event> findByTenantUserUuidInactiveOnlyByCategory(
            @Param("tenantUserUuid") UUID tenantUserUuid,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("venue") String venue,
            @Param("country") String country,
            @Param("category") EventCategory category,
            Pageable pageable
    );

    // Upcoming events in a country: non-deleted, tenant-active, not admin-rejected,
    // and starting at or after the supplied cutoff (usually "now"). Country match
    // is case-insensitive since it's free text carried over from the JWT claim.
    // Used by /events/by-country.
    Page<Event> findByCountryIgnoreCaseAndDeletedFalseAndActiveTrueAndRejectedFalseAndStartDateTimeGreaterThanEqual(
            String country, LocalDateTime cutoff, Pageable pageable
    );

    // Detects an already-created event by the same organizer with identical title,
    // venue, and scheduled dateTime. Used as a duplicate-create guard. The legacy
    // (tenant_id, title, venue, start_date_time) DB-level unique constraint was
    // dropped with the tenant_id column in V8, so this app-layer exists-check is
    // the dup guard for new rows.
    boolean existsByTenantUserUuidAndTitleAndVenueAndStartDateTimeAndDeletedFalse(
            UUID tenantUserUuid, String title, String venue, LocalDateTime startDateTime
    );

    // Free-text search across title, description and venue. Case-insensitive
    // substring match — typing "H" matches every event with H anywhere in
    // those fields; typing "Harare" narrows to events whose title/description/
    // venue mentions Harare. Excludes soft-deleted, inactive, ended and
    // admin-rejected events so the public search bar only surfaces bookable
    // events.
    @Query("""
        SELECT e FROM Event e
        WHERE e.deleted = false
        AND e.active = true
        AND e.rejected = false
        AND e.endDateTime > :now
        AND (
            LOWER(e.title)       LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
         OR LOWER(e.description) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
         OR LOWER(e.venue)       LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
        )
    """)
    Page<Event> searchByKeyword(@Param("q") String q,
                                @Param("now") LocalDateTime now,
                                Pageable pageable);

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

    // Atomically restores availableTickets when a confirmed booking is
    // reversed (admin refund, no-show, real-payment failure compensation).
    // Clamped to totalCapacity so a buggy caller / replay can never push
    // available above the event's seat count. Returns the number of rows
    // updated (1 on success, 0 if the event is missing/deleted or the
    // requested count would overflow totalCapacity).
    @Modifying
    @Query("""
        UPDATE Event e
        SET e.availableTickets = e.availableTickets + :count
        WHERE e.eventId = :eventId
          AND e.deleted = false
          AND e.availableTickets + :count <= e.totalCapacity
    """)
    int releaseAvailableTickets(@Param("eventId") UUID eventId, @Param("count") int count);
}
