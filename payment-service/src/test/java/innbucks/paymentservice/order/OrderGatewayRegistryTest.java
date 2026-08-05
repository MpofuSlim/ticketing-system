package innbucks.paymentservice.order;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the registry's selection contract: every product resolves to exactly
 * one gateway by {@link OrderType}; a missing or duplicated registration
 * fails LOUDLY (at boot for duplicates, at first use for a product no
 * adapter serves) — never a silent wrong-product call.
 */
class OrderGatewayRegistryTest {

    private static OrderGateway gateway(OrderType type) {
        OrderGateway g = mock(OrderGateway.class);
        when(g.type()).thenReturn(type);
        return g;
    }

    @Test
    void selectsTheGatewayByOrderType() {
        OrderGateway booking = gateway(OrderType.BOOKING);
        OrderGateway marketplace = gateway(OrderType.MARKETPLACE);
        OrderGatewayRegistry registry = new OrderGatewayRegistry(List.of(booking, marketplace));

        assertSame(booking, registry.forType(OrderType.BOOKING));
        assertSame(marketplace, registry.forType(OrderType.MARKETPLACE));
    }

    @Test
    void missingGateway_failsLoudly_neverReturnsNull() {
        OrderGatewayRegistry registry = new OrderGatewayRegistry(List.of(gateway(OrderType.BOOKING)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.forType(OrderType.MARKETPLACE));
        assertTrue(ex.getMessage().contains("MARKETPLACE"));
    }

    @Test
    void nullType_isRefused() {
        OrderGatewayRegistry registry = new OrderGatewayRegistry(List.of(gateway(OrderType.BOOKING)));

        assertThrows(NullPointerException.class, () -> registry.forType(null));
    }

    @Test
    void duplicateRegistrations_failAtConstruction() {
        // Two beans claiming one product would make gateway selection
        // context-order dependent — refuse at boot instead.
        assertThrows(IllegalStateException.class, () -> new OrderGatewayRegistry(
                List.of(gateway(OrderType.BOOKING), gateway(OrderType.BOOKING))));
    }
}
