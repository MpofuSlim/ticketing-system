package innbucks.paymentservice.service;

import innbucks.paymentservice.client.CardPaymentStatus;
import innbucks.paymentservice.client.ZimswitchApiTransientException;
import innbucks.paymentservice.client.ZimswitchCopyPayClient;
import innbucks.paymentservice.client.ZimswitchProperties;
import innbucks.paymentservice.client.ZimswitchResultCode;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.PaymentRail;
import innbucks.paymentservice.order.ConfirmOutcome;
import innbucks.paymentservice.order.OrderGatewayRegistry;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import innbucks.paymentservice.service.ZimswitchCardPaymentService.CardCheckOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Money rules of the card rail's resolver — the parts whose failure modes
 * are double charges or goods-against-disputed-money, each pinned as its own
 * case (rationale in docs/api/zimswitch-copyandpay.md):
 *
 * <ul>
 *   <li>a paid read persists the money fact BEFORE the order confirm (the
 *       one-shot status read makes confirm-first a data-loss window);</li>
 *   <li>an echo mismatch on a paid read parks IN_DOUBT and never confirms;</li>
 *   <li>declines/NOT_FOUND-before-deadline leave the checkout open;
 *       NOT_FOUND past the ceiling positively expires it;</li>
 *   <li>the persisted stamp keeps reads inside the 2/min throttle, and is
 *       written even when the read then fails.</li>
 * </ul>
 */
class ZimswitchCardPaymentServiceTest {

    private final PaymentRecordService records = mock(PaymentRecordService.class);
    private final ZimswitchCopyPayClient client = mock(ZimswitchCopyPayClient.class);
    private final ZimswitchProperties properties = new ZimswitchProperties();
    private final CodePaymentResolutionService resolution = mock(CodePaymentResolutionService.class);
    private final PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());

    private final ZimswitchCardPaymentService service = new ZimswitchCardPaymentService(
            records, client, properties, mock(OrderGatewayRegistry.class), resolution, metrics);

    private static final Duration NO_GAP = Duration.ZERO;

    private static Payment openCardRow() {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKZ-PINKRUN26-4F3A2B1C0D9E")
                .paymentRail(PaymentRail.ZIMSWITCH_CARD)
                .status(Payment.PaymentStatus.TOKEN_ISSUED)
                .checkoutId("8a82944a4cc25ebf014cc2c782423202")
                .amount(new BigDecimal("92.00"))
                .currency("USD")
                .createdAt(Instant.now().minus(Duration.ofMinutes(5)))
                .codeExpiresAt(Instant.now().plus(Duration.ofMinutes(23)))
                .build();
    }

    private static CardPaymentStatus status(ZimswitchResultCode outcome, String code,
                                            String amount, String currency, String merchantRef) {
        return new CardPaymentStatus(outcome, "gw-tx-1",
                amount == null ? null : new BigDecimal(amount), currency,
                "VISA", "DB", merchantRef, code, "desc", "ndc-1");
    }

    @Test
    @DisplayName("paid read: money fact (COMPLETED_UNCONFIRMED) is persisted BEFORE the order confirm — the one-shot rule")
    void paidRead_persistsMoneyFactBeforeConfirm() {
        Payment p = openCardRow();
        when(client.getPaymentStatus(p.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.SUCCESS, "000.000.000", "92.00", "USD", p.getPaymentReference()));
        when(resolution.confirmOrder(p)).thenReturn(ConfirmOutcome.confirmed("INN-123"));

        CardCheckOutcome outcome = service.resolveOpenCheckout(p, NO_GAP);

        assertThat(outcome).isEqualTo(CardCheckOutcome.PAID);
        InOrder inOrder = inOrder(records, resolution);
        inOrder.verify(records).stampCardStatusChecked(p.getId());
        inOrder.verify(records).markCompletedUnconfirmed(eq(p.getId()), eq("gw-tx-1"), anyString());
        inOrder.verify(resolution).confirmOrder(p);
        inOrder.verify(records).resolveUnconfirmed(p.getId(), "INN-123");
        verify(records, never()).markInDoubt(any(), anyString());
    }

    @Test
    @DisplayName("paid read + confirm failure: row stays COMPLETED_UNCONFIRMED for the shared retry sweep")
    void paidRead_confirmFailureLeavesUnconfirmed() {
        Payment p = openCardRow();
        when(client.getPaymentStatus(p.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.SUCCESS, "000.000.000", "92.00", "USD", p.getPaymentReference()));
        when(resolution.confirmOrder(p)).thenReturn(ConfirmOutcome.unreachable("booking-service down"));

        assertThat(service.resolveOpenCheckout(p, NO_GAP)).isEqualTo(CardCheckOutcome.PAID);

        verify(records).markCompletedUnconfirmed(eq(p.getId()), eq("gw-tx-1"), anyString());
        verify(records, never()).resolveUnconfirmed(any(), any());
    }

    @Test
    @DisplayName("paid read with amount-echo mismatch: parked IN_DOUBT; the order is NEVER confirmed (100x guard)")
    void paidRead_amountMismatchParksInDoubt() {
        Payment p = openCardRow();
        when(client.getPaymentStatus(p.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.SUCCESS, "000.000.000", "9200.00", "USD", p.getPaymentReference()));

        assertThat(service.resolveOpenCheckout(p, NO_GAP)).isEqualTo(CardCheckOutcome.PENDING);

        verify(records).markInDoubt(eq(p.getId()), contains("amount"));
        verify(records, never()).markCompletedUnconfirmed(any(), any(), any());
        verifyNoInteractions(resolution);
    }

    @Test
    @DisplayName("paid read with foreign merchantTransactionId echo: IN_DOUBT — that money may belong to another order")
    void paidRead_referenceMismatchParksInDoubt() {
        Payment p = openCardRow();
        when(client.getPaymentStatus(p.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.SUCCESS, "000.000.000", "92.00", "USD", "TKT-PMT-someone-else"));

        assertThat(service.resolveOpenCheckout(p, NO_GAP)).isEqualTo(CardCheckOutcome.PENDING);
        verify(records).markInDoubt(eq(p.getId()), contains("merchantTransactionId"));
        verifyNoInteractions(resolution);
    }

    @Test
    @DisplayName("manual-review success: paid path plus a journal note carrying the review flag")
    void paidRead_manualReviewIsPaidAndNoted() {
        Payment p = openCardRow();
        when(client.getPaymentStatus(p.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.SUCCESS_MANUAL_REVIEW, "000.400.000", "92.00", "USD", p.getPaymentReference()));
        when(resolution.confirmOrder(p)).thenReturn(ConfirmOutcome.confirmed("INN-124"));

        assertThat(service.resolveOpenCheckout(p, NO_GAP)).isEqualTo(CardCheckOutcome.PAID);
        verify(records).noteEvent(eq(p.getId()), contains("manual fraud review"));
        verify(records).resolveUnconfirmed(p.getId(), "INN-124");
    }

    @Test
    @DisplayName("decline: journalled, row STAYS open — the shopper may retry another card on the same checkout")
    void decline_keepsCheckoutOpen() {
        Payment p = openCardRow();
        when(client.getPaymentStatus(p.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.REJECTED, "800.100.153", "92.00", "USD", p.getPaymentReference()));

        assertThat(service.resolveOpenCheckout(p, NO_GAP)).isEqualTo(CardCheckOutcome.PENDING);

        verify(records).noteEvent(eq(p.getId()), contains("800.100.153"));
        verify(records, never()).markFailed(any(), any(), any());
        verify(records, never()).markExpired(any(), any());
        verifyNoInteractions(resolution);
    }

    @Test
    @DisplayName("NOT_FOUND before the deadline: normal pre-submission state — row stays, nothing transitions")
    void notFoundBeforeDeadline_staysPending() {
        Payment p = openCardRow(); // deadline 23 minutes away
        when(client.getPaymentStatus(p.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.CHECKOUT_NOT_FOUND, "200.300.404", null, null, null));

        assertThat(service.resolveOpenCheckout(p, NO_GAP)).isEqualTo(CardCheckOutcome.PENDING);
        verify(records, never()).markExpired(any(), any());
    }

    @Test
    @DisplayName("NOT_FOUND past deadline + grace: positive never-paid — row EXPIRED, slot freed")
    void notFoundPastCeiling_expires() {
        Payment p = openCardRow();
        p.setCodeExpiresAt(Instant.now().minus(ZimswitchCardPaymentService.CHECKOUT_EXPIRY_GRACE)
                .minus(Duration.ofSeconds(5)));
        when(client.getPaymentStatus(p.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.CHECKOUT_NOT_FOUND, "200.300.404", null, null, null));

        assertThat(service.resolveOpenCheckout(p, NO_GAP)).isEqualTo(CardCheckOutcome.EXPIRED);
        verify(records).markExpired(eq(p.getId()), anyString());
    }

    @Test
    @DisplayName("NOT_FOUND with a missing local deadline: falls back to the gateway CEILING from creation — never expires early")
    void notFoundWithoutDeadline_neverExpiresBeforeCeiling() {
        Payment p = openCardRow();
        p.setCodeExpiresAt(null); // defensive path: deadline never stamped
        p.setCreatedAt(Instant.now().minus(Duration.ofMinutes(20))); // checkout still alive upstream
        when(client.getPaymentStatus(p.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.CHECKOUT_NOT_FOUND, "200.300.404", null, null, null));

        assertThat(service.resolveOpenCheckout(p, NO_GAP)).isEqualTo(CardCheckOutcome.PENDING);
        verify(records, never()).markExpired(any(), any());
    }

    @Test
    @DisplayName("throttle: a fresh card_status_checked_at stamp skips the read entirely")
    void freshStamp_skipsTheRead() {
        Payment p = openCardRow();
        p.setCardStatusCheckedAt(Instant.now().minusSeconds(5));

        assertThat(service.resolveOpenCheckout(p, Duration.ofSeconds(30)))
                .isEqualTo(CardCheckOutcome.PENDING);

        verifyNoInteractions(client);
        verify(records, never()).stampCardStatusChecked(any());
    }

    @Test
    @DisplayName("the stamp is written BEFORE the read — a crash-loop cannot hammer the gateway")
    void stampPrecedesRead_evenWhenReadFails() {
        Payment p = openCardRow();
        when(client.getPaymentStatus(p.getCheckoutId()))
                .thenThrow(new ZimswitchApiTransientException("down", 503));

        assertThat(service.resolveOpenCheckout(p, NO_GAP)).isEqualTo(CardCheckOutcome.PENDING);

        InOrder inOrder = inOrder(records, client);
        inOrder.verify(records).stampCardStatusChecked(p.getId());
        inOrder.verify(client).getPaymentStatus(p.getCheckoutId());
        verify(records, never()).markExpired(any(), any());
        verify(records, never()).markFailed(any(), any(), any());
    }

    @Test
    @DisplayName("unconfigured rail: refused with 503 BEFORE any slot/ledger/upstream interaction")
    void startCheckout_unconfiguredFailsFast() {
        when(client.canStartCheckout()).thenReturn(false);

        assertThatThrownBy(() -> service.startCheckout(
                innbucks.paymentservice.order.OrderType.BOOKING, UUID.randomUUID().toString()))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .satisfies(e -> assertThat(((InvalidPaymentRequestException) e).getStatusCode()).isEqualTo(503));

        verifyNoInteractions(records);
    }

    @Test
    @DisplayName("HALF-PROVISIONED rail (credentials but no shopperResultUrl): 503 and NO payment slot burned")
    void startCheckout_halfProvisionedNeverClaimsTheSlot() {
        // The exact state a cell is in after credentials land but the FE's
        // result URL has not been set. Before the canStartCheckout() split
        // this sailed through, minted a REAL checkout upstream, and locked
        // the order's only payment slot (both rails) for ~28 minutes.
        when(client.canStartCheckout()).thenReturn(false);
        when(client.isConfigured()).thenReturn(true);

        assertThatThrownBy(() -> service.startCheckout(
                innbucks.paymentservice.order.OrderType.BOOKING, UUID.randomUUID().toString()))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .satisfies(e -> assertThat(((InvalidPaymentRequestException) e).getStatusCode()).isEqualTo(503));

        // No ledger row opened => the order's payment slot is untouched, so
        // the customer can still pay via the InnBucks code rail.
        verifyNoInteractions(records);
        // And critically: no checkout minted upstream.
        verify(client, never()).prepareCheckout(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("half-provisioned rail stays POLLABLE: open checkouts must still resolve")
    void halfProvisionedRail_isStillPollable() {
        // isRailConfigured() gates the reconciler sweep and must track
        // gateway reachability only — otherwise a blank result URL would
        // strand rows whose money may already have moved.
        when(client.isConfigured()).thenReturn(true);

        assertThat(service.isRailConfigured()).isTrue();
    }

    @Test
    @DisplayName("non-TOKEN_ISSUED / no-checkout rows are never queried")
    void nonOpenRows_areNeverQueried() {
        Payment noCheckout = openCardRow();
        noCheckout.setCheckoutId(null);
        assertThat(service.resolveOpenCheckout(noCheckout, NO_GAP)).isEqualTo(CardCheckOutcome.PENDING);

        Payment terminal = openCardRow();
        terminal.setStatus(Payment.PaymentStatus.SUCCEEDED);
        assertThat(service.resolveOpenCheckout(terminal, NO_GAP)).isEqualTo(CardCheckOutcome.PENDING);

        verifyNoInteractions(client);
    }
}
