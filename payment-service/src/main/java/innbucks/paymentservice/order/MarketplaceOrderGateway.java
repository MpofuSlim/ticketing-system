package innbucks.paymentservice.order;

import innbucks.paymentservice.client.MarketplaceOrderClient;
import innbucks.paymentservice.client.MarketplaceOrderClient.MarketplaceOrderException;
import innbucks.paymentservice.client.MarketplaceOrderClient.OrderView;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@link OrderGateway} for marketplace orders, backed by marketplace-service's
 * internal S2S surface via {@link MarketplaceOrderClient}:
 * <ul>
 *   <li>{@link #fetch}: {@code GET /marketplace/internal/orders/{ref}} — the
 *       marketplace stores cents natively, so {@code totalCents} passes
 *       through UNCONVERTED (this gateway's major↔minor conversion is the
 *       identity). Payable = status {@code PENDING_PAYMENT}. Settlement tag
 *       is the fixed {@code MKT} (references group as
 *       {@code TKZ-MKT-<unique>} on the merchant statement); narration
 *       {@code "Marketplace order <last 6 of ref>"}.</li>
 *   <li>{@link #extendHold}: {@code PATCH .../extend-expiry?minutes=N} with
 *       the SAME safety window the booking path uses (code TTL +
 *       {@link #HOLD_SAFETY_MARGIN}, rounded up to whole minutes and clamped
 *       to the marketplace's 1..60 contract). A refusal means the order is
 *       dead — the buyer re-orders with ZERO money moved.</li>
 *   <li>{@link #confirm}: {@code PATCH .../confirm-payment} with
 *       {@code {paymentRef: confirmationRef, amountCents}}. 200 → CONFIRMED
 *       (the marketplace answers the same 200 for an idempotent same-ref
 *       replay, so ALREADY_CONFIRMED is folded into it); 409/422/404 →
 *       REJECTED carrying the marketplace's response code (amount_mismatch /
 *       order_not_confirmable / order_already_paid / order_not_found);
 *       connect failure or 5xx → UNREACHABLE. The marketplace has no
 *       confirmation-number concept, so the order ref doubles as the
 *       confirmation handle on success.</li>
 * </ul>
 */
@Slf4j
@Component
public class MarketplaceOrderGateway implements OrderGateway {

    /** Marketplace orders group under one statement tag; per-merchant split
     *  happens marketplace-side, not on the shared InnBucks account. */
    static final String SETTLEMENT_TAG = "MKT";

    private static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";
    private static final int MIN_EXTEND_MINUTES = 1;
    private static final int MAX_EXTEND_MINUTES = 60;

    private final MarketplaceOrderClient client;
    private final Duration codeTtl;

    public MarketplaceOrderGateway(
            MarketplaceOrderClient client,
            @Value("${payments.innbucks.code.ttl:PT10M}") Duration codeTtl) {
        this.client = client;
        this.codeTtl = codeTtl;
    }

    @Override
    public OrderType type() {
        return OrderType.MARKETPLACE;
    }

    @Override
    public OrderSnapshot fetch(String orderRef) {
        OrderView order;
        try {
            order = client.getOrder(orderRef);
        } catch (MarketplaceOrderException e) {
            if (e.getStatusCode() == 404) {
                throw new InvalidPaymentRequestException("Order not found", 404);
            }
            throw new InvalidPaymentRequestException(
                    "We could not load your order right now; please try again shortly", 503);
        }
        return new OrderSnapshot(
                orderRef,
                order.totalCents(),
                order.currency(),
                order.buyerMsisdn(),
                SETTLEMENT_TAG,
                narration(orderRef),
                STATUS_PENDING_PAYMENT.equals(order.status()));
    }

    @Override
    public void extendHold(String orderRef) {
        try {
            client.extendExpiry(orderRef, holdMinutes());
        } catch (MarketplaceOrderException e) {
            if (e.getStatusCode() == 404 || e.getStatusCode() == 409 || e.getStatusCode() == 400) {
                log.warn("[marketplace-gateway] hold extension refused orderRef={} status={} code={} — payment refused pre-mint: {}",
                        orderRef, e.getStatusCode(), e.getCode(), e.getMessage());
                throw new InvalidPaymentRequestException(
                        "Your order has expired — please create a new order and try again", 409);
            }
            log.warn("[marketplace-gateway] hold extension unreachable orderRef={} status={} — refusing payment: {}",
                    orderRef, e.getStatusCode(), e.getMessage());
            throw new InvalidPaymentRequestException(
                    "We could not secure your order right now; please try again shortly", 503);
        }
    }

    @Override
    public ConfirmOutcome confirm(String orderRef, String confirmationRef, long amountCents) {
        try {
            client.confirmPayment(orderRef, confirmationRef, amountCents);
            // 200 covers both a fresh confirm and the idempotent same-ref
            // replay — indistinguishable on the wire, identical to the caller.
            return ConfirmOutcome.confirmed(orderRef);
        } catch (MarketplaceOrderException e) {
            if (e.getStatusCode() == 409 || e.getStatusCode() == 422 || e.getStatusCode() == 404
                    || e.getStatusCode() == 400) {
                // Definite refusal (amount_mismatch / order_not_confirmable /
                // order_already_paid / order_not_found). The row parks
                // COMPLETED_UNCONFIRMED for the retry sweep + operator queue.
                return ConfirmOutcome.rejected(
                        e.getCode() != null ? e.getCode() + ": " + e.getMessage() : e.getMessage());
            }
            return ConfirmOutcome.unreachable(e.getMessage());
        }
    }

    /**
     * The same safety window the booking path grants its seat hold — code TTL
     * + {@link #HOLD_SAFETY_MARGIN} — expressed as the whole minutes the
     * marketplace's extend-expiry contract takes (rounded UP so the hold
     * never undershoots the code), clamped to its 1..60 range.
     */
    int holdMinutes() {
        long seconds = codeTtl.plus(HOLD_SAFETY_MARGIN).getSeconds();
        long minutes = (seconds + 59) / 60;
        return (int) Math.max(MIN_EXTEND_MINUTES, Math.min(MAX_EXTEND_MINUTES, minutes));
    }

    static String narration(String orderRef) {
        String tail = orderRef == null ? "" : orderRef;
        if (tail.length() > 6) {
            tail = tail.substring(tail.length() - 6);
        }
        return "Marketplace order " + tail;
    }
}
