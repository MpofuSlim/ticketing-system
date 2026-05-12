package com.innbucks.loyaltyservice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Business-level metrics for the loyalty domain. Spring Boot Actuator already
 * surfaces infrastructure metrics (HTTP latency per endpoint, JVM, Hikari,
 * Tomcat) — these counters add the loyalty-specific signals: how many points
 * moved, how many vouchers issued and redeemed, where fraud was rejected.
 *
 * <p>All names use the {@code loyalty.} prefix so they're easy to dashboard
 * and alert on separately from the Spring defaults. Available at
 * {@code /actuator/prometheus} in the standard exposition format.
 *
 * <p>The MeterRegistry is auto-wired by Spring Boot (a PrometheusMeterRegistry
 * because {@code management.endpoints.web.exposure.include} has {@code prometheus}).
 * Counter increments are nanosecond-scale atomic operations — they can be
 * dropped on hot paths without measurable cost.
 */
@Component
public class LoyaltyMetrics {

    private final MeterRegistry registry;

    private final Counter vouchersIssued;
    private final Counter vouchersRedeemed;
    private final Counter pendingPromoted;
    private final Timer redemptionLatency;

    public LoyaltyMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.vouchersIssued = Counter.builder("loyalty.voucher.issued")
                .description("Total vouchers issued (single + bulk)")
                .register(registry);
        this.vouchersRedeemed = Counter.builder("loyalty.voucher.redeemed")
                .description("Total successful voucher redemptions")
                .register(registry);
        this.pendingPromoted = Counter.builder("loyalty.user.promoted")
                .description("Total PENDING LoyaltyUsers flipped to ACTIVE by the registration webhook")
                .register(registry);
        // Percentile histogram lets dashboards graph p50/p95/p99 for the
        // single most performance-sensitive call in the service.
        this.redemptionLatency = Timer.builder("loyalty.voucher.redeem.latency")
                .description("End-to-end voucher redemption latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void incVouchersIssued() {
        vouchersIssued.increment();
    }

    public void incVouchersIssued(long count) {
        if (count > 0) vouchersIssued.increment(count);
    }

    public void incVouchersRedeemed() {
        vouchersRedeemed.increment();
    }

    public void incPendingPromoted(int count) {
        if (count > 0) pendingPromoted.increment(count);
    }

    /**
     * Counter for transactions grouped by type. We resolve the meter lazily
     * (one tag value per TransactionType) so callers don't need to know which
     * tag values exist; new types added later are surfaced automatically.
     */
    public void incTransactionPosted(String type) {
        Counter.builder("loyalty.transaction.posted")
                .description("Transactions posted to the ledger, grouped by type")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    /**
     * Counter for fraud rejections grouped by reason. Spike alerts on this
     * (e.g. rate(loyalty_fraud_rejected_total{reason="BAD_SIGNATURE"}[5m]) > 1)
     * are the cheapest possible early-warning for attacks.
     */
    public void incFraudRejected(String reason) {
        Counter.builder("loyalty.fraud.rejected")
                .description("Fraud attempts rejected, grouped by reason")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /** Wraps a redemption call so the latency series captures real end-to-end. */
    public Timer redemptionLatency() {
        return redemptionLatency;
    }
}
