package innbucks.paymentservice.service;

import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.order.ConfirmOutcome;
import innbucks.paymentservice.order.OrderGatewayRegistry;
import innbucks.paymentservice.order.OrderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Terminal-state transitions for an open 2D-code payment, shared by the TWO
 * places a code can resolve: the 20s reconciliation poller and the instant
 * check a customer triggers by re-POSTing /payments ("I've paid"). One
 * implementation so the money rules can never drift between the two paths.
 * Order confirmation goes through the row's {@code OrderGateway} (selected by
 * its {@code order_type}) — this class knows nothing product-specific.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodePaymentResolutionService {

    private final PaymentRecordService paymentRecordService;
    private final OrderGatewayRegistry orderGatewayRegistry;
    private final PaymentMetrics metrics;

    /**
     * InnBucks says the customer paid (Paid, or Claimed — the doc defines
     * both as "finalised by the customer"). Confirm the order through its
     * gateway, then promote; anything short of a confirmed order parks the
     * row COMPLETED_UNCONFIRMED with the authNumber as the recovery handle —
     * money HAS moved, and the confirm-retry sweep keeps trying.
     */
    public void completePaid(Payment p, String rawUpstreamStatus) {
        ConfirmOutcome outcome = confirmOrder(p);
        if (outcome.succeeded()) {
            paymentRecordService.markSucceeded(p.getId(), p.getCodeAuthNumber(),
                    outcome.confirmationNumber());
            metrics.incCodeResolution("paid");
            log.info("Code paid + order confirmed paymentReference={} orderType={} orderRef={} confirmation={} upstreamStatus={}",
                    p.getPaymentReference(), orderTypeOf(p), orderRefOf(p),
                    outcome.confirmationNumber(), rawUpstreamStatus);
        } else {
            paymentRecordService.markCompletedUnconfirmed(p.getId(), p.getCodeAuthNumber(),
                    "Customer paid the InnBucks code but the order confirm failed: " + outcome.reason());
            metrics.incCodeResolution("paid_unconfirmed");
            log.error("CODE PAID BUT ORDER CONFIRM FAILED — confirm-retry will keep trying "
                            + "paymentReference={} orderType={} orderRef={} reason={}",
                    p.getPaymentReference(), orderTypeOf(p), orderRefOf(p), outcome.reason());
        }
    }

    /** InnBucks reports the code Expired / Timed Out — never paid; free the slot. */
    public void markExpiredUpstream(Payment p, String rawUpstreamStatus) {
        paymentRecordService.markExpired(p.getId(),
                "InnBucks reports the code " + rawUpstreamStatus + " — never paid");
        metrics.incCodeResolution("expired");
        log.info("Code expired upstream paymentReference={} orderType={} orderRef={} — slot freed",
                p.getPaymentReference(), orderTypeOf(p), orderRefOf(p));
    }

    /**
     * Confirm the payment's order through its product gateway. Never throws:
     * an unexpected exception (gateway bug, missing registration, sub-cent
     * ledger amount) maps to {@link ConfirmOutcome#unreachable}, so the
     * caller parks the paid row rather than crashing the sweep — a paid code
     * must never be guessed into a terminal failure. Shared by
     * {@link #completePaid} and the reconciler's confirm-retry loop so the
     * confirm semantics cannot drift between them.
     */
    public ConfirmOutcome confirmOrder(Payment p) {
        try {
            return orderGatewayRegistry.forType(orderTypeOf(p)).confirm(
                    orderRefOf(p),
                    // Our stable payment reference doubles as the product
                    // side's idempotency handle for the confirm.
                    p.getPaymentReference(),
                    ledgerAmountCents(p));
        } catch (RuntimeException e) {
            log.warn("Order confirm errored paymentReference={} orderType={} orderRef={} cause={}",
                    p.getPaymentReference(), orderTypeOf(p), orderRefOf(p), e.toString());
            return ConfirmOutcome.unreachable(e.getMessage());
        }
    }

    /** Pre-V12 rows (and legacy test fixtures) may carry only bookingId. */
    private static OrderType orderTypeOf(Payment p) {
        return p.getOrderType() != null ? p.getOrderType() : OrderType.BOOKING;
    }

    private static String orderRefOf(Payment p) {
        if (p.getOrderRef() != null) return p.getOrderRef();
        return p.getBookingId() != null ? p.getBookingId().toString() : null;
    }

    /**
     * The ledger stores major units (derived FROM the cents the code was
     * minted for), so this exact reverse conversion can never lose precision.
     */
    private static long ledgerAmountCents(Payment p) {
        return p.getAmount().movePointRight(2).longValueExact();
    }
}
