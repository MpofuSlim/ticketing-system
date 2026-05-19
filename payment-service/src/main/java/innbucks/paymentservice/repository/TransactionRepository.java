package innbucks.paymentservice.repository;

import innbucks.paymentservice.entity.Transaction;
import innbucks.paymentservice.entity.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * JpaRepository over the {@code transactions} ledger. Today the writes go
 * through {@link innbucks.paymentservice.service.TransactionService}; the
 * one read is the per-day SUM used by
 * {@link innbucks.paymentservice.service.TransferLimitService} to enforce
 * daily caps. History / reconciliation finders land as separate work.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Sum of {@code amount} for one source account on a single calendar day,
     * filtered to the lifecycle states that have actually moved money or are
     * about to. Used by the velocity check on POST /payments/transfer +
     * /payments/withdraw to compare "today so far" against the daily cap.
     *
     * <p>Includes {@link TransactionStatus#PENDING} alongside
     * {@link TransactionStatus#SUCCEEDED} on purpose: a customer firing N
     * parallel requests of just-under-the-cap should not race past the
     * limit by exploiting the window between status flips. FAILED rows
     * don't count — those didn't actually leave the account.
     *
     * <p>{@code COALESCE} so an account with no rows yet returns 0 instead
     * of null, which keeps the calling service from null-checking.
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
            "WHERE t.sourceAccountId = :accountId " +
            "AND t.transactionDate = :date " +
            "AND t.status IN :statuses")
    BigDecimal sumByAccountAndDateAndStatusIn(
            @Param("accountId") String accountId,
            @Param("date") LocalDate date,
            @Param("statuses") Collection<TransactionStatus> statuses);

    /**
     * Customer-facing history finder: rows where {@code customer_phone}
     * matches the JWT-derived subject and {@code transaction_date} falls in
     * the requested window. Backed by the
     * {@code idx_transactions_customer_phone_created_at} index from V1 —
     * default sort {@code createdAt DESC} keeps the index scan cheap.
     *
     * <p>Filtering by {@code customerPhone} (rather than by source account)
     * gives the customer all their transactions in one view regardless of
     * which of their Oradian accounts moved the money.
     */
    Page<Transaction> findByCustomerPhoneAndTransactionDateBetween(
            String customerPhone, LocalDate fromDate, LocalDate toDate, Pageable pageable);

    /**
     * Reconciliation finder: rows still in PENDING whose {@code created_at}
     * is older than the supplied cutoff. The reconciliation job scans this
     * every minute (configurable) and surfaces each via log + Prometheus
     * counter so operators can investigate before drift accumulates.
     *
     * <p>Backed by the {@code idx_transactions_status_created_at} index
     * from V1 — the filter is essentially "PENDING && created_at &lt; X"
     * which is exactly what that index covers.
     *
     * <p>Caller passes a {@link Pageable} so a long-broken system doesn't
     * pull millions of rows into memory in one scan. The job iterates
     * pages until the page is short of size.
     */
    @Query("SELECT t FROM Transaction t " +
            "WHERE t.status = innbucks.paymentservice.entity.TransactionStatus.PENDING " +
            "AND t.createdAt < :cutoff " +
            "ORDER BY t.createdAt ASC")
    List<Transaction> findStalePending(@Param("cutoff") Instant cutoff, Pageable pageable);
}
