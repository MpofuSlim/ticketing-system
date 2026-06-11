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
     * The veengu-flow states (TOKEN_ISSUED, CONSENTED, ...) gain their
     * entries when the direct-API adapter lands — no writer exists today,
     * so no transitions are legal into or out of them yet.
     */
    private static final Map<PaymentStatus, Set<PaymentStatus>> LEGAL_TRANSITIONS = Map.of(
            PaymentStatus.PENDING, EnumSet.of(
                    PaymentStatus.SUCCEEDED, PaymentStatus.FAILED,
                    PaymentStatus.IN_DOUBT, PaymentStatus.COMPLETED_UNCONFIRMED),
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(UUID id, String veenguTransactionId, String confirmationNumber) {
        transition(id, PaymentStatus.SUCCEEDED, "Debit completed and booking confirmed",
                veenguTransactionId, payment -> {
                    payment.setVeenguTransactionId(veenguTransactionId);
                    payment.setConfirmationNumber(confirmationNumber);
                    payment.setCompletedAt(Instant.now());
                });
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
