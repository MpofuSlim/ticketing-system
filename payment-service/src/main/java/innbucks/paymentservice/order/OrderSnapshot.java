package innbucks.paymentservice.order;

/**
 * Product-neutral view of an order at checkout time, produced by an
 * {@link OrderGateway}. Everything the InnBucks 2D-code rail needs to mint a
 * payment code, with all product-specific lookups (amount source, settlement
 * metadata, payer contact) already resolved by the adapter.
 *
 * <p><b>Amounts are ALWAYS minor units (cents) here.</b> Each gateway adapter
 * is the single major↔minor conversion point for its product: booking totals
 * are decimal dollars and convert in {@link BookingOrderGateway#toCents};
 * marketplace totals are already cents on the wire and pass through
 * unconverted.
 *
 * @param orderRef      the product-side reference this snapshot describes
 *                      (booking UUID text / {@code MKT-...} order ref)
 * @param amountCents   amount to collect, in minor units — never converted
 *                      again downstream
 * @param currency      ISO 4217 code; may be null/blank when the product has
 *                      no currency column (the caller falls back to the cell
 *                      currency)
 * @param payerMsisdn   the payer's phone captured at order creation; may be
 *                      null when the product row carries none
 * @param settlementTag statement-grouping tag for the InnBucks reference
 *                      ({@code TKZ-<TAG>-<unique>}): the event settlement code
 *                      (or event-id tag) for bookings, {@code MKT} for
 *                      marketplace orders; null degrades to the legacy
 *                      {@code TKT-PMT-<uuid>} shape
 * @param narration     human-readable statement narration for code generation
 * @param payable       whether the order is currently awaiting payment; a
 *                      non-payable order is refused before any ledger write
 *                      or upstream call
 */
public record OrderSnapshot(
        String orderRef,
        long amountCents,
        String currency,
        String payerMsisdn,
        String settlementTag,
        String narration,
        boolean payable) {
}
