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
import innbucks.paymentservice.service.ZimswitchCardPaymentService.ReadTrigger;
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
import static org.mockito.ArgumentMatchers.argThat;
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
 *   <li>before the checkout's ceiling only the shopper's return reads it —
 *       a read kills a checkout the shopper may still be paying on (staging,
 *       2026-11-12); the poller and a plain re-POST wait for the ceiling;</li>
 *   <li>declines/NOT_FOUND before the ceiling leave the checkout open;
 *       past it NOT_FOUND expires the row and a decline closes it FAILED;</li>
 *   <li>every read is journalled with ZimSwitch's result code and its trigger;</li>
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
    private static final ReadTrigger RETURN = ReadTrigger.SHOPPER_RETURN;

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

        CardCheckOutcome outcome = service.resolveOpenCheckout(p, NO_GAP, RETURN);

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

        assertThat(service.resolveOpenCheckout(p, NO_GAP, RETURN)).isEqualTo(CardCheckOutcome.PAID);

        verify(records).markCompletedUnconfirmed(eq(p.getId()), eq("gw-tx-1"), anyString());
        verify(records, never()).resolveUnconfirmed(any(), any());
    }

    @Test
    @DisplayName("paid read with amount-echo mismatch: parked IN_DOUBT; the order is NEVER confirmed (100x guard)")
    void paidRead_amountMismatchParksInDoubt() {
        Payment p = openCardRow();
        when(client.getPaymentStatus(p.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.SUCCESS, "000.000.000", "9200.00", "USD", p.getPaymentReference()));

        assertThat(service.resolveOpenCheckout(p, NO_GAP, RETURN)).isEqualTo(CardCheckOutcome.PENDING);

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

        assertThat(service.resolveOpenCheckout(p, NO_GAP, RETURN)).isEqualTo(CardCheckOutcome.PENDING);
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

        assertThat(service.resolveOpenCheckout(p, NO_GAP, RETURN)).isEqualTo(CardCheckOutcome.PAID);
        verify(records).noteEvent(eq(p.getId()), contains("manual fraud review"));
        verify(records).resolveUnconfirmed(p.getId(), "INN-124");
    }

    @Test
    @DisplayName("decline: journalled, row STAYS open — the shopper may retry another card on the same checkout")
    void decline_keepsCheckoutOpen() {
        Payment p = openCardRow();
        when(client.getPaymentStatus(p.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.REJECTED, "800.100.153", "92.00", "USD", p.getPaymentReference()));

        assertThat(service.resolveOpenCheckout(p, NO_GAP, RETURN)).isEqualTo(CardCheckOutcome.PENDING);

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

        assertThat(service.resolveOpenCheckout(p, NO_GAP, RETURN)).isEqualTo(CardCheckOutcome.PENDING);
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

        assertThat(service.resolveOpenCheckout(p, NO_GAP, ReadTrigger.POLLER)).isEqualTo(CardCheckOutcome.EXPIRED);
        verify(records).markExpired(eq(p.getId()), contains("200.300.404"));
    }

    @Test
    @DisplayName("NOT_FOUND with a missing local deadline: falls back to the gateway CEILING from creation — never expires early")
    void notFoundWithoutDeadline_neverExpiresBeforeCeiling() {
        Payment p = openCardRow();
        p.setCodeExpiresAt(null); // defensive path: deadline never stamped
        p.setCreatedAt(Instant.now().minus(Duration.ofMinutes(20))); // checkout still alive upstream
        when(client.getPaymentStatus(p.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.CHECKOUT_NOT_FOUND, "200.300.404", null, null, null));

        assertThat(service.resolveOpenCheckout(p, NO_GAP, RETURN)).isEqualTo(CardCheckOutcome.PENDING);
        verify(records, never()).markExpired(any(), any());
    }

    @Test
    @DisplayName("throttle: a fresh card_status_checked_at stamp skips the read entirely")
    void freshStamp_skipsTheRead() {
        Payment p = openCardRow();
        p.setCardStatusCheckedAt(Instant.now().minusSeconds(5));

        assertThat(service.resolveOpenCheckout(p, Duration.ofSeconds(30), RETURN))
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

        assertThat(service.resolveOpenCheckout(p, NO_GAP, RETURN)).isEqualTo(CardCheckOutcome.PENDING);

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
    @DisplayName("an order priced in a currency the merchant account does not settle in is refused before any checkout")
    void startCheckout_foreignCurrencyOrder_isRefusedBeforeTheSlot() {
        // The staging case: a ZAR voucher on the USD cell. Nothing may be
        // opened upstream or in the ledger for money the account cannot take.
        OrderGatewayRegistry registry = mock(OrderGatewayRegistry.class);
        innbucks.paymentservice.order.OrderGateway gateway = mock(innbucks.paymentservice.order.OrderGateway.class);
        when(registry.forType(innbucks.paymentservice.order.OrderType.LOYALTY_VOUCHER)).thenReturn(gateway);
        when(gateway.fetch("VCH-4F9A1C22B7D3")).thenReturn(new innbucks.paymentservice.order.OrderSnapshot(
                "VCH-4F9A1C22B7D3", 10000, "ZAR", "+263771234567", "VCH", "Gift voucher 4F9A1C22B7D3", true));
        ZimswitchCardPaymentService usdCell = new ZimswitchCardPaymentService(
                records, client, properties, registry, resolution, metrics);
        org.springframework.test.util.ReflectionTestUtils.setField(usdCell, "cellCurrency", "USD");
        when(client.canStartCheckout()).thenReturn(true);

        assertThatThrownBy(() -> usdCell.startCheckout(
                innbucks.paymentservice.order.OrderType.LOYALTY_VOUCHER, "VCH-4F9A1C22B7D3"))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasMessage("Card cannot take payment in ZAR — please use another payment method")
                .satisfies(e -> assertThat(((InvalidPaymentRequestException) e).getStatusCode()).isEqualTo(422));

        verify(gateway, never()).extendHold(anyString(), any());
        verify(records, never()).openPending(any());
        verify(client, never()).prepareCheckout(anyString(), anyLong(), anyString());
    }

    /** A healthy card rail over the given registry, for the startCheckout happy path. */
    private ZimswitchCardPaymentService cardServiceOver(OrderGatewayRegistry registry) {
        ZimswitchCardPaymentService s = new ZimswitchCardPaymentService(
                records, client, properties, registry, resolution, metrics);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "cellCurrency", "USD");
        when(client.canStartCheckout()).thenReturn(true);
        when(client.isConfigured()).thenReturn(true);
        when(records.openPending(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            p.setStatus(Payment.PaymentStatus.PENDING);
            return p;
        });
        return s;
    }

    @Test
    @DisplayName("the hold is sized from the checkout's 30-minute ceiling BEFORE the call; the ledger deadline is stamped after the checkout exists")
    void startCheckout_holdCoversTheCeiling_ledgerDeadlineStampedAfterTheCheckoutExists() {
        OrderGatewayRegistry registry = mock(OrderGatewayRegistry.class);
        innbucks.paymentservice.order.OrderGateway gateway = mock(innbucks.paymentservice.order.OrderGateway.class);
        when(registry.forType(innbucks.paymentservice.order.OrderType.BOOKING)).thenReturn(gateway);
        String ref = UUID.randomUUID().toString();
        when(gateway.fetch(ref)).thenReturn(new innbucks.paymentservice.order.OrderSnapshot(
                ref, 9200, "USD", "+263771234567", "PINKRUN26", "Pink Run ticket", true));
        ZimswitchCardPaymentService card = cardServiceOver(registry);
        Instant[] checkoutCreatedAt = new Instant[1];
        when(client.prepareCheckout(anyString(), anyLong(), anyString())).thenAnswer(inv -> {
            checkoutCreatedAt[0] = Instant.now();
            return new innbucks.paymentservice.client.CheckoutPreparation(true,
                    "8a82944a4cc25ebf014cc2c782423202", "sha384-x", "000.200.100", "created", "ndc-1");
        });

        Instant before = Instant.now();
        var outcome = card.startCheckout(innbucks.paymentservice.order.OrderType.BOOKING, ref);

        org.mockito.ArgumentCaptor<Instant> payableUntil = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(gateway).extendHold(eq(ref), payableUntil.capture());
        org.mockito.ArgumentCaptor<Instant> recorded = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(records).markCardCheckoutIssued(any(UUID.class), anyString(), anyString(), recorded.capture());
        Duration ceiling = properties.getCheckoutTtl().plus(ZimswitchCardPaymentService.CHECKOUT_EXPIRY_GRACE);
        assertThat(ceiling).isEqualTo(Duration.ofMinutes(30));

        // The hold is sized from the 30-minute ceiling, fixed before the call.
        assertThat(payableUntil.getValue()).isBetween(before.plus(ceiling), checkoutCreatedAt[0].plus(ceiling));
        // The NOT_FOUND rule closes the row at deadline + grace, which must
        // not fall before the gateway's own ceiling — that runs from the
        // checkout's creation, so the stamp must postdate it.
        assertThat(recorded.getValue().plus(ZimswitchCardPaymentService.CHECKOUT_EXPIRY_GRACE))
                .isAfterOrEqualTo(checkoutCreatedAt[0].plus(ceiling));
        // ... and the hold still covers that later stamp, by all but the
        // call's own duration of the safety margin.
        assertThat(recorded.getValue().plus(ZimswitchCardPaymentService.CHECKOUT_EXPIRY_GRACE))
                .isBetween(payableUntil.getValue(),
                        payableUntil.getValue().plus(innbucks.paymentservice.order.OrderGateway.HOLD_SAFETY_MARGIN));
        assertThat(outcome.checkoutExpiresAt()).isEqualTo(recorded.getValue());
        InOrder inOrder = inOrder(gateway, records, client);
        inOrder.verify(gateway).extendHold(eq(ref), any(Instant.class));
        inOrder.verify(records).openPending(any(Payment.class));
        inOrder.verify(client).prepareCheckout(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("through the real booking gateway, a card checkout holds the seats for now + 33 minutes (30-minute ceiling + 3m margin)")
    void startCheckout_bookingSeatHold_isThirtyThreeMinutes() {
        UUID bookingId = UUID.randomUUID();
        innbucks.paymentservice.client.BookingServiceClient bookings =
                mock(innbucks.paymentservice.client.BookingServiceClient.class);
        innbucks.paymentservice.client.EventServiceClient events =
                mock(innbucks.paymentservice.client.EventServiceClient.class);
        when(events.getSettlementInfo(any())).thenReturn(java.util.Optional.empty());
        when(bookings.getBooking(bookingId)).thenReturn(java.util.Map.of(
                "totalAmount", new BigDecimal("92.00"), "currency", "USD", "phoneNumber", "+263771234567"));
        ZimswitchCardPaymentService card = cardServiceOver(new OrderGatewayRegistry(java.util.List.of(
                new innbucks.paymentservice.order.BookingOrderGateway(bookings, events, "USD"))));
        when(client.prepareCheckout(anyString(), anyLong(), anyString())).thenReturn(
                new innbucks.paymentservice.client.CheckoutPreparation(true,
                        "8a82944a4cc25ebf014cc2c782423202", "sha384-x", "000.200.100", "created", "ndc-1"));

        Instant before = Instant.now();
        card.startCheckout(innbucks.paymentservice.order.OrderType.BOOKING, bookingId.toString());
        Instant after = Instant.now();

        org.mockito.ArgumentCaptor<Instant> hold = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(bookings).extendHold(eq(bookingId), hold.capture());
        // A card paid after minute 13 used to be paid with no ticket.
        assertThat(hold.getValue()).isBetween(
                before.plus(Duration.ofMinutes(33)), after.plus(Duration.ofMinutes(33)));
    }

    @Test
    @DisplayName("a refused hold mints nothing: no ledger row, no checkout")
    void startCheckout_holdRefused_mintsNothing() {
        OrderGatewayRegistry registry = mock(OrderGatewayRegistry.class);
        innbucks.paymentservice.order.OrderGateway gateway = mock(innbucks.paymentservice.order.OrderGateway.class);
        when(registry.forType(innbucks.paymentservice.order.OrderType.BOOKING)).thenReturn(gateway);
        String ref = UUID.randomUUID().toString();
        when(gateway.fetch(ref)).thenReturn(new innbucks.paymentservice.order.OrderSnapshot(
                ref, 9200, "USD", "+263771234567", "PINKRUN26", "Pink Run ticket", true));
        org.mockito.Mockito.doThrow(new InvalidPaymentRequestException(
                        "Your booking has expired — please create a new booking and try again", 409))
                .when(gateway).extendHold(eq(ref), any(Instant.class));
        ZimswitchCardPaymentService card = cardServiceOver(registry);

        assertThatThrownBy(() -> card.startCheckout(innbucks.paymentservice.order.OrderType.BOOKING, ref))
                .isInstanceOfSatisfying(InvalidPaymentRequestException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(409));

        verify(records, never()).openPending(any());
        verify(client, never()).prepareCheckout(anyString(), anyLong(), anyString());
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
    @DisplayName("REPLAY on a half-provisioned cell returns NO artifacts — never a widget with an empty form action")
    void replayOpenCheckout_refusesWhenResultUrlUnusable() {
        // The gap the start-gate alone left: a row minted before the cell was
        // fully provisioned (or by an old pod mid-rolling-deploy) would replay
        // a COMPLETE artifact set with a blank shopperResultUrl — exactly the
        // unrenderable response the start gate exists to prevent.
        when(client.canStartCheckout()).thenReturn(false);

        assertThat(service.replayOpenCheckout(openCardRow())).isNull();
    }

    @Test
    @DisplayName("REPLAY on a healthy cell re-surfaces the SAME checkout's artifacts")
    void replayOpenCheckout_reSurfacesSameCheckout() {
        Payment p = openCardRow();
        when(client.canStartCheckout()).thenReturn(true);
        when(client.widgetScriptUrl(p.getCheckoutId())).thenReturn("https://gw.example/widgets?checkoutId=x");
        properties.setShopperResultUrl("https://tickets.example.co.zw/checkout/card-result");
        properties.setBrands("PRIVATE_LABEL");

        var out = service.replayOpenCheckout(p);

        assertThat(out).isNotNull();
        // Same checkout — the gateway's documented reuse model; a second
        // checkout here would be a double-charge surface.
        assertThat(out.checkoutId()).isEqualTo(p.getCheckoutId());
        assertThat(out.shopperResultUrl()).isEqualTo("https://tickets.example.co.zw/checkout/card-result");
        assertThat(out.brands()).isEqualTo("PRIVATE_LABEL");
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
        assertThat(service.resolveOpenCheckout(noCheckout, NO_GAP, RETURN)).isEqualTo(CardCheckOutcome.PENDING);

        Payment terminal = openCardRow();
        terminal.setStatus(Payment.PaymentStatus.SUCCEEDED);
        assertThat(service.resolveOpenCheckout(terminal, NO_GAP, RETURN)).isEqualTo(CardCheckOutcome.PENDING);

        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("before the ceiling the poller and a plain re-POST never read: a read kills a checkout the shopper may still be on")
    void beforeCeiling_onlyTheShoppersReturnReads() {
        Payment p = openCardRow(); // made 5 minutes ago, deadline 23 minutes away

        assertThat(service.resolveOpenCheckout(p, NO_GAP, ReadTrigger.POLLER)).isEqualTo(CardCheckOutcome.PENDING);
        assertThat(service.resolveOpenCheckout(p, NO_GAP, ReadTrigger.CUSTOMER_RETRY))
                .isEqualTo(CardCheckOutcome.PENDING);

        verifyNoInteractions(client);
        verify(records, never()).stampCardStatusChecked(any());
        verify(records, never()).noteEvent(any(), anyString());
    }

    @Test
    @DisplayName("between the local deadline and the ceiling a plain re-POST still waits: the checkout may still be payable")
    void betweenDeadlineAndCeiling_plainRePostWaits() {
        Payment p = openCardRow();
        p.setCodeExpiresAt(Instant.now().minusSeconds(30)); // deadline passed, ceiling (+2 min) not yet

        assertThat(service.resolveOpenCheckout(p, NO_GAP, ReadTrigger.CUSTOMER_RETRY))
                .isEqualTo(CardCheckOutcome.PENDING);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("past the ceiling a plain re-POST reads: the checkout can no longer be paid, so the read harms nothing and frees the slot")
    void pastCeiling_plainRePostReadsAndFreesTheSlot() {
        Payment p = openCardRow();
        p.setCodeExpiresAt(Instant.now().minus(ZimswitchCardPaymentService.CHECKOUT_EXPIRY_GRACE)
                .minus(Duration.ofSeconds(5)));
        when(client.getPaymentStatus(p.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.CHECKOUT_NOT_FOUND, "200.300.404", null, null, null));

        assertThat(service.resolveOpenCheckout(p, NO_GAP, ReadTrigger.CUSTOMER_RETRY))
                .isEqualTo(CardCheckOutcome.EXPIRED);
        verify(records).markExpired(eq(p.getId()), contains("customer re-POST"));
    }

    @Test
    @DisplayName("a decline read past the ceiling closes the row FAILED: no retry can follow it")
    void declinePastCeiling_closesTheRowFailed() {
        Payment p = openCardRow();
        p.setCodeExpiresAt(Instant.now().minus(ZimswitchCardPaymentService.CHECKOUT_EXPIRY_GRACE)
                .minus(Duration.ofSeconds(5)));
        when(client.getPaymentStatus(p.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.REJECTED, "800.100.153", "92.00", "USD", p.getPaymentReference()));

        assertThat(service.resolveOpenCheckout(p, NO_GAP, ReadTrigger.POLLER)).isEqualTo(CardCheckOutcome.EXPIRED);

        verify(records).markFailed(eq(p.getId()), eq("800.100.153"), contains("past the checkout ceiling"));
        verify(records, never()).markExpired(any(), any());
        verifyNoInteractions(resolution);
    }

    @Test
    @DisplayName("every read lands in the journal with ZimSwitch's result code and what triggered it")
    void everyRead_isJournalled() {
        Payment notYet = openCardRow();
        when(client.getPaymentStatus(notYet.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.CHECKOUT_NOT_FOUND, "200.300.404", null, null, null));
        service.resolveOpenCheckout(notYet, NO_GAP, RETURN);
        verify(records).noteEvent(eq(notYet.getId()),
                argThat(d -> d.contains("200.300.404") && d.contains("shopper's return")));

        Payment pending = openCardRow();
        pending.setId(UUID.randomUUID());
        when(client.getPaymentStatus(pending.getCheckoutId())).thenReturn(
                status(ZimswitchResultCode.PENDING, "000.200.000", null, null, null));
        service.resolveOpenCheckout(pending, NO_GAP, RETURN);
        verify(records).noteEvent(eq(pending.getId()), contains("000.200.000"));

        Payment unanswered = openCardRow();
        unanswered.setId(UUID.randomUUID());
        when(client.getPaymentStatus(unanswered.getCheckoutId()))
                .thenThrow(new ZimswitchApiTransientException("read timed out", 0));
        service.resolveOpenCheckout(unanswered, NO_GAP, RETURN);
        verify(records).noteEvent(eq(unanswered.getId()), contains("got no answer"));
    }

    @Test
    @DisplayName("the shopper's return is the redirect for THIS checkout, compared exactly, never used")
    void isShopperReturn_matchesOnlyThisRowsCheckout() {
        Payment p = openCardRow();
        String own = "/v1/checkouts/" + p.getCheckoutId() + "/payment";

        assertThat(ZimswitchCardPaymentService.isShopperReturn(own, p)).isTrue();
        assertThat(ZimswitchCardPaymentService.isShopperReturn("  " + own + "\n", p)).isTrue();
        assertThat(ZimswitchCardPaymentService.isShopperReturn(null, p)).isFalse();
        assertThat(ZimswitchCardPaymentService.isShopperReturn("", p)).isFalse();
        assertThat(ZimswitchCardPaymentService.isShopperReturn(
                "/v1/checkouts/43914C7C89177901A2E33EE1C6CCB1C7.uat01-vm-tx02/payment", p)).isFalse();
        assertThat(ZimswitchCardPaymentService.isShopperReturn(
                "https://evil.example/v1/checkouts/" + p.getCheckoutId() + "/payment", p)).isFalse();
        assertThat(ZimswitchCardPaymentService.isShopperReturn(
                "/v1/checkouts/" + p.getCheckoutId().toUpperCase() + "/payment", p)).isFalse();

        Payment noCheckout = openCardRow();
        noCheckout.setCheckoutId(null);
        assertThat(ZimswitchCardPaymentService.isShopperReturn("/v1/checkouts/null/payment", noCheckout)).isFalse();
    }

    @Test
    @DisplayName("the sweep's query cut-offs and the resolver's ceiling are one definition")
    void ceilingCutoffs_agreeWithTheCeiling() {
        Instant now = Instant.now();
        ZimswitchCardPaymentService.CeilingCutoffs cutoffs = ZimswitchCardPaymentService.CeilingCutoffs.at(now);

        Payment justPast = openCardRow();
        justPast.setCodeExpiresAt(cutoffs.deadlineBefore().minusSeconds(1));
        assertThat(ZimswitchCardPaymentService.isPastCeiling(justPast, now)).isTrue();

        Payment notYet = openCardRow();
        notYet.setCodeExpiresAt(cutoffs.deadlineBefore().plusSeconds(1));
        assertThat(ZimswitchCardPaymentService.isPastCeiling(notYet, now)).isFalse();

        Payment noDeadlineOld = openCardRow();
        noDeadlineOld.setCodeExpiresAt(null);
        noDeadlineOld.setCreatedAt(cutoffs.createdBefore().minusSeconds(1));
        assertThat(ZimswitchCardPaymentService.isPastCeiling(noDeadlineOld, now)).isTrue();

        Payment noDeadlineYoung = openCardRow();
        noDeadlineYoung.setCodeExpiresAt(null);
        noDeadlineYoung.setCreatedAt(cutoffs.createdBefore().plusSeconds(1));
        assertThat(ZimswitchCardPaymentService.isPastCeiling(noDeadlineYoung, now)).isFalse();
    }
}
