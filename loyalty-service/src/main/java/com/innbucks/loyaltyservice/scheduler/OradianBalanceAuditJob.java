package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.client.OradianMiddlewareClient;
import com.innbucks.loyaltyservice.client.OradianMiddlewareException;
import com.innbucks.loyaltyservice.client.dto.DepositAccountSnapshot;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Nightly drift detector between local {@code wallet.balance} and the
 * Oradian-canonical balance of the customer's LPW account.
 *
 * <p>For every wallet with an {@code oradian_account_id} set, calls
 * {@code GET /internal/deposits/{accountId}} and compares the result
 * to the local balance. Any wallet whose two numbers disagree gets:
 *
 * <ul>
 *   <li>a WARN log line with both values + the drift magnitude,</li>
 *   <li>a {@code loyalty.oradian_sync.drift} counter increment (per
 *       direction: {@code local_high} vs {@code local_low}).</li>
 * </ul>
 *
 * <p>The job is read-only and never auto-corrects — drift is an
 * operator-investigates signal, not a self-heal trigger. Auto-correct
 * would mask the root cause (a buggy sync path, a missing reversal,
 * etc.) and risk amplifying the bug. Once root-caused, operators
 * apply the corrective entry manually via the appropriate flow.
 *
 * <p>Pagination keeps memory bounded — a 10M-wallet deployment
 * doesn't load everything into RAM. Upstream failures on a single
 * account are caught + logged + counted; they don't abort the rest
 * of the sweep.
 *
 * <p>Schedule + page size + tolerance configurable via:
 * <pre>
 * loyalty.oradian-sync.balance-audit-cron  (default: 0 0 3 * * * -> 3am daily)
 * loyalty.oradian-sync.balance-audit-page-size  (default: 200)
 * loyalty.oradian-sync.balance-audit-tolerance  (default: 0.0001)
 * </pre>
 */
@Component
public class OradianBalanceAuditJob {

    private static final Logger log = LoggerFactory.getLogger(OradianBalanceAuditJob.class);

    private final boolean enabled;
    private final int pageSize;
    private final BigDecimal tolerance;
    private final WalletRepository walletRepository;
    private final OradianMiddlewareClient client;

    private final Counter walletsChecked;
    private final Counter driftLocalHigh;
    private final Counter driftLocalLow;
    private final Counter lookupFailures;

    public OradianBalanceAuditJob(
            @Value("${loyalty.oradian-sync.enabled:false}") boolean enabled,
            @Value("${loyalty.oradian-sync.balance-audit-page-size:200}") int pageSize,
            @Value("${loyalty.oradian-sync.balance-audit-tolerance:0.0001}") BigDecimal tolerance,
            WalletRepository walletRepository,
            OradianMiddlewareClient client,
            MeterRegistry registry) {
        this.enabled = enabled;
        this.pageSize = pageSize;
        this.tolerance = tolerance;
        this.walletRepository = walletRepository;
        this.client = client;
        this.walletsChecked = Counter.builder("loyalty.oradian_sync.balance_audit.checked")
                .description("Wallets compared against Oradian balance")
                .register(registry);
        this.driftLocalHigh = Counter.builder("loyalty.oradian_sync.drift")
                .tag("direction", "local_high")
                .description("Wallets where local.balance > Oradian.balance by more than the tolerance")
                .register(registry);
        this.driftLocalLow = Counter.builder("loyalty.oradian_sync.drift")
                .tag("direction", "local_low")
                .description("Wallets where local.balance < Oradian.balance by more than the tolerance")
                .register(registry);
        this.lookupFailures = Counter.builder("loyalty.oradian_sync.balance_audit.lookup_failures")
                .description("Per-wallet Oradian lookup failures during the balance audit sweep")
                .register(registry);
    }

    @Scheduled(cron = "${loyalty.oradian-sync.balance-audit-cron:0 0 3 * * *}")
    @SchedulerLock(name = "loyaltyOradianBalanceAudit",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void audit() {
        if (!enabled) {
            return;
        }
        long totalDrift = 0;
        long totalChecked = 0;
        int pageNumber = 0;
        Page<Wallet> page;
        do {
            page = walletRepository.findByOradianAccountIdIsNotNull(
                    PageRequest.of(pageNumber, pageSize, Sort.by("id").ascending()));
            for (Wallet w : page.getContent()) {
                totalChecked++;
                if (checkOne(w)) {
                    totalDrift++;
                }
            }
            pageNumber++;
        } while (page.hasNext());

        log.info("OradianBalanceAuditJob complete walletsChecked={} driftDetected={}",
                totalChecked, totalDrift);
    }

    /**
     * @return true if drift was detected (counter already incremented).
     *         false on match, lookup failure, or any non-drift outcome.
     */
    private boolean checkOne(Wallet wallet) {
        walletsChecked.increment();
        DepositAccountSnapshot snapshot;
        try {
            snapshot = client.getDepositAccount(wallet.getOradianAccountId());
        } catch (OradianMiddlewareException e) {
            lookupFailures.increment();
            log.warn("Balance audit lookup failed walletId={} oradianAccountId={} upstreamStatus={} reason={}",
                    wallet.getId(), wallet.getOradianAccountId(),
                    e.getStatusCode(), e.getMessage());
            return false;
        } catch (RuntimeException e) {
            lookupFailures.increment();
            log.warn("Balance audit lookup errored walletId={} oradianAccountId={} cause={}",
                    wallet.getId(), wallet.getOradianAccountId(), e.toString());
            return false;
        }
        if (snapshot == null || snapshot.balance() == null) {
            lookupFailures.increment();
            log.warn("Balance audit got empty snapshot walletId={} oradianAccountId={}",
                    wallet.getId(), wallet.getOradianAccountId());
            return false;
        }
        BigDecimal oradianBalance;
        try {
            oradianBalance = new BigDecimal(snapshot.balance());
        } catch (NumberFormatException e) {
            lookupFailures.increment();
            log.warn("Balance audit got unparseable balance walletId={} oradianAccountId={} balance={}",
                    wallet.getId(), wallet.getOradianAccountId(), snapshot.balance());
            return false;
        }
        BigDecimal localBalance = wallet.getBalance();
        BigDecimal diff = localBalance.subtract(oradianBalance);
        if (diff.abs().compareTo(tolerance) <= 0) {
            return false;
        }
        if (diff.signum() > 0) {
            driftLocalHigh.increment();
            log.warn("BALANCE_DRIFT local_high walletId={} oradianAccountId={} local={} oradian={} delta={}",
                    wallet.getId(), wallet.getOradianAccountId(),
                    localBalance.toPlainString(),
                    oradianBalance.toPlainString(),
                    diff.toPlainString());
        } else {
            driftLocalLow.increment();
            log.warn("BALANCE_DRIFT local_low walletId={} oradianAccountId={} local={} oradian={} delta={}",
                    wallet.getId(), wallet.getOradianAccountId(),
                    localBalance.toPlainString(),
                    oradianBalance.toPlainString(),
                    diff.toPlainString());
        }
        return true;
    }
}
