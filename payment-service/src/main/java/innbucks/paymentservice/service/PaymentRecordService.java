package innbucks.paymentservice.service;

import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.Payment.PaymentStatus;
import innbucks.paymentservice.entity.PaymentEvent;
import innbucks.paymentservice.repository.PaymentEventRepository;
import innbucks.paymentservice.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ledger writes for {@link Payment}. Mirrors {@link TransactionService} —
 * each method commits in its OWN transaction ({@link Propagation#REQUIRES_NEW})
 * so the PENDING row is durable before any upstream call, and a downstream
 * failure or local rollback cannot disappear the audit trail.
 *
 * <p>Banking-grade invariants enforced here:
 * <ul>
 *   <li><b>Write-ahead:</b> openPending MUST commit before the upstream debit
 *       is invoked — an upstream success with no local row is the worst class
 *       of bug in a payment system.</li>
 *   <li><b>Journalled transitions:</b> every status change appends a
 *       {@link PaymentEvent} row IN THE SAME TRANSACTION as the change, so the
 *       ledger and its history cannot diverge.</li>
 *   <li><b>Guarded transitions:</b> {@link #LEGAL_TRANSITIONS} is the only
 *       path between states. Terminal rows (SUCCEEDED / FAILED) are immutable;
 *       an illegal request is logged loudly and SKIPPED — never applied, and
 *       never thrown either, because masking the upstream outcome from the
 *       caller is worse than refusing a bookkeeping write (the journal of the
 *       refusal is the operator's breadcrumb).</li>
 *   <li><b>No guessing:</b> IN_DOUBT exists precisely so a timeout is never
 *       recorded as FAILED. Only a reconciler that has QUERIED the processor
 *       (or an operator) moves a row out of IN_DOUBT.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRecordService {

    /**
     * Statuses that occupy the booking's single payment slot (see the
     * {@code uq_payment_active_booking} partial index — this set must stay
     * the complement of that index's WHERE clause). Terminal failures free
     * the slot for a retry; everything else blocks a second payment.
     */
    public static final Set<PaymentStatus> ACTIVE_OR_SUCCEEDED =
            EnumSet.complementOf(EnumSet.of(
                    PaymentStatus.FAILED, PaymentStatus.REJECTED, PaymentStatus.EXPIRED));

    /**
     * The complete legal state machine. Anything not listed is refused.
     *
     * <p>The 2D-code flow's happy path is PENDING → TOKEN_ISSUED (code
     * minted + delivered) → SUCCEEDED (customer paid, booking confirmed).
     * TOKEN_ISSUED → EXPIRED closes a code that lapsed unpaid (frees the
     * booking slot); TOKEN_ISSUED → COMPLETED_UNCONFIRMED records
     * customer-paid-but-confirm-failed, which the reconciler's confirm-retry
     * loop promotes. The remaining reserved states (CONSENTED, EXECUTING,
     * REQUIRES_AUTH, REJECTED) still have no writer and no legal transitions.
     */
    private static final Map<PaymentStatus, Set<PaymentStatus>> LEGAL_TRANSITIONS = Map.of(
            PaymentStatus.PENDING, EnumSet.of(
                    PaymentStatus.TOKEN_ISSUED,
                    PaymentStatus.SUCCEEDED, PaymentStatus.FAILED,
                    PaymentStatus.IN_DOUBT, PaymentStatus.COMPLETED_UNCONFIRMED),
            PaymentStatus.TOKEN_ISSUED, EnumSet.of(
                    PaymentStatus.SUCCEEDED, PaymentStatus.COMPLETED_UNCONFIRMED,
                    PaymentStatus.FAILED, PaymentStatus.EXPIRED),
            PaymentStatus.IN_DOUBT, EnumSet.of(
                    PaymentStatus.SUCCEEDED, PaymentStatus.FAILED,
                    PaymentStatus.COMPLETED_UNCONFIRMED),
            PaymentStatus.COMPLETED_UNCONFIRMED, EnumSet.of(
                    PaymentStatus.SUCCEEDED)
    );

    private final PaymentRepository repository;
    private final PaymentEventRepository eventRepository;

    /**
     * UX pre-check companion to the DB's one-active-payment-per-booking
     * index: lets the caller return a clean 409 instead of surfacing a
     * constraint violation. The index remains the arbiter under races.
     */
    @Transactional(readOnly = true)
    public boolean hasActiveOrSucceededPayment(UUID bookingId) {
        return repository.existsByBookingIdAndStatusIn(bookingId, ACTIVE_OR_SUCCEEDED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment openPending(Payment draft) {
        draft.setStatus(PaymentStatus.PENDING);
        if (draft.getCorrelationId() == null) {
            draft.setCorrelationId(MDC.get("correlationId"));
        }
        Payment saved = repository.save(draft);
        recordEvent(saved, null, PaymentStatus.PENDING, "Ledger row opened before upstream call", null);
        log.info("payment PENDING id={} paymentReference={} bookingId={} amount={} currency={}",
                saved.getId(), saved.getPaymentReference(), saved.getBookingId(),
                saved.getAmount(), saved.getCurrency());
        return saved;
    }

    /**
     * Terminal success: money received AND booking confirmed. The upstream
     * reference lands in the legacy-named {@code veengu_transaction_id}
     * column — for 2D-code payments that's the InnBucks {@code authNumber}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(UUID id, String veenguTransactionId, String confirmationNumber) {
        transition(id, PaymentStatus.SUCCEEDED, "Debit completed and booking confirmed",
                veenguTransactionId, payment -> {
                    payment.setVeenguTransactionId(veenguTransactionId);
                    payment.setConfirmationNumber(confirmationNumber);
                    payment.setCompletedAt(Instant.now());
                });
    }

    /**
     * 2D-code flow: the InnBucks PAYMENT code was minted. Records both
     * handles (the customer-facing code and the {@code authNumber} the
     * status poller queries with) plus our local expiry deadline, in the
     * same transaction as the PENDING → TOKEN_ISSUED transition.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markTokenIssued(UUID id, String code, String authNumber, Instant expiresAt) {
        transition(id, PaymentStatus.TOKEN_ISSUED,
                "InnBucks payment code issued; awaiting customer approval", authNumber,
                payment -> {
                    payment.setInnbucksCode(code);
                    payment.setCodeAuthNumber(authNumber);
                    payment.setCodeExpiresAt(expiresAt);
                });
    }

    /**
     * 2D-code flow: the code lapsed unpaid (upstream says Expired/Timed Out,
     * or our local TTL passed with the code still New). Terminal — frees the
     * booking's payment slot so the customer can simply tap pay again for a
     * fresh code. NEVER call this on a row whose upstream status is unknown:
     * expiring a code the customer may have paid is the double-charge path.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExpired(UUID id, String detail) {
        transition(id, PaymentStatus.EXPIRED, detail, null,
                payment -> payment.setCompletedAt(Instant.now()));
    }

    /**
     * Append an annotation row to the journal WITHOUT a status change
     * (from == to). Used for events that matter to an auditor but aren't
     * transitions — e.g. which channel the payment code was delivered on,
     * or that delivery failed on all channels.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void noteEvent(UUID id, String detail) {
        Payment payment = repository.findById(id).orElse(null);
        if (payment == null) {
            log.error("payment journal note requested for missing payment id={} detail={}", id, detail);
            return;
        }
        recordEvent(payment, payment.getStatus(), payment.getStatus(), detail, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, String upstreamCode, String upstreamMessage) {
        transition(id, PaymentStatus.FAILED,
                "Upstream rejected: " + upstreamCode, null, payment -> {
                    payment.setUpstreamErrorCode(truncate(upstreamCode, 64));
                    payment.setUpstreamErrorMessage(truncate(upstreamMessage, 500));
                    payment.setCompletedAt(Instant.now());
                });
    }

    /**
     * The upstream call timed out or returned an unclassifiable outcome —
     * money MAY have moved. The row is parked, never auto-failed; the
     * reconciler (or an operator, by checking the processor's records)
     * resolves it. The customer-facing response for such rows is PROCESSING.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markInDoubt(UUID id, String reason) {
        transition(id, PaymentStatus.IN_DOUBT, reason, null, payment -> { });
    }

    /**
     * Money DEFINITELY moved (upstream reported COMPLETED with the given
     * reference) but the booking confirm failed. Records the upstream
     * reference immediately — it is the recovery handle — and leaves the
     * row for the reconciler's confirm-retry loop. This state previously
     * (incorrectly) landed as FAILED, which told the ledger no money moved.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompletedUnconfirmed(UUID id, String veenguTransactionId, String detail) {
        transition(id, PaymentStatus.COMPLETED_UNCONFIRMED, detail, veenguTransactionId,
                payment -> payment.setVeenguTransactionId(veenguTransactionId));
    }

    /**
     * Reconciler resolution of {@link PaymentStatus#COMPLETED_UNCONFIRMED}:
     * the booking confirm finally went through, promote to SUCCEEDED.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveUnconfirmed(UUID id, String confirmationNumber) {
        transition(id, PaymentStatus.SUCCEEDED,
                "Reconciler confirmed booking after earlier failure", null, payment -> {
                    payment.setConfirmationNumber(confirmationNumber);
                    payment.setCompletedAt(Instant.now());
                });
    }

    private void transition(UUID id, PaymentStatus target, String detail,
                            String upstreamRef, java.util.function.Consumer<Payment> mutator) {
        Payment payment = repository.findById(id).orElse(null);
        if (payment == null) {
            // Defensive no-op: the row should exist (we just opened it). If it
            // doesn't, something deleted it out from under us — log loudly but
            // don't throw and mask the upstream outcome from the caller.
            log.error("payment transition to {} requested for missing payment id={}", target, id);
            return;
        }
        PaymentStatus from = payment.getStatus();
        if (from == target) {
            // Idempotent replay (e.g. reconciler and request thread racing to
            // record the same outcome) — nothing to do, nothing to journal.
            log.debug("payment transition no-op id={} already {}", id, target);
            return;
        }
        Set<PaymentStatus> allowed = LEGAL_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(target)) {
            // Illegal transition — most critically: terminal rows are immutable.
            // Refuse, journal nothing, and shout. (A FAILED->SUCCEEDED request,
            // for instance, means two code paths disagree about an outcome —
            // an operator must look, not a best-effort overwrite.)
            log.error("ILLEGAL payment transition refused id={} paymentReference={} {} -> {} detail={}",
                    id, payment.getPaymentReference(), from, target, detail);
            return;
        }
        mutator.accept(payment);
        payment.setStatus(target);
        repository.save(payment);
        recordEvent(payment, from, target, detail, upstreamRef);
        log.info("payment {} -> {} id={} paymentReference={} upstreamRef={}",
                from, target, payment.getId(), payment.getPaymentReference(), upstreamRef);
    }

    private void recordEvent(Payment payment, PaymentStatus from, PaymentStatus to,
                             String detail, String upstreamRef) {
        eventRepository.save(PaymentEvent.builder()
                .paymentId(payment.getId())
                .fromStatus(from == null ? null : from.name())
                .toStatus(to.name())
                .detail(truncate(detail, 500))
                .upstreamRef(truncate(upstreamRef, 64))
                .correlationId(payment.getCorrelationId() != null
                        ? payment.getCorrelationId() : MDC.get("correlationId"))
                .build());
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
