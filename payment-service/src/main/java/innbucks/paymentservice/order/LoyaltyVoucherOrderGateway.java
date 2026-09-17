package innbucks.paymentservice.order;

import innbucks.paymentservice.client.LoyaltyVoucherOrderClient;
import innbucks.paymentservice.client.LoyaltyVoucherOrderClient.VoucherOrderException;
import innbucks.paymentservice.client.LoyaltyVoucherOrderClient.VoucherOrderView;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * {@link OrderGateway} for voucher purchase orders (InnRewards V47), backed by
 * loyalty-service's internal S2S surface via {@link LoyaltyVoucherOrderClient}:
 * <ul>
 *   <li>{@link #fetch}: {@code GET /loyalty/internal/voucher-orders/{ref}} —
 *       loyalty serves DECIMAL major units, so this gateway owns the
 *       major→minor conversion ({@link #toCents}; exact by construction —
 *       loyalty refuses sub-cent face values at order creation). Payable comes
 *       straight from the order's own flag (PENDING_PAYMENT and not past its
 *       deadline). Settlement tag is the fixed {@code VCH} (references group
 *       as {@code TKZ-VCH-<unique>} on the statement); narration
 *       {@code "Gift voucher <last 6 of ref>"}.</li>
 *   <li>{@link #extendHold}: {@code PATCH .../extend-expiry?minutes=N} with
 *       the same safety window the other products use (code TTL +
 *       {@link #HOLD_SAFETY_MARGIN}, rounded up, clamped to loyalty's 1..60
 *       contract). A refusal means the order lapsed — the console creates a
 *       fresh order with ZERO money moved.</li>
 *   <li>{@link #confirm}: {@code PATCH .../confirm-payment} with
 *       {@code {paymentRef, amountCents}}. A 200 means loyalty marked the
 *       order PAID <b>and issued the voucher</b> (recipient + sender WhatsApp
 *       messages ride loyalty's issue path); idempotent same-ref replays are
 *       the same 200, folded into CONFIRMED. 4xx → REJECTED carrying
 *       loyalty's code ({@code AMOUNT_MISMATCH} / {@code ORDER_ALREADY_PAID}
 *       / {@code ORDER_NOT_CONFIRMABLE} / not-found); connect failure or 5xx
 *       → UNREACHABLE (loyalty issues IN the confirm transaction, so a
 *       failed issue rolls back and the retry sweep lands it later). The
 *       order ref doubles as the confirmation handle.</li>
 * </ul>
 */
@Slf4j
@Component
public class LoyaltyVoucherOrderGateway implements OrderGateway {

    /** Voucher purchases group under one statement tag; per-merchant fee
     *  attribution happens loyalty-side (invoicing), not on the statement. */
    static final String SETTLEMENT_TAG = "VCH";

    private static final int MIN_EXTEND_MINUTES = 1;
    private static final int MAX_EXTEND_MINUTES = 60;

    private final LoyaltyVoucherOrderClient client;
    private final Duration codeTtl;

    public LoyaltyVoucherOrderGateway(
            LoyaltyVoucherOrderClient client,
            @Value("${payments.innbucks.code.ttl:PT10M}") Duration codeTtl) {
        this.client = client;
        this.codeTtl = codeTtl;
    }

    @Override
    public OrderType type() {
        return OrderType.LOYALTY_VOUCHER;
    }

    @Override
    public OrderSnapshot fetch(String orderRef) {
        VoucherOrderView order;
        try {
            order = client.getOrder(orderRef);
        } catch (VoucherOrderException e) {
            if (e.getStatusCode() == 404) {
                throw new InvalidPaymentRequestException("Order not found", 404);
            }
            throw new InvalidPaymentRequestException(
                    "We could not load your order right now; please try again shortly", 503);
        }
        if (order.amount() == null || order.amount().signum() <= 0) {
            throw new InvalidPaymentRequestException(
                    "Voucher order has no positive amount; cannot request payment", 422);
        }
        return new OrderSnapshot(
                orderRef,
                toCents(order.amount()),
                order.currency(),
                order.payerMsisdn(),
                SETTLEMENT_TAG,
                narration(orderRef),
                order.payable());
    }

    @Override
    public void extendHold(String orderRef) {
        try {
            client.extendExpiry(orderRef, holdMinutes());
        } catch (VoucherOrderException e) {
            if (e.getStatusCode() == 404 || e.getStatusCode() == 409 || e.getStatusCode() == 400) {
                log.warn("[voucher-gateway] expiry extension refused orderRef={} status={} code={} — payment refused pre-mint: {}",
                        orderRef, e.getStatusCode(), e.getCode(), e.getMessage());
                throw new InvalidPaymentRequestException(
                        "This voucher order has expired — please create a new order and try again", 409);
            }
            log.warn("[voucher-gateway] expiry extension unreachable orderRef={} status={} — refusing payment: {}",
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
        } catch (VoucherOrderException e) {
            if (e.getStatusCode() >= 400 && e.getStatusCode() < 500) {
                // Definite refusal (AMOUNT_MISMATCH / ORDER_ALREADY_PAID /
                // ORDER_NOT_CONFIRMABLE / not found). The row parks
                // COMPLETED_UNCONFIRMED for the retry sweep + operator queue.
                return ConfirmOutcome.rejected(
                        e.getCode() != null ? e.getCode() + ": " + e.getMessage() : e.getMessage());
            }
            return ConfirmOutcome.unreachable(e.getMessage());
        }
    }

    /**
     * Loyalty serves decimal major units; the Merchant API takes CENTS. Exact
     * by construction — loyalty refuses sub-cent face values at order
     * creation — but a mismatch is still refused rather than rounded.
     */
    static long toCents(BigDecimal amount) {
        try {
            return amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new InvalidPaymentRequestException(
                    "Voucher order amount has sub-cent precision; cannot request payment", 422);
        }
    }

    /** Same safety window as the other products — code TTL +
     *  {@link #HOLD_SAFETY_MARGIN} in whole minutes, rounded UP, clamped to
     *  loyalty's 1..60 contract. */
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
        return "Gift voucher " + tail;
    }
}
