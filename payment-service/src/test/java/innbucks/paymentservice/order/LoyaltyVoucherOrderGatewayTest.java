package innbucks.paymentservice.order;

import innbucks.paymentservice.client.LoyaltyVoucherOrderClient;
import innbucks.paymentservice.client.LoyaltyVoucherOrderClient.VoucherOrderException;
import innbucks.paymentservice.client.LoyaltyVoucherOrderClient.VoucherOrderView;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit contract of {@link LoyaltyVoucherOrderGateway} over a mocked client —
 * the pieces the WireMock contract test doesn't already pin end-to-end: the
 * major→minor conversion is EXACT (sub-cent refuses, never rounds), the hold
 * window matches the fleet's safety maths, and unreachability maps to a
 * retryable 503 rather than a refusal.
 */
class LoyaltyVoucherOrderGatewayTest {

    private static final String REF = "VCH-4F9A1C22B7D3";

    private LoyaltyVoucherOrderClient client;
    private LoyaltyVoucherOrderGateway gateway;

    @BeforeEach
    void setUp() {
        client = mock(LoyaltyVoucherOrderClient.class);
        gateway = new LoyaltyVoucherOrderGateway(client);
    }

    private static VoucherOrderView view(BigDecimal amount, boolean payable) {
        return new VoucherOrderView(REF, payable ? "PENDING_PAYMENT" : "EXPIRED",
                amount, "USD", "+263782608767",
                Instant.now().plus(Duration.ofMinutes(20)), payable);
    }

    @Test
    void fetch_convertsMajorUnitsToCents_exactly() {
        when(client.getOrder(REF)).thenReturn(view(new BigDecimal("5.0000"), true));

        OrderSnapshot snapshot = gateway.fetch(REF);

        assertThat(snapshot.amountCents()).isEqualTo(500L);
        assertThat(snapshot.settlementTag()).isEqualTo("VCH");
        assertThat(snapshot.narration()).isEqualTo("Gift voucher 22B7D3");
        assertThat(snapshot.payable()).isTrue();
    }

    @Test
    void fetch_subCentAmount_isRefusedNotRounded() {
        // Loyalty refuses sub-cent values at order creation, so this is
        // defence in depth — but a mismatch must refuse, never round: a
        // ledger may not charge an amount that differs from the order.
        when(client.getOrder(REF)).thenReturn(view(new BigDecimal("5.005"), true));

        assertThatThrownBy(() -> gateway.fetch(REF))
                .isInstanceOfSatisfying(InvalidPaymentRequestException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(422));
    }

    @Test
    void fetch_missingOrNonPositiveAmount_isRefused() {
        when(client.getOrder(REF)).thenReturn(view(null, true));
        assertThatThrownBy(() -> gateway.fetch(REF))
                .isInstanceOfSatisfying(InvalidPaymentRequestException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(422));
    }

    @Test
    void fetch_unreachable_mapsToRetryable503() {
        when(client.getOrder(REF)).thenThrow(
                new VoucherOrderException("boom", 503, "LOYALTY_UNREACHABLE"));

        assertThatThrownBy(() -> gateway.fetch(REF))
                .isInstanceOfSatisfying(InvalidPaymentRequestException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(503));
    }

    @Test
    void extendHold_sendsTheRailsPayableDeadlinePlusTheFleetSafetyMargin() {
        // Same maths as the booking and marketplace gateways: the rail's last
        // payable instant + 3m. Loyalty refuses cash until this window ends,
        // so the card rail's 33 (not a code-sized 13) is what stops a till
        // taking cash while the customer's card checkout is still payable.
        gateway.extendHold(REF, Instant.now().plus(Duration.ofMinutes(10)));
        verify(client).extendExpiry(eq(REF), eq(13));
        gateway.extendHold(REF, Instant.now().plus(Duration.ofMinutes(7)));
        verify(client).extendExpiry(eq(REF), eq(10));
        gateway.extendHold(REF, Instant.now().plus(Duration.ofMinutes(30)));
        verify(client).extendExpiry(eq(REF), eq(33));
    }

    @Test
    void holdMinutes_roundsUp_andIsClampedToLoyaltysContract() {
        Instant now = Instant.parse("2026-10-13T10:00:00Z");
        assertThat(LoyaltyVoucherOrderGateway.holdMinutes(
                now.plus(Duration.ofMinutes(10).plusMillis(1)), now)).isEqualTo(14);
        assertThat(LoyaltyVoucherOrderGateway.holdMinutes(now.plus(Duration.ofHours(3)), now)).isEqualTo(60);
        assertThat(LoyaltyVoucherOrderGateway.holdMinutes(now.minus(Duration.ofHours(1)), now)).isEqualTo(1);
    }

    @Test
    void extendHold_unreachable_mapsToRetryable503_notARefusal() {
        doThrow(new VoucherOrderException("down", 503, "LOYALTY_UNREACHABLE"))
                .when(client).extendExpiry(eq(REF), anyInt());

        assertThatThrownBy(() -> gateway.extendHold(REF, Instant.now().plus(Duration.ofMinutes(10))))
                .isInstanceOfSatisfying(InvalidPaymentRequestException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(503));
    }

    @Test
    void confirm_5xx_isUnreachable_neverRejected() {
        // Loyalty issues the voucher IN the confirm transaction — a 500 can
        // mean the issue rolled back, so the retry sweep must get another go.
        doThrow(new VoucherOrderException("issue failed", 500, null))
                .when(client).confirmPayment(REF, "TKZ-VCH-ABC", 500L, null);

        ConfirmOutcome outcome = gateway.confirm(REF, "TKZ-VCH-ABC", 500L);

        assertThat(outcome.result()).isEqualTo(ConfirmOutcome.Result.UNREACHABLE);
    }
}
