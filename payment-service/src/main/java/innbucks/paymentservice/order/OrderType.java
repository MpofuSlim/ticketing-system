package innbucks.paymentservice.order;

/**
 * The product a payment collects money for. Every {@code payment} ledger row
 * carries one, and the {@link OrderGatewayRegistry} selects the matching
 * {@link OrderGateway} by it — nothing downstream of the registry may branch
 * on the product (no {@code instanceof}, no string-matching on refs).
 *
 * <ul>
 *   <li>{@link #BOOKING} — a ticket booking (booking-service). The order ref
 *       is the booking UUID in canonical text form; {@code payment.booking_id}
 *       stays populated for these rows (legacy response echo / back-compat).</li>
 *   <li>{@link #MARKETPLACE} — a marketplace order (marketplace-service). The
 *       order ref is the opaque {@code MKT-...} order reference from the
 *       marketplace's internal S2S surface.</li>
 * </ul>
 */
public enum OrderType {
    BOOKING,
    MARKETPLACE
}
