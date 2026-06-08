package innbucks.paymentservice.service;

import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.Payment.PaymentStatus;
import innbucks.paymentservice.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Ledger writes for {@link Payment}. Mirrors {@link TransactionService} —
 * each method commits in its OWN transaction ({@link Propagation#REQUIRES_NEW})
 * so the PENDING row is durable before any upstream call, and a downstream
 * failure or local rollback cannot disappear the audit trail.
 *
 * <p>Critical invariant: openPending MUST commit before
 * {@link InnbucksCoreGatewayClient}{@code .debit(...)} is invoked. If veengu
 * succeeds but markSucceeded fails (DB blip, JVM crash) the row stays
 * PENDING and the reconciliation job picks it up. Without REQUIRES_NEW a
 * failure in the outer transaction would roll the PENDING insert back and
 * the local ledger would have no record of a debit that actually moved
 * money — the worst class of bug in a banking system.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRecordService {

    private final PaymentRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment openPending(Payment draft) {
        draft.setStatus(PaymentStatus.PENDING);
        if (draft.getCorrelationId() == null) {
            draft.setCorrelationId(MDC.get("correlationId"));
        }
        Payment saved = repository.save(draft);
        log.info("payment PENDING id={} paymentReference={} bookingId={} amount={} currency={}",
                saved.getId(), saved.getPaymentReference(), saved.getBookingId(),
                saved.getAmount(), saved.getCurrency());
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(UUID id, String veenguTransactionId, String confirmationNumber) {
        Payment payment = repository.findById(id).orElse(null);
        if (payment == null) {
            // Defensive no-op: the row should exist (we just opened it). If it
            // doesn't, something deleted it out from under us — log loudly but
            // don't throw and mask the upstream outcome from the caller.
            log.error("markSucceeded called for missing payment id={}", id);
            return;
        }
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setVeenguTransactionId(veenguTransactionId);
        payment.setConfirmationNumber(confirmationNumber);
        payment.setCompletedAt(Instant.now());
        repository.save(payment);
        log.info("payment SUCCEEDED id={} paymentReference={} veenguTransactionId={} confirmation={}",
                payment.getId(), payment.getPaymentReference(), veenguTransactionId, confirmationNumber);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, String upstreamCode, String upstreamMessage) {
        Payment payment = repository.findById(id).orElse(null);
        if (payment == null) {
            log.error("markFailed called for missing payment id={}", id);
            return;
        }
        payment.setStatus(PaymentStatus.FAILED);
        payment.setUpstreamErrorCode(truncate(upstreamCode, 64));
        payment.setUpstreamErrorMessage(truncate(upstreamMessage, 500));
        payment.setCompletedAt(Instant.now());
        repository.save(payment);
        log.info("payment FAILED id={} paymentReference={} upstreamCode={}",
                payment.getId(), payment.getPaymentReference(), upstreamCode);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
