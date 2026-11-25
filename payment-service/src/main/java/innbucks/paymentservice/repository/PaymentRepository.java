package innbucks.paymentservice.repository;

import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.PaymentRail;
import innbucks.paymentservice.order.OrderType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Payment}. Lookup by stable
 * {@code paymentReference} (the veengu-side idempotency key) is the primary
 * read for the reconciler and support-ticket workflows; lookup by
 * {@code idempotencyKey} backs the cache-eviction safety net.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByPaymentReference(String paymentReference);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /**
     * The row, locked ({@code SELECT … FOR UPDATE}) until the caller's
     * transaction ends. Every status change reads through this
     * ({@code PaymentRecordService.transitionFrom}), so two writers that both
     * saw a row open cannot both change it: the second waits, then reads the
     * first one's result.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Mark a Paynow payment as handed to an operator, once. Returns 1 for the
     * caller that set it and 0 for everyone after, so one case is raised once
     * however many updates or re-checks report it.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Payment p set p.paynowFlaggedAt = :now where p.id = :id and p.paynowFlaggedAt is null")
    int flagPaynowOperatorCase(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Claim the one stuck-payment alert of a payment that is still
     * COMPLETED_UNCONFIRMED. Returns 1 for the caller that set the stamp and
     * 0 otherwise (already alerted, or the row moved on). One statement on
     * the live row, never a save of a copy read earlier: a save would write
     * that copy's every stale field back (Payment has no {@code @Version}),
     * undoing a park or a confirm that landed meanwhile.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Payment p set p.operatorAlertedAt = :now where p.id = :id and p.operatorAlertedAt is null "
            + "and p.status = :status")
    int claimStuckPaymentAlert(@Param("id") UUID id, @Param("now") Instant now,
                               @Param("status") Payment.PaymentStatus status);

    /**
     * EcoCash notify-webhook lookup: "which row is this notification about?"
     * The correlator is unique where present (uq_payment_ecocash_correlator),
     * and it is OUR value — an attacker guessing one gains nothing, since the
     * webhook only triggers a fresh upstream Query, never trusts the body.
     */
    Optional<Payment> findByEcocashClientCorrelator(String ecocashClientCorrelator);

    /**
     * Pre-check companion to the {@code uq_payment_active_order} partial
     * unique index: lets the service refuse a second payment for an order
     * with a clean 409 instead of a constraint violation. The INDEX remains
     * the source of truth under concurrency — this is UX, not enforcement.
     */
    boolean existsByOrderTypeAndOrderRefAndStatusIn(
            OrderType orderType, String orderRef, Collection<Payment.PaymentStatus> statuses);

    /**
     * The EcoCash per-number prompt cap: charges on {@code rail} to any of
     * {@code msisdns} (the spellings of one number) created after
     * {@code since}. Uses idx_payment_msisdn_created (V17).
     */
    long countByPaymentRailAndCustomerMsisdnInAndCreatedAtAfter(
            PaymentRail rail, Collection<String> msisdns, Instant since);

    /** The oldest of those, so the refusal can say when a place frees up. */
    Optional<Payment> findFirstByPaymentRailAndCustomerMsisdnInAndCreatedAtAfterOrderByCreatedAtAsc(
            PaymentRail rail, Collection<String> msisdns, Instant since);

    /**
     * Reconciler sweep: rows stuck in a non-terminal state (PENDING /
     * IN_DOUBT) past the staleness threshold.
     */
    List<Payment> findByStatusInAndCreatedAtBefore(
            Collection<Payment.PaymentStatus> statuses, Instant cutoff, Pageable pageable);

    /** Reconciler sweep: money-moved-but-booking-unconfirmed rows to retry. */
    List<Payment> findByStatus(Payment.PaymentStatus status, Pageable pageable);

    /**
     * Rail-scoped poller sweeps: the code poller resolves only
     * INNBUCKS_CODE rows (via the code-inquiry endpoint) and the card
     * poller only ZIMSWITCH_CARD rows (via the COPYandPAY status endpoint)
     * — each rail's open rows are opaque to the other's upstream.
     */
    List<Payment> findByStatusAndPaymentRail(
            Payment.PaymentStatus status, PaymentRail paymentRail, Pageable pageable);

    /** Open payments on any of the Paynow rails (tests and support reads; the sweep uses {@link #findPaynowDue}). */
    List<Payment> findByStatusAndPaymentRailIn(
            Payment.PaymentStatus status, Collection<PaymentRail> paymentRails, Pageable pageable);

    /**
     * The Paynow sweep's rows: open Paynow payments that need a check now,
     * least recently checked first. Due means one of:
     * <ul>
     *   <li>its initiate reply was lost (no poll URL) for more than
     *       {@code noReplyBefore}'s 30 s: looked up by its trace;</li>
     *   <li>past its deadline plus grace ({@code deadlineBefore}): closed or
     *       confirmed;</li>
     *   <li>not checked since {@code checkedBefore} (Paynow's update may have
     *       been lost).</li>
     * </ul>
     * Filtering here, not in Java, keeps a page of payments that are not yet
     * due from crowding out the ones that are (the card poller's rule,
     * {@link #findOpenPastCeiling}). A check stamps {@code paynow_checked_at}
     * before Paynow is asked, so a payment whose check keeps failing moves to
     * the back of the order rather than holding the head.
     */
    @Query("""
            select p from Payment p
             where p.status = :status
               and p.paymentRail in :rails
               and ((p.paynowPollUrl is null and p.createdAt < :noReplyBefore)
                 or (p.codeExpiresAt is not null and p.codeExpiresAt < :deadlineBefore)
                 or (p.codeExpiresAt is null and p.createdAt < :createdBefore)
                 or coalesce(p.paynowCheckedAt, p.createdAt) < :checkedBefore)
             order by coalesce(p.paynowCheckedAt, p.createdAt) asc, p.createdAt asc
            """)
    List<Payment> findPaynowDue(
            @Param("status") Payment.PaymentStatus status,
            @Param("rails") Collection<PaymentRail> rails,
            @Param("noReplyBefore") Instant noReplyBefore,
            @Param("deadlineBefore") Instant deadlineBefore,
            @Param("createdBefore") Instant createdBefore,
            @Param("checkedBefore") Instant checkedBefore,
            Pageable pageable);

    /**
     * Paynow payments we closed unpaid ({@code EXPIRED}) in the last day that
     * are due a look, in case the customer paid after all and Paynow's update
     * about it was lost: within the first hour of closing, every
     * {@code firstHourGap}; then every {@code laterGap}. A payment handed to an
     * operator ({@code paynow_flagged_at}) is never read again. Least recently
     * checked first. Backed by {@code idx_payment_paynow_closed} (V19).
     */
    @Query("""
            select p from Payment p
             where p.status = :status
               and p.paymentRail in :rails
               and p.paynowFlaggedAt is null
               and p.completedAt > :closedAfter
               and ((p.completedAt > :firstHourAfter
                     and coalesce(p.paynowCheckedAt, p.completedAt) < :firstHourCheckedBefore)
                 or (p.completedAt <= :firstHourAfter
                     and coalesce(p.paynowCheckedAt, p.completedAt) < :laterCheckedBefore))
             order by coalesce(p.paynowCheckedAt, p.completedAt) asc
            """)
    List<Payment> findPaynowClosedDue(
            @Param("status") Payment.PaymentStatus status,
            @Param("rails") Collection<PaymentRail> rails,
            @Param("closedAfter") Instant closedAfter,
            @Param("firstHourAfter") Instant firstHourAfter,
            @Param("firstHourCheckedBefore") Instant firstHourCheckedBefore,
            @Param("laterCheckedBefore") Instant laterCheckedBefore,
            Pageable pageable);

    /** The per-number prompt cap for the Paynow phone rails (OneMoney, O'mari). */
    long countByPaymentRailInAndCustomerMsisdnInAndCreatedAtAfter(
            Collection<PaymentRail> paymentRails, Collection<String> customerMsisdns, Instant createdAfter);

    Optional<Payment> findFirstByPaymentRailInAndCustomerMsisdnInAndCreatedAtAfterOrderByCreatedAtAsc(
            Collection<PaymentRail> paymentRails, Collection<String> customerMsisdns, Instant createdAfter);

    /**
     * The card sweep's rows: open checkouts past their ceiling, oldest first.
     * The ceiling is the local deadline plus the grace, so the caller passes
     * {@code now − grace} as {@code deadlineBefore}; a row missing its
     * deadline falls back to its creation time against the gateway's
     * 30-minute ceiling ({@code createdBefore = now − 30 min}). Filtering
     * here, not in Java, keeps a page of young checkouts on a busy on-sale
     * from starving the old ones.
     */
    @Query("""
            select p from Payment p
             where p.status = :status
               and p.paymentRail = :rail
               and ((p.codeExpiresAt is not null and p.codeExpiresAt < :deadlineBefore)
                 or (p.codeExpiresAt is null and p.createdAt < :createdBefore))
             order by p.createdAt asc
            """)
    List<Payment> findOpenPastCeiling(
            @Param("status") Payment.PaymentStatus status,
            @Param("rail") PaymentRail rail,
            @Param("deadlineBefore") Instant deadlineBefore,
            @Param("createdBefore") Instant createdBefore,
            Pageable pageable);

    /**
     * Settlement reconciliation: every code-bearing row created in the
     * window, regardless of status — the our-side input of the nightly match
     * against InnBucks' mini statement.
     */
    List<Payment> findByInnbucksCodeIsNotNullAndCreatedAtBetween(
            Instant start, Instant end, Pageable pageable);

    /**
     * Exception workbasket: TOKEN_ISSUED rows whose code expiry passed long
     * enough ago that the poller SHOULD have resolved them — still being
     * open means the upstream status is unreadable (the never-guess rule
     * keeps them blocking the slot until a human or InnBucks answers).
     */
    List<Payment> findByStatusAndCodeExpiresAtBefore(
            Payment.PaymentStatus status, Instant cutoff, Pageable pageable);

    /**
     * Workbasket: the Paynow late-money cases raised since {@code since} whose
     * row is closed or confirmed (paid after we closed it; refunded or disputed
     * after the booking was confirmed). Those rows never reach the other
     * workbasket categories, and the alert on them is one-shot. Flagged rows
     * parked IN_DOUBT are listed with IN_DOUBT already.
     */
    List<Payment> findByPaynowFlaggedAtAfterAndStatusInOrderByPaynowFlaggedAtAsc(
            Instant since, java.util.Collection<Payment.PaymentStatus> statuses, Pageable pageable);

    /**
     * Replay lookup for the public {@code POST /payments} entry: when an
     * order already has an active-or-successful payment, the endpoint
     * returns THAT row's receipt (idempotent-replay semantics — a double-tap
     * must never surface as an error to the FE).
     */
    Optional<Payment> findFirstByOrderTypeAndOrderRefAndStatusInOrderByCreatedAtDesc(
            OrderType orderType, String orderRef, Collection<Payment.PaymentStatus> statuses);

    /**
     * The order's latest attempt in {@code statuses} that was handed to an
     * operator. With FAILED and EXPIRED, that is a Paynow attempt we closed
     * which Paynow then reported paid: {@code POST /payments} answers it
     * instead of starting a second payment for an order already paid.
     */
    Optional<Payment> findFirstByOrderTypeAndOrderRefAndPaynowFlaggedAtIsNotNullAndStatusInOrderByCreatedAtDesc(
            OrderType orderType, String orderRef, Collection<Payment.PaymentStatus> statuses);

    /** The order's latest attempt on one rail — the EcoCash decline hold reads it. */
    Optional<Payment> findFirstByOrderTypeAndOrderRefAndPaymentRailOrderByCreatedAtDesc(
            OrderType orderType, String orderRef, innbucks.paymentservice.entity.PaymentRail paymentRail);
}
