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
 * window the booking path uses: the rail's last payable instant + the
 * safety margin.
 */
class MarketplaceOrderGatewayTest {

    private static final String REF = "MKT-4F9A1C22B7D3";

    private MarketplaceOrderClient client;
    private MarketplaceOrderGateway gateway;

    @BeforeEach
    void setUp() {
        client = mock(MarketplaceOrderClient.class);
        gateway = new MarketplaceOrderGateway(client);
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
    void extendHold_sendsTheRailsPayableDeadlinePlusTheSafetyMargin() {
        // The booking path's window, per rail: last payable instant + 3m.
        // Code: 10m TTL -> 13.
        gateway.extendHold(REF, Instant.now().plus(Duration.ofMinutes(10)));
        verify(client).extendExpiry(REF, 13);
        // Card: the gateway's 30-minute ceiling (28m TTL + 2m grace) -> 33.
        gateway.extendHold(REF, Instant.now().plus(Duration.ofMinutes(30)));
        verify(client).extendExpiry(REF, 33);
    }

    @Test
    void holdMinutes_perRail_roundedUp_andClampedToTheMarketplaceContract() {
        Instant now = Instant.parse("2026-10-13T10:00:00Z");
        // code 10m, EcoCash 5m prompt + 2m grace, card 28m checkout + 2m grace
        assertEquals(13, MarketplaceOrderGateway.holdMinutes(now.plus(Duration.ofMinutes(10)), now));
        assertEquals(10, MarketplaceOrderGateway.holdMinutes(now.plus(Duration.ofMinutes(7)), now));
        assertEquals(33, MarketplaceOrderGateway.holdMinutes(now.plus(Duration.ofMinutes(30)), now));

        // A window that isn't whole minutes rounds UP — even by a
        // millisecond — so the hold never undershoots the instrument.
        assertEquals(13, MarketplaceOrderGateway.holdMinutes(
                now.plus(Duration.ofMinutes(9).plusSeconds(30)), now));
        assertEquals(14, MarketplaceOrderGateway.holdMinutes(
                now.plus(Duration.ofMinutes(10).plusMillis(1)), now));

        // Clamped to the marketplace's 1..60 extend-expiry contract.
        assertEquals(60, MarketplaceOrderGateway.holdMinutes(now.plus(Duration.ofHours(3)), now));
        assertEquals(1, MarketplaceOrderGateway.holdMinutes(now.minus(Duration.ofHours(1)), now));
    }

    @Test
    void extendHold_refusal_mapsTo409() {
        doThrow(new MarketplaceOrderException("Order MKT is not awaiting payment", 409, "order_not_extendable"))
                .when(client).extendExpiry(eq(REF), anyInt());

        assertEquals(409, assertThrows(InvalidPaymentRequestException.class,
                () -> gateway.extendHold(REF, Instant.now().plus(Duration.ofMinutes(10)))).getStatusCode());
    }

    @Test
    void extendHold_unreachable_mapsTo503() {
        doThrow(new MarketplaceOrderException("down", 503, "marketplace_unreachable"))
                .when(client).extendExpiry(eq(REF), anyInt());

        assertEquals(503, assertThrows(InvalidPaymentRequestException.class,
                () -> gateway.extendHold(REF, Instant.now().plus(Duration.ofMinutes(10)))).getStatusCode());
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
