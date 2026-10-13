package innbucks.paymentservice.order;

import java.time.Duration;
import java.time.Instant;

/**
 * Per-product adapter between the InnBucks 2D-code checkout rail and the
 * service that owns the order (booking-service, marketplace-service, ...).
 * The payment orchestration ({@code InnbucksPaymentService}, the reconciler,
 * the resolution service) talks ONLY to this interface, selected via
 * {@link OrderGatewayRegistry} by the row's {@link OrderType} — product
 * knowledge (URLs, field names, unit conversion, wording) lives entirely in
 * the adapters.
 *
 * <p>Contract notes:
 * <ul>
 *   <li>{@link #fetch} resolves the order into a product-neutral
 *       {@link OrderSnapshot} with the amount ALREADY in cents — each adapter
 *       is the single major↔minor conversion point for its product.</li>
 *   <li>{@link #extendHold} makes the product-side hold (seat hold / stock
 *       hold) provably outlive the payment instrument the customer is about
 *       to be given: the rail's last payable instant + {@link #HOLD_SAFETY_MARGIN}.
 *       Each rail computes that instant ONCE, before the call, and persists
 *       the deadline it is derived from, so the hold and the ledger can never
 *       disagree about how long the instrument lives. A refusal means the
 *       order is dead and the adapter throws
 *       {@code InnbucksPaymentService.InvalidPaymentRequestException} BEFORE
 *       any money moves.</li>
 *   <li>{@link #confirm} marks the order paid after the customer approved the
 *       code. It must be safe to retry (product services confirm
 *       idempotently) and NEVER throws for expected upstream outcomes — it
 *       answers with a {@link ConfirmOutcome} so the caller parks
 *       {@code COMPLETED_UNCONFIRMED} only when genuinely unconfirmed.</li>
 * </ul>
 */
public interface OrderGateway {

    /**
     * Extra slack the product-side hold must have past the instrument's last
     * payable instant: time for the poller to see a payment made at that
     * instant and confirm the order (a poll interval, the confirm round trip,
     * clock skew). Guarantees a code paid at second 599 of its 10-minute
     * life, or a card paid in the last second of its checkout, still
     * confirms against a LIVE hold. Per rail with the default TTLs: code
     * 10 + 3 = 13 minutes, EcoCash 5 + 2 + 3 = 10, card 28 + 2 + 3 = 33.
     */
    Duration HOLD_SAFETY_MARGIN = Duration.ofMinutes(3);

    /** The product this gateway serves — the registry's selection key. */
    OrderType type();

    /**
     * Resolve the order into a product-neutral snapshot (amount in CENTS,
     * payer contact, settlement tag, narration, payable flag).
     */
    OrderSnapshot fetch(String orderRef);

    /**
     * Extend the product-side hold to at least {@code payableUntil} +
     * {@link #HOLD_SAFETY_MARGIN}, so it outlives the instrument about to be
     * minted. Called BEFORE any ledger write or mint; a refusal throws with a
     * customer-facing message and zero money moved — the caller never mints
     * after a refusal, and an adapter never shortens the hold and reports
     * success.
     *
     * @param payableUntil the last instant the instrument can still be paid:
     *                     code TTL for an InnBucks code, prompt TTL + the
     *                     resolver's grace for EcoCash, checkout TTL + the
     *                     resolver's grace (the gateway's 30-minute ceiling)
     *                     for a card checkout
     */
    void extendHold(String orderRef, Instant payableUntil);

    /**
     * Confirm the order as paid. {@code confirmationRef} is our stable
     * payment reference (the product side's idempotency handle);
     * {@code amountCents} is the amount actually collected, cross-checked by
     * the product service (the 100x guard on the confirm leg).
     */
    ConfirmOutcome confirm(String orderRef, String confirmationRef, long amountCents);

    /**
     * {@link #confirm(String, String, long)} carrying the rail the money came
     * in on, for a product that records it (loyalty's voucher report filters
     * by payment type). The default ignores it, so a gateway that has no use
     * for the rail need not change.
     */
    default ConfirmOutcome confirm(String orderRef, String confirmationRef, long amountCents,
                                   innbucks.paymentservice.entity.PaymentRail rail) {
        return confirm(orderRef, confirmationRef, amountCents);
    }
}
