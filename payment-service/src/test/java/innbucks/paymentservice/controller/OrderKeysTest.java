package innbucks.paymentservice.controller;

import innbucks.paymentservice.exception.BadRequestException;
import innbucks.paymentservice.order.OrderType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the shared request grammar: EXACTLY one of bookingId /
 * (orderType + orderRef); bookingId implies BOOKING. Both public and JWT
 * payment entries resolve through this, so the validation can never drift.
 */
class OrderKeysTest {

    private final UUID bookingId = UUID.randomUUID();

    @Test
    void bookingId_alone_impliesBooking_withCanonicalRef() {
        OrderKeys.OrderKey key = OrderKeys.resolve(bookingId, null, null);

        assertEquals(OrderType.BOOKING, key.type());
        assertEquals(bookingId.toString(), key.ref());
    }

    @Test
    void orderTypePlusRef_alone_isAccepted() {
        OrderKeys.OrderKey key = OrderKeys.resolve(null, OrderType.MARKETPLACE, " MKT-4F9A1C22B7D3 ");

        assertEquals(OrderType.MARKETPLACE, key.type());
        assertEquals("MKT-4F9A1C22B7D3", key.ref(), "ref is trimmed");
    }

    @Test
    void bookingOrderType_withUuidRef_canonicalizesTheUuid() {
        OrderKeys.OrderKey key = OrderKeys.resolve(null, OrderType.BOOKING,
                bookingId.toString().toUpperCase());

        assertEquals(OrderType.BOOKING, key.type());
        assertEquals(bookingId.toString(), key.ref(),
                "BOOKING refs canonicalize through UUID so replay lookups match rows from either request shape");
    }

    @Test
    void bookingOrderType_withNonUuidRef_isRefused() {
        assertThrows(BadRequestException.class,
                () -> OrderKeys.resolve(null, OrderType.BOOKING, "not-a-uuid"));
    }

    @Test
    void neitherShape_isRefused() {
        assertThrows(BadRequestException.class, () -> OrderKeys.resolve(null, null, null));
        assertThrows(BadRequestException.class, () -> OrderKeys.resolve(null, null, "   "));
    }

    @Test
    void bothShapes_areRefused() {
        assertThrows(BadRequestException.class,
                () -> OrderKeys.resolve(bookingId, OrderType.MARKETPLACE, "MKT-1"));
        assertThrows(BadRequestException.class,
                () -> OrderKeys.resolve(bookingId, OrderType.BOOKING, null),
                "even a redundant orderType alongside bookingId is refused — exactly one shape");
        assertThrows(BadRequestException.class,
                () -> OrderKeys.resolve(bookingId, null, "MKT-1"));
    }

    @Test
    void halfAPair_isRefused() {
        assertThrows(BadRequestException.class,
                () -> OrderKeys.resolve(null, OrderType.MARKETPLACE, null));
        assertThrows(BadRequestException.class,
                () -> OrderKeys.resolve(null, OrderType.MARKETPLACE, " "));
        assertThrows(BadRequestException.class,
                () -> OrderKeys.resolve(null, null, "MKT-1"));
    }

    @Test
    void overlongRef_isRefused() {
        assertThrows(BadRequestException.class,
                () -> OrderKeys.resolve(null, OrderType.MARKETPLACE, "M".repeat(65)));
    }
}
