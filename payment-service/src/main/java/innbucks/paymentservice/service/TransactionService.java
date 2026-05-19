package innbucks.paymentservice.service;

import innbucks.paymentservice.client.OradianMiddlewareException;
import innbucks.paymentservice.entity.Transaction;
import innbucks.paymentservice.entity.TransactionStatus;
import innbucks.paymentservice.messaging.TransactionCompletedEvent;
import innbucks.paymentservice.repository.TransactionRepository;
import innbucks.paymentservice.util.MsisdnMasking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;
    private final TransferLimitService transferLimitService;

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
                MsisdnMasking.mask(saved.getCustomerPhone()), saved.getAmount(),
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
            // Publish AFTER the row is in the persistence context. The
            // @TransactionalEventListener on the consumer side fires only
            // AFTER_COMMIT so this never emits for a transaction that
            // ultimately rolls back.
            eventPublisher.publishEvent(toEvent(tx));
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
            // Release the Redis velocity budget for this row — Oradian
            // definitively rejected the call, the money didn't move,
            // so this amount shouldn't count against the customer's
            // daily cap. Without this release a customer who hits
            // "Insufficient funds" five times would have their cap
            // artificially tightened by the sum of those failed
            // attempts until midnight. Best-effort: a Redis hiccup
            // here logs but doesn't throw.
            transferLimitService.releaseBudget(
                    tx.getSourceAccountId(), tx.getAmount(), tx.getTransactionDate());
            // Same AFTER_COMMIT pattern as markSucceeded. Failed transfers
            // are still observable events that downstream consumers
            // (notification service, BI) want — "your transfer was
            // rejected, here's why" UX needs them.
            eventPublisher.publishEvent(toEvent(tx));
        }, () -> log.error("markFailed called for unknown txId={} — investigate", id));
    }

    /**
     * Map the persistent Transaction to its outbound event shape. Carries
     * the full phone number unmasked — downstream consumers (the future
     * notification service) need it to send the SMS / push. Kafka is
     * internal-network only.
     */
    private static TransactionCompletedEvent toEvent(Transaction tx) {
        return new TransactionCompletedEvent(
                UUID.randomUUID(),
                Instant.now(),
                tx.getId(),
                tx.getTransactionType() == null ? null : tx.getTransactionType().name(),
                tx.getStatus() == null ? null : tx.getStatus().name(),
                tx.getCustomerPhone(),
                tx.getSourceAccountId(),
                tx.getDestinationAccountId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getPaymentMethodName(),
                tx.getNotes(),
                tx.getTransactionDate(),
                tx.getTransactionBranchId(),
                tx.getCreatedAt(),
                tx.getCompletedAt(),
                tx.getOradianTransactionId(),
                tx.getOradianReferenceNumber(),
                tx.getOradianCommandId(),
                tx.getFailureCode(),
                tx.getFailureMessage(),
                tx.getCorrelationId()
        );
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
}
