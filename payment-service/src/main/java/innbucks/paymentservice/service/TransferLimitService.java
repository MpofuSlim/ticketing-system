package innbucks.paymentservice.service;

import innbucks.paymentservice.entity.TransactionStatus;
import innbucks.paymentservice.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * Velocity gate on POST /payments/transfer and /payments/withdraw. Two
 * limits enforced before payment-service hits Oradian:
 *
 * <ol>
 *   <li>{@code per-transaction-max} — caps the single-request amount.
 *       A typo turning {@code "100"} into {@code "100000"} gets rejected
 *       here instead of moving real money.</li>
 *   <li>{@code per-day-max} — caps the sum of today's PENDING +
 *       SUCCEEDED rows on the same source account plus the new amount.
 *       Calendar-day semantics (server timezone) — most retail-banking
 *       customers expect "your daily limit resets at midnight".</li>
 * </ol>
 *
 * <p>Defaults are placeholders (100k / 500k); override per deployment via
 * {@code PER_TX_LIMIT} / {@code PER_DAY_LIMIT} env vars. Real-world caps
 * should also vary by KYC tier, account type, and country — those are
 * follow-up work.
 *
 * <p>Known limitation (out of scope for this change): two parallel
 * requests can both pass the SUM check before either commits its PENDING
 * row, letting a determined attacker race past the daily cap by ~one
 * concurrent batch. The audit lists concurrency as a separate item;
 * fixing it cleanly needs row-level locking on an account-summary row or
 * a Redis-backed counter. Not addressed here.
 */
@Service
@Slf4j
public class TransferLimitService {

    private static final Set<TransactionStatus> COUNTED_STATUSES =
            Set.of(TransactionStatus.PENDING, TransactionStatus.SUCCEEDED);

    private final BigDecimal perTransactionMax;
    private final BigDecimal perDayMax;
    private final TransactionRepository repository;

    public TransferLimitService(
            @Value("${innbucks.transfer-limits.per-transaction-max:100000}") BigDecimal perTransactionMax,
            @Value("${innbucks.transfer-limits.per-day-max:500000}") BigDecimal perDayMax,
            TransactionRepository repository) {
        if (perTransactionMax.signum() <= 0 || perDayMax.signum() <= 0) {
            throw new IllegalArgumentException(
                    "transfer-limits must be positive: perTransactionMax=" + perTransactionMax +
                            " perDayMax=" + perDayMax);
        }
        this.perTransactionMax = perTransactionMax;
        this.perDayMax = perDayMax;
        this.repository = repository;
    }

    /**
     * Throws {@link IllegalArgumentException} if the new amount would breach
     * either cap. {@code GlobalExceptionHandler} maps that to a 400 with
     * the exception message surfaced in {@code ApiResult.message} — clean
     * actionable error for the FE without needing a new exception type.
     */
    @Transactional(readOnly = true)
    public void enforce(String accountId, BigDecimal amount) {
        if (amount.compareTo(perTransactionMax) > 0) {
            log.warn("Per-transaction limit exceeded account={} amount={} max={}",
                    accountId, amount, perTransactionMax);
            throw new IllegalArgumentException(
                    "Per-transaction limit exceeded (max " + perTransactionMax.toPlainString() +
                            ", requested " + amount.toPlainString() + ")");
        }
        BigDecimal todaySoFar = repository.sumByAccountAndDateAndStatusIn(
                accountId, LocalDate.now(), COUNTED_STATUSES);
        if (todaySoFar == null) todaySoFar = BigDecimal.ZERO;
        BigDecimal projected = todaySoFar.add(amount);
        if (projected.compareTo(perDayMax) > 0) {
            log.warn("Daily limit exceeded account={} today={} requested={} projected={} max={}",
                    accountId, todaySoFar, amount, projected, perDayMax);
            throw new IllegalArgumentException(
                    "Daily limit exceeded (max " + perDayMax.toPlainString() +
                            ", today " + todaySoFar.toPlainString() +
                            ", requested " + amount.toPlainString() +
                            ", projected " + projected.toPlainString() + ")");
        }
        log.debug("Velocity check OK account={} amount={} todayBefore={} max={}",
                accountId, amount, todaySoFar, perDayMax);
    }
}
