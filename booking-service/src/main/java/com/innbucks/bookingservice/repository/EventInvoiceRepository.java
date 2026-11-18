package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.EventInvoice;
import com.innbucks.bookingservice.repository.projection.InvoiceStatusAggregate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventInvoiceRepository extends JpaRepository<EventInvoice, UUID> {

    boolean existsByOrganizerUuidAndPeriodStartAndPeriodEnd(UUID organizerUuid,
                                                            LocalDate periodStart,
                                                            LocalDate periodEnd);

    /**
     * The last day already on one of this organizer's invoices among those that
     * overlap {@code [periodStart, periodEnd]}, in ANY status; empty when none
     * overlaps.
     *
     * <p>Feeds the scheduled path's "never bill a day twice" rule: the exact
     * (organizer, period) key cannot see a monthly invoice and a weekly one
     * covering the same days. A CANCELLED invoice counts as covering its days,
     * as it does for the unique key, so the scheduler never re-issues what an
     * admin cancelled.
     */
    @Query("""
        SELECT MAX(i.periodEnd) FROM EventInvoice i
        WHERE i.organizerUuid = :organizerUuid
          AND i.periodStart <= :periodEnd
          AND i.periodEnd >= :periodStart
    """)
    Optional<LocalDate> findLatestOverlappingPeriodEnd(@Param("organizerUuid") UUID organizerUuid,
                                                       @Param("periodStart") LocalDate periodStart,
                                                       @Param("periodEnd") LocalDate periodEnd);

    /** Scoped fetch for the organizer-facing GET — an organizer can only read their own. */
    Optional<EventInvoice> findByIdAndOrganizerUuid(UUID id, UUID organizerUuid);

    /**
     * Single filterable list used by both audiences: admins pass {@code organizerUuid=null}
     * to span all organizers (or a value to narrow); organizers always pass their own
     * organizerUuid. {@code status=null} means "any status".
     */
    @Query("""
        SELECT i FROM EventInvoice i
        WHERE (:organizerUuid IS NULL OR i.organizerUuid = :organizerUuid)
          AND (:status IS NULL OR i.status = :status)
    """)
    Page<EventInvoice> search(@Param("organizerUuid") UUID organizerUuid,
                              @Param("status") EventInvoice.InvoiceStatus status,
                              Pageable pageable);

    /** ISSUED invoices whose due date has passed — fed to the overdue sweep. */
    @Query("""
        SELECT i FROM EventInvoice i
        WHERE i.status = com.innbucks.bookingservice.entity.EventInvoice.InvoiceStatus.ISSUED
          AND i.dueAt < :now
    """)
    List<EventInvoice> findIssuedPastDue(@Param("now") LocalDateTime now);

    /**
     * ISSUED invoices whose due date falls inside [now, until) and whose
     * due-soon reminder hasn't been attempted yet — fed to the due-soon sweep.
     */
    @Query("""
        SELECT i FROM EventInvoice i
        WHERE i.status = com.innbucks.bookingservice.entity.EventInvoice.InvoiceStatus.ISSUED
          AND i.dueSoonNotifiedAt IS NULL
          AND i.dueAt >= :now
          AND i.dueAt < :until
    """)
    List<EventInvoice> findIssuedDueSoonUnnotified(@Param("now") LocalDateTime now,
                                                   @Param("until") LocalDateTime until);

    /** Count + total-billed per status for the admin dashboard summary. */
    @Query("""
        SELECT i.status AS status, COUNT(i) AS count, COALESCE(SUM(i.totalAmount), 0) AS total
        FROM EventInvoice i
        GROUP BY i.status
    """)
    List<InvoiceStatusAggregate> summariseByStatus();

    /**
     * Next human-readable invoice-number sequence value. Non-transactional, so a
     * rolled-back generation leaves a gap — acceptable (we don't promise gapless).
     */
    @Query(value = "SELECT nextval('event_invoice_number_seq')", nativeQuery = true)
    long nextInvoiceNumberValue();
}
