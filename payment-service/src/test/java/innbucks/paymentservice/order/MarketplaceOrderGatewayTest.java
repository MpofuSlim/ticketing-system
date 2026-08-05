package innbucks.paymentservice.order;

import innbucks.paymentservice.client.MarketplaceOrderClient;
import innbucks.paymentservice.client.MarketplaceOrderClient.MarketplaceOrderException;
import innbucks.paymentservice.client.MarketplaceOrderClient.OrderView;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pins the marketplace adapter: cents pass through UNCONVERTED (the
 * marketplace is minor-units native — this gateway's conversion is the
 * identity), payable = PENDING_PAYMENT only, the fixed MKT settlement tag +
 * last-6 narration, and the extend-hold minutes derived from the SAME
 * TTL + safety-margin window the booking path uses.
 */
class MarketplaceOrderGatewayTest {

    private static final String REF = "MKT-4F9A1C22B7D3";

    private MarketplaceOrderClient client;
    private MarketplaceOrderGateway gateway;

    @BeforeEach
    void setUp() {
        client = mock(MarketplaceOrderClient.class);
        gateway = new MarketplaceOrderGateway(client, Duration.ofMinutes(10));
    }

    private static OrderView order(String status, long totalCents) {
        return new OrderView(REF, status, totalCents, "USD", "+263771234567",
                Instant.parse("2026-08-05T10:45:00Z"));
    }

    @Test
    void fetch_passesCentsThroughUnconverted() {
        when(client.getOrder(REF)).thenReturn(order("PENDING_PAYMENT", 3550L));

        OrderSnapshot snapshot = gateway.fetch(REF);

        assertEquals(3550L, snapshot.amountCents(), "marketplace totals are cents natively — no conversion");
        assertEquals("USD", snapshot.currency());
        assertEquals("+263771234567", snapshot.payerMsisdn());
        assertTrue(snapshot.payable());
        assertEquals("MKT", snapshot.settlementTag());
        assertEquals("Marketplace order 22B7D3", snapshot.narration(),
                "narration carries the last 6 of the ref");
    }

    @Test
    void fetch_nonPendingOrder_isNotPayable() {
        when(client.getOrder(REF)).thenReturn(order("PAID", 3550L));
        assertFalse(gateway.fetch(REF).payable());

        when(client.getOrder(REF)).thenReturn(order("EXPIRED", 3550L));
        assertFalse(gateway.fetch(REF).payable());
    }

    @Test
    void fetch_404_mapsToNotFound() {
        when(client.getOrder(REF)).thenThrow(
                new MarketplaceOrderException("Order not found", 404, "order_not_found"));

        assertEquals(404, assertThrows(InvalidPaymentRequestException.class,
                () -> gateway.fetch(REF)).getStatusCode());
    }

    @Test
    void fetch_unreachable_mapsTo503() {
        when(client.getOrder(REF)).thenThrow(
                new MarketplaceOrderException("down", 503, "marketplace_unreachable"));

        assertEquals(503, assertThrows(InvalidPaymentRequestException.class,
                () -> gateway.fetch(REF)).getStatusCode());
    }

    @Test
    void extendHold_usesTheBookingPathsSafetyWindow_roundedUpToMinutes() {
        // ttl 10m + margin 3m = 13 minutes, same window the booking hold gets.
        gateway.extendHold(REF);
        verify(client).extendExpiry(REF, 13);

        // A ttl that isn't whole minutes rounds UP so the hold never
        // undershoots the code.
        MarketplaceOrderGateway odd = new MarketplaceOrderGateway(
                client, Duration.ofMinutes(9).plusSeconds(30));
        assertEquals(13, odd.holdMinutes());

        // Clamped to the marketplace's 1..60 extend-expiry contract.
        MarketplaceOrderGateway huge = new MarketplaceOrderGateway(client, Duration.ofHours(3));
        assertEquals(60, huge.holdMinutes());
    }

    @Test
    void extendHold_refusal_mapsTo409() {
        doThrow(new MarketplaceOrderException("Order MKT is not awaiting payment", 409, "order_not_extendable"))
                .when(client).extendExpiry(eq(REF), anyInt());

        assertEquals(409, assertThrows(InvalidPaymentRequestException.class,
                () -> gateway.extendHold(REF)).getStatusCode());
    }

    @Test
    void extendHold_unreachable_mapsTo503() {
        doThrow(new MarketplaceOrderException("down", 503, "marketplace_unreachable"))
                .when(client).extendExpiry(eq(REF), anyInt());

        assertEquals(503, assertThrows(InvalidPaymentRequestException.class,
                () -> gateway.extendHold(REF)).getStatusCode());
    }

    @Test
    void confirm_200_isConfirmed_withTheOrderRefAsTheHandle() {
        ConfirmOutcome outcome = gateway.confirm(REF, "TKZ-MKT-4F3A2B1C0D9E", 3550L);

        assertTrue(outcome.succeeded());
        assertEquals(REF, outcome.confirmationNumber(),
                "the marketplace has no confirmation-number concept — the ref is the handle");
        verify(client).confirmPayment(REF, "TKZ-MKT-4F3A2B1C0D9E", 3550L);
    }
}
