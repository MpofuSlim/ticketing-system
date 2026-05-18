package innbucks.paymentservice.service;

import innbucks.paymentservice.client.OradianMiddlewareException;
import innbucks.paymentservice.entity.Transaction;
import innbucks.paymentservice.entity.TransactionStatus;
import innbucks.paymentservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Owns writes to the {@code transactions} ledger. Each method commits in
 * its own transaction ({@link Propagation#REQUIRES_NEW}) — that's the
 * whole point of the ledger: a PENDING row must be durable before the
 * Oradian call so an Oradian-success-but-local-rollback can't disappear
 * the record of the attempt. The controller calls {@link #openPending}
 * first, then {@link #markSucceeded} or {@link #markFailed} on the
 * Oradian outcome.
 *
 * <p>Concretely: if {@link #markSucceeded} throws (DB outage, etc.) after
 * Oradian moved the money, the row stays PENDING. A reconciliation sweep
 * picks it up, queries Oradian by the local ID (eventually via Oradian's
 * lookup-by-reference) or by the customer's deposit history, and flips
 * the row to its true final state — without re-issuing the Oradian call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final TransactionRepository repository;

    /**
     * Insert a row in PENDING state. Returns the persisted entity (with id
     * assigned) so the caller can pass its id to {@link #markSucceeded}
     * or {@link #markFailed}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction openPending(Transaction draft) {
        draft.setStatus(TransactionStatus.PENDING);
        if (draft.getCorrelationId() == null) {
            // Stamp the current correlation id from MDC so support tickets
            // can join a transaction row to its request log.
            draft.setCorrelationId(MDC.get(CORRELATION_ID_MDC_KEY));
        }
        Transaction saved = repository.save(draft);
        log.info("Transaction PENDING txId={} type={} phone={} amount={} src={} dst={}",
                saved.getId(), saved.getTransactionType(),
                maskPhone(saved.getCustomerPhone()), saved.getAmount(),
                saved.getSourceAccountId(), saved.getDestinationAccountId());
        return saved;
    }

    /**
     * Flip PENDING -> SUCCEEDED and stamp the Oradian-assigned identifiers.
     * Best-effort no-op if the row doesn't exist (defensive — shouldn't
     * happen given the caller just inserted it).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(UUID id, String oradianTransactionId,
                              String oradianReferenceNumber, String oradianCommandId) {
        repository.findById(id).ifPresentOrElse(tx -> {
            tx.setStatus(TransactionStatus.SUCCEEDED);
            tx.setOradianTransactionId(oradianTransactionId);
            tx.setOradianReferenceNumber(oradianReferenceNumber);
            tx.setOradianCommandId(oradianCommandId);
            tx.setCompletedAt(Instant.now());
            repository.save(tx);
            log.info("Transaction SUCCEEDED txId={} oradianTxnId={} reference={}",
                    id, oradianTransactionId, oradianReferenceNumber);
        }, () -> log.error("markSucceeded called for unknown txId={} — investigate", id));
    }

    /**
     * Flip PENDING -> FAILED with a coarse code and a short message. The
     * message is truncated at 500 chars to fit the column. Upstream
     * responses can be verbose; the operator gets the full text via the
     * server log line at the time of failure.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, OradianMiddlewareException ex) {
        String code = classifyFailure(ex);
        String message = truncate(ex.getMessage(), 500);
        repository.findById(id).ifPresentOrElse(tx -> {
            tx.setStatus(TransactionStatus.FAILED);
            tx.setFailureCode(code);
            tx.setFailureMessage(message);
            tx.setCompletedAt(Instant.now());
            repository.save(tx);
            log.warn("Transaction FAILED txId={} code={} message={}", id, code, message);
        }, () -> log.error("markFailed called for unknown txId={} — investigate", id));
    }

    /**
     * Coarse failure classification used for ledger queries / metrics.
     * Bucket by upstream status family rather than echoing the raw code so
     * future Oradian middleware changes don't churn the ledger schema.
     */
    private static String classifyFailure(OradianMiddlewareException ex) {
        int status = ex.getStatusCode();
        if (status == 502 || status == 503 || status == 504) return "UPSTREAM_UNAVAILABLE";
        if (status >= 400 && status < 500) return "UPSTREAM_REJECTED";
        if (status >= 500) return "UPSTREAM_FAILED";
        return "INTERNAL_ERROR";
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Last-4-digits mask for log lines. Avoid emitting full MSISDNs into
     * structured logs — Data Protection Act and PCI-style banking standards
     * treat full phone numbers as account-binding PII.
     */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4) return "****";
        return "****" + phone.substring(phone.length() - 4);
    }
}
