package innbucks.paymentservice.order;

import java.time.Duration;

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
 *       hold) provably outlive the payment code the customer is about to be
 *       shown (code TTL + {@link #HOLD_SAFETY_MARGIN}); a refusal means the
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
     * Extra slack the product-side hold must have past the payment code's own
     * TTL: one poll interval + the poller's expiry grace + processing margin.
     * Guarantees a code paid at second 599 of its 10-minute life still
     * confirms against a LIVE hold.
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
     * Extend the product-side hold so it outlives the code about to be
     * minted. Called BEFORE any ledger write or code mint; a refusal throws
     * with a customer-facing message and zero money moved.
     */
    void extendHold(String orderRef);

    /**
     * Confirm the order as paid. {@code confirmationRef} is our stable
     * payment reference (the product side's idempotency handle);
     * {@code amountCents} is the amount actually collected, cross-checked by
     * the product service (the 100x guard on the confirm leg).
     */
    ConfirmOutcome confirm(String orderRef, String confirmationRef, long amountCents);
}
