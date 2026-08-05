package innbucks.paymentservice.controller;

import innbucks.paymentservice.exception.BadRequestException;
import innbucks.paymentservice.order.OrderType;

import java.util.UUID;

/**
 * Resolves the order identity a payment request names — shared by the public
 * {@code POST /payments} and the JWT {@code POST /payments/innbucks} so the
 * two entries can never drift on the validation grammar:
 *
 * <p><b>Exactly one of {@code bookingId} / ({@code orderType} +
 * {@code orderRef}).</b> {@code bookingId} implies BOOKING (the historical
 * contract, unchanged for existing FEs); the pair is the additive shape for
 * non-booking products. A BOOKING {@code orderRef} must parse as a booking
 * UUID and is canonicalized through {@link UUID#toString()} so replay lookups
 * match rows written from either request shape.
 */
final class OrderKeys {

    /** Resolved, canonical order identity. */
    record OrderKey(OrderType type, String ref) {
    }

    static final String XOR_MESSAGE =
            "Provide exactly one of bookingId or orderType + orderRef";

    private OrderKeys() {
    }

    static OrderKey resolve(UUID bookingId, OrderType orderType, String orderRef) {
        boolean hasPairPart = orderType != null || (orderRef != null && !orderRef.isBlank());
        if (bookingId != null && hasPairPart) {
            throw new BadRequestException(XOR_MESSAGE + " — not both");
        }
        if (bookingId != null) {
            return new OrderKey(OrderType.BOOKING, bookingId.toString());
        }
        if (orderType == null || orderRef == null || orderRef.isBlank()) {
            throw new BadRequestException(XOR_MESSAGE + " (orderType and orderRef go together)");
        }
        String ref = orderRef.trim();
        if (orderType == OrderType.BOOKING) {
            try {
                return new OrderKey(OrderType.BOOKING, UUID.fromString(ref).toString());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("orderRef must be a booking UUID when orderType is BOOKING");
            }
        }
        if (ref.length() > 64) {
            throw new BadRequestException("orderRef must be at most 64 characters");
        }
        return new OrderKey(orderType, ref);
    }
}
