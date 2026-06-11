package innbucks.paymentservice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Business-level metrics for the payment domain. Spring Boot Actuator already
 * surfaces HTTP latency per endpoint at the controller level; these add the
 * payment-flow-specific signals so dashboards split shop-checkout failures by
 * cause and graph end-to-end duration with percentiles.
 *
 * <p>All names use the {@code payment.} prefix so they're isolated from the
 * loyalty.* series in /actuator/prometheus.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;
    private final Timer shopCheckoutDuration;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Percentiles for the single most user-visible call in payment-service:
        // p95 spikes here mean loyalty-service is degraded, since payment-service
        // is otherwise just orchestration.
        this.shopCheckoutDuration = Timer.builder("payment.shop_checkout.duration")
                .description("End-to-end /payments/shop-checkout latency including the loyalty round-trip")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public Timer shopCheckoutDuration() {
        return shopCheckoutDuration;
    }

    /**
     * Counter for shop-checkout outcomes. Tags so dashboards split:
     *   - outcome={success, validation_failed, loyalty_rejected, loyalty_unavailable}
     *   - mode={cash, points, mixed, unknown}
     * A spike on outcome=loyalty_unavailable is a direct page-able event:
     * loyalty-service is down and customers can't pay.
     */
    public void incShopCheckout(String outcome, String mode) {
        Counter.builder("payment.shop_checkout")
                .description("Shop checkouts handled by payment-service, by outcome and payment mode")
                .tag("outcome", outcome)
                .tag("mode", mode)
                .register(registry)
                .increment();
    }

    /**
     * Counter incremented once per stale-PENDING ledger row the
     * reconciliation job surfaces on each scan. Tag by transaction_type
     * so dashboards / alerts can split TRANSFER vs WITHDRAWAL drift.
     *
     * <p>Alerting target: any non-zero value sustained across two
     * consecutive scrape windows. A single row is operator-investigate;
     * a steady drip is "payment-service is failing to mark PENDING rows
     * SUCCEEDED/FAILED" — likely a DB outage during commit AFTER Oradian
     * already moved the money, i.e. the orphan-in-upstream class of bug
     * the ledger exists to surface.
     */
    public void incStalePendingTransaction(String type) {
        Counter.builder("payment.transactions.stale_pending")
                .description("PENDING ledger rows older than the reconciliation threshold, by transaction type")
                .tag("type", type == null ? "UNKNOWN" : type)
                .register(registry)
                .increment();
    }

    /**
     * Counter incremented once per stale ticket-payment ledger row
     * (PENDING or IN_DOUBT past the reconciliation threshold) per scan.
     * IN_DOUBT is the louder of the two: it means an upstream debit call
     * timed out or returned an unclassifiable outcome and money MAY have
     * moved — any sustained non-zero value is a page.
     */
    public void incStalePayment(String status) {
        Counter.builder("payment.payments.stale")
                .description("Ticket-payment ledger rows stuck in a non-terminal state past the reconciliation threshold, by status")
                .tag("status", status == null ? "UNKNOWN" : status)
                .register(registry)
                .increment();
    }

    /**
     * Outcome of the reconciler's booking-confirm retry for
     * COMPLETED_UNCONFIRMED rows (money moved, booking not confirmed).
     * outcome={resolved, still_failing}. A steady drip of still_failing
     * means customers are debited without tickets — page and read the
     * booking-side rejection reason out of the WARN logs.
     */
    public void incUnconfirmedRetry(String outcome) {
        Counter.builder("payment.payments.unconfirmed_retry")
                .description("Reconciler retries of the booking confirm for money-moved-but-unconfirmed payments, by outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
