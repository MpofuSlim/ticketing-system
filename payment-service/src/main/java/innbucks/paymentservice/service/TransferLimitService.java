package innbucks.paymentservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Velocity gate on POST /payments/transfer and /payments/withdraw.
 * Two limits enforced before payment-service hits Oradian:
 *
 * <ol>
 *   <li>{@code per-transaction-max} — caps the single-request amount.
 *       A typo turning {@code "100"} into {@code "100000"} gets rejected
 *       here instead of moving real money.</li>
 *   <li>{@code per-day-max} — caps the running daily total per source
 *       account. Calendar-day semantics (server timezone).</li>
 * </ol>
 *
 * <p>Daily total is tracked in Redis (key
 * {@code velocity:daily:{accountId}:{date}}) via atomic {@code INCRBY}.
 * The earlier SQL-based check ({@code SUM(amount) ... WHERE source_account_id=?
 * AND transaction_date=? AND status IN (PENDING, SUCCEEDED)}) had a
 * Time-Of-Check / Time-Of-Use race: two parallel requests could both
 * read the same SUM before either inserted its PENDING row, letting
 * a customer move ~one extra transaction's worth past the daily cap.
 * {@code INCRBY} resolves this atomically — second caller sees the
 * post-increment total and bails immediately if it's over.
 *
 * <p>Lifecycle relative to a transaction:
 * <ul>
 *   <li>{@link #enforce} — INCRBY the daily counter by the requested
 *       amount. If the new total is over the cap, DECRBY back and
 *       reject. Otherwise the budget is "committed".</li>
 *   <li>{@link #releaseBudget} — DECRBY when the transaction definitely
 *       won't move money: the local openPending failed before we
 *       reached Oradian, or Oradian rejected the call ({@code markFailed}).
 *       Without the release, a customer's daily cap would silently
 *       tighten every time Oradian said "Insufficient funds" — punishing
 *       honest mistakes with a smaller budget.</li>
 * </ul>
 *
 * <p><b>Fail-closed on Redis unreachability:</b> if Redis is down, the
 * counter is unverifiable so we reject the call. Better to deny a
 * legitimate transfer for a moment than allow an attacker to bypass
 * velocity caps when the rate-limit infrastructure is degraded.
 *
 * <p>Known constraint: Redis persistence (AOF/RDB) is assumed. A clean
 * wipe of Redis would zero today's counters; customers could then
 * legitimately exceed the cap until midnight (when the TTL would have
 * expired anyway). For higher assurance, cold-start the counters from
 * the SQL ledger on boot — out of scope for this change.
 */
@Service
@Slf4j
public class TransferLimitService {

    private static final String KEY_PREFIX = "velocity:daily:";
    /** TTL just past midnight + buffer; counter naturally rolls each day. */
    private static final Duration COUNTER_TTL = Duration.ofHours(25);
    /**
     * Multiplier to take a 4-decimal-place BigDecimal into a long so
     * Redis {@code INCRBY} (integer-only) works without precision loss.
     * Matches {@code NUMERIC(19,4)} on the ledger column.
     */
    private static final int SCALE = 4;

    private final BigDecimal perTransactionMax;
    private final BigDecimal perDayMax;
    private final StringRedisTemplate redis;

    public TransferLimitService(
            @Value("${innbucks.transfer-limits.per-transaction-max:100000}") BigDecimal perTransactionMax,
            @Value("${innbucks.transfer-limits.per-day-max:500000}") BigDecimal perDayMax,
            StringRedisTemplate redis) {
        if (perTransactionMax.signum() <= 0 || perDayMax.signum() <= 0) {
            throw new IllegalArgumentException(
                    "transfer-limits must be positive: perTransactionMax=" + perTransactionMax +
                            " perDayMax=" + perDayMax);
        }
        this.perTransactionMax = perTransactionMax;
        this.perDayMax = perDayMax;
        this.redis = redis;
    }

    /**
     * Reserve {@code amount} of the daily budget for {@code accountId}
     * by atomically incrementing Redis. Throws if the per-transaction
     * cap is exceeded (pre-Redis check, no side effects) or if the
     * running daily total would exceed the cap (post-INCRBY check, the
     * over-INCRBY is rolled back before throwing).
     *
     * <p>Callers MUST pair every successful {@code enforce} with either
     * a completed transaction (money moved → counter stays) OR a
     * {@link #releaseBudget} (money didn't move → counter rolls back).
     * Otherwise the customer's daily cap is permanently tightened until
     * midnight.
     */
    public void enforce(String accountId, BigDecimal amount) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");

        if (amount.compareTo(perTransactionMax) > 0) {
            log.warn("Per-transaction limit exceeded account={} amount={} max={}",
                    accountId, amount, perTransactionMax);
            throw new IllegalArgumentException(
                    "Per-transaction limit exceeded (max " + perTransactionMax.toPlainString() +
                            ", requested " + amount.toPlainString() + ")");
        }

        long amountUnits = toUnits(amount);
        long perDayMaxUnits = toUnits(perDayMax);
        String key = key(accountId, LocalDate.now());

        Long newTotalUnits;
        try {
            newTotalUnits = redis.opsForValue().increment(key, amountUnits);
            // Idempotent — Redis ignores the call if the TTL is already
            // shorter than the requested one, and we want every key to
            // have a TTL so they auto-clean past midnight.
            redis.expire(key, COUNTER_TTL);
        } catch (RuntimeException ex) {
            log.error("Redis velocity counter unreachable account={}; failing closed", accountId, ex);
            throw new IllegalArgumentException(
                    "Velocity service is temporarily unavailable; please try again in a moment");
        }
        if (newTotalUnits == null) {
            // Redis returned null — shouldn't happen for INCRBY, defence
            // in depth in case of a Lettuce / connection-pool oddity.
            throw new IllegalArgumentException(
                    "Velocity service is temporarily unavailable; please try again in a moment");
        }

        if (newTotalUnits > perDayMaxUnits) {
            // Roll back the increment. Best-effort: if THIS call also
            // fails, the counter ends up artificially high until midnight,
            // but the customer still gets the right answer (rejected).
            try {
                redis.opsForValue().increment(key, -amountUnits);
            } catch (RuntimeException ignored) {
                log.warn("Failed to roll back over-cap INCRBY account={} amount={}; counter will recover at TTL",
                        accountId, amount);
            }
            BigDecimal projected = fromUnits(newTotalUnits);
            BigDecimal todaySoFar = projected.subtract(amount);
            log.warn("Daily limit exceeded account={} today={} requested={} projected={} max={}",
                    accountId, todaySoFar, amount, projected, perDayMax);
            throw new IllegalArgumentException(
                    "Daily limit exceeded (max " + perDayMax.toPlainString() +
                            ", today " + todaySoFar.toPlainString() +
                            ", requested " + amount.toPlainString() +
                            ", projected " + projected.toPlainString() + ")");
        }

        log.debug("Velocity check OK account={} amount={} newDailyTotal={} max={}",
                accountId, amount, fromUnits(newTotalUnits), perDayMax);
    }

    /**
     * Roll back a budget reservation. Called by
     * {@link TransactionService#markFailed} when Oradian rejected the
     * call (the money definitely didn't move) and by
     * {@link innbucks.paymentservice.controller.TransfersController} if
     * the local {@code openPending} insert fails right after
     * {@link #enforce} (rare DB blip; prevents the counter being
     * permanently over-counted by failed retries).
     *
     * <p>Pass the original {@code transactionDate} of the row so the
     * release lands on the correct daily counter — a row inserted at
     * 23:59 and failed at 00:01 spans two dates.
     */
    public void releaseBudget(String accountId, BigDecimal amount, LocalDate date) {
        if (accountId == null || amount == null || date == null) return;
        long amountUnits = toUnits(amount);
        String key = key(accountId, date);
        try {
            Long newTotal = redis.opsForValue().increment(key, -amountUnits);
            log.info("Released velocity budget account={} date={} amount={} newDailyTotal={}",
                    accountId, date, amount,
                    newTotal == null ? "?" : fromUnits(newTotal).toPlainString());
        } catch (RuntimeException ex) {
            // Don't throw — the caller's job (markFailed, etc.) must
            // complete even if Redis hiccups. Worst case: customer's
            // cap is artificially tight on this account+date until the
            // TTL expires at midnight.
            log.error("Failed to release velocity budget account={} date={} amount={}; counter will recover at TTL",
                    accountId, date, amount, ex);
        }
    }

    private static String key(String accountId, LocalDate date) {
        return KEY_PREFIX + accountId + ":" + date;
    }

    /** Convert NUMERIC(19,4) BigDecimal to long for Redis INCRBY. */
    private static long toUnits(BigDecimal value) {
        return value.movePointRight(SCALE).longValueExact();
    }

    /** Inverse of {@link #toUnits}. */
    private static BigDecimal fromUnits(long units) {
        return BigDecimal.valueOf(units, SCALE);
    }
}
