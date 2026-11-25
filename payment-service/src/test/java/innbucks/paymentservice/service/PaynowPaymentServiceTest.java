package innbucks.paymentservice.service;

import innbucks.paymentservice.client.PaynowApiException;
import innbucks.paymentservice.client.PaynowApiTransientException;
import innbucks.paymentservice.client.PaynowClient;
import innbucks.paymentservice.client.PaynowInitiation;
import innbucks.paymentservice.client.PaynowNotSentException;
import innbucks.paymentservice.client.PaynowProperties;
import innbucks.paymentservice.client.PaynowStatus;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.PaymentRail;
import innbucks.paymentservice.order.ConfirmOutcome;
import innbucks.paymentservice.order.OrderGateway;
import innbucks.paymentservice.order.OrderGatewayRegistry;
import innbucks.paymentservice.order.OrderSnapshot;
import innbucks.paymentservice.order.OrderType;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import innbucks.paymentservice.service.PaynowPaymentService.CheckOutcome;
import innbucks.paymentservice.service.PaynowPaymentService.Trigger;
import innbucks.paymentservice.service.PaynowPaymentService.UpdateAck;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Money rules of the Paynow rails ({@code docs/api/paynow.md}), each pinned as
 * its own case: the row is armed before the call; a start with no answer
 * closes a card or O'mari attempt (nothing to pay with) but leaves a OneMoney
 * prompt open; a poll, never an update, confirms; an echo mismatch parks
 * IN_DOUBT; an unknown status never closes; and money that moves after we
 * closed or confirmed goes to an operator, changing nothing.
 */
class PaynowPaymentServiceTest {

    private final PaymentRecordService records = mock(PaymentRecordService.class);
    private final PaynowClient client = mock(PaynowClient.class);
    private final PaynowProperties properties = new PaynowProperties();
    private final OrderGatewayRegistry gateways = mock(OrderGatewayRegistry.class);
    private final CodePaymentResolutionService resolution = mock(CodePaymentResolutionService.class);
    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PaymentMetrics metrics = new PaymentMetrics(registry);
    private final PaynowPaymentService service = new PaynowPaymentService(
            records, client, properties, gateways, resolution, repository, metrics);

    private static final String POLL = "https://www.paynow.co.zw/Interface/CheckPayment/?guid=g1";
    private static final String REFERENCE = "TKZ-AMA2KFESTIVA-CF604A7376B7";

    {
        properties.setReturnUrl("https://ticketize.example.co.zw/checkout/paynow-return");
        ReflectionTestUtils.setField(service, "cellCurrency", "USD");
        when(records.recentCharges(any(), any(), any())).thenReturn(new PaymentRecordService.RecentCharges(0, null));
        when(records.openPending(any())).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            p.setStatus(Payment.PaymentStatus.PENDING);
            return p;
        });
        when(client.hasAnyIntegration()).thenReturn(true);
        when(client.isConfigured("USD")).thenReturn(true);
        when(client.canStartWeb("USD")).thenReturn(true);
        when(client.canStartExpress("USD")).thenReturn(true);
        // The ledger's guarded writes apply unless a case says otherwise.
        when(records.claimPaid(any(), any(), anyString())).thenReturn(true);
        when(records.flagPaynowOperatorCase(any())).thenReturn(true);
        when(records.parkUnconfirmedInDoubt(any(), anyString())).thenReturn(true);
    }

    private OrderGateway payableBooking(String phone) {
        OrderGateway gateway = mock(OrderGateway.class);
        when(gateways.forType(OrderType.BOOKING)).thenReturn(gateway);
        when(gateway.fetch(anyString())).thenReturn(new OrderSnapshot(
                UUID.randomUUID().toString(), 1250, "USD", phone, "AMA2KFESTIVA", "Ama2K Festiva", true));
        return gateway;
    }

    private static Payment open(PaymentRail rail, Instant expiresAt) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference(REFERENCE)
                .paymentRail(rail)
                .orderType(OrderType.BOOKING)
                .orderRef(UUID.randomUUID().toString())
                .status(Payment.PaymentStatus.TOKEN_ISSUED)
                .paynowTrace("0123456789abcdef0123456789abcdef")
                .paynowPollUrl(POLL)
                .customerMsisdn("+263771111111")
                .amount(new BigDecimal("12.50"))
                .currency("USD")
                .createdAt(Instant.now().minus(Duration.ofMinutes(1)))
                .codeExpiresAt(expiresAt)
                .build();
    }

    private static PaynowStatus status(PaynowStatus.Outcome outcome, String raw, String amount, String reference) {
        return new PaynowStatus(outcome, raw, reference, "123456", amount == null ? null : new BigDecimal(amount), POLL);
    }

    private double starts(String rail, String outcome) {
        var c = registry.find("payment.payments.paynow_start").tag("rail", rail).tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    /** {@code p} as the database holds it now, with another status. */
    private static Payment asStored(Payment p, Payment.PaymentStatus status) {
        Payment copy = open(p.getPaymentRail(), p.getCodeExpiresAt());
        copy.setId(p.getId());
        copy.setStatus(status);
        copy.setPaynowPollUrl(p.getPaynowPollUrl());
        copy.setCompletedAt(Instant.now().minusSeconds(60));
        return copy;
    }

    private double resolutions(String outcome) {
        var c = registry.find("payment.payments.paynow_resolution").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    // -- start -----------------------------------------------------------------

    @Test
    @DisplayName("card: hold, then the row with its trace, then TOKEN_ISSUED, THEN the one initiate call")
    void startCard_ordering() {
        OrderGateway gateway = payableBooking("+263771111111");
        when(client.initiateWeb(eq("USD"), anyString(), eq(1250L), anyString(), anyString(), anyString()))
                .thenReturn(new PaynowInitiation("https://www.paynow.co.zw/Payment/ConfirmPayment/9510", POLL,
                        "9510", null, null, null));

        var outcome = service.start(PaymentRail.PAYNOW_CARD, OrderType.BOOKING, UUID.randomUUID().toString());

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.redirectUrl()).isEqualTo("https://www.paynow.co.zw/Payment/ConfirmPayment/9510");
        ArgumentCaptor<Payment> draft = ArgumentCaptor.forClass(Payment.class);
        ArgumentCaptor<String> returnUrl = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> trace = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(gateway, records, client);
        order.verify(gateway).extendHold(anyString(), any());
        order.verify(records).openPending(draft.capture());
        order.verify(records).markPaynowIssued(any(), anyString(), any());
        order.verify(client).initiateWeb(eq("USD"), anyString(), eq(1250L), eq("Ticketize tickets"),
                returnUrl.capture(), trace.capture());
        order.verify(records).recordPaynowInitiation(any(), eq(POLL), anyString(), eq("9510"), any(), any());
        assertThat(draft.getValue().getPaymentRail()).isEqualTo(PaymentRail.PAYNOW_CARD);
        assertThat(draft.getValue().getPaynowTrace()).matches("[0-9a-f]{32}").isEqualTo(trace.getValue());
        // The bare return page: our payment id never goes on a page URL, where
        // history, analytics and logs would keep the key to the booking.
        assertThat(returnUrl.getValue()).isEqualTo("https://ticketize.example.co.zw/checkout/paynow-return")
                .doesNotContain(draft.getValue().getId().toString());
    }

    @Test
    @DisplayName("tickets only for now: a marketplace or voucher order is refused before anything is touched")
    void start_ticketsOnly() {
        assertThatThrownBy(() -> service.start(PaymentRail.ONEMONEY, OrderType.MARKETPLACE, "MKT-1"))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .satisfies(e -> assertThat(((InvalidPaymentRequestException) e).getStatusCode()).isEqualTo(422));
        verifyNoInteractions(gateways, records);
    }

    @Test
    @DisplayName("a currency with no integration is a 422; an integration missing its URLs is a 503")
    void start_currencyAndProvisioning() {
        OrderGateway gateway = mock(OrderGateway.class);
        when(gateways.forType(OrderType.BOOKING)).thenReturn(gateway);
        when(gateway.fetch(anyString())).thenReturn(new OrderSnapshot("r", 1250, "ZWG", "+263771111111", "T", "n", true));
        assertThatThrownBy(() -> service.start(PaymentRail.PAYNOW_CARD, OrderType.BOOKING, UUID.randomUUID().toString()))
                .satisfies(e -> assertThat(((InvalidPaymentRequestException) e).getStatusCode()).isEqualTo(422));

        when(gateway.fetch(anyString())).thenReturn(new OrderSnapshot("r", 1250, "USD", "+263771111111", "T", "n", true));
        when(client.canStartWeb("USD")).thenReturn(false);
        assertThatThrownBy(() -> service.start(PaymentRail.PAYNOW_CARD, OrderType.BOOKING, UUID.randomUUID().toString()))
                .satisfies(e -> assertThat(((InvalidPaymentRequestException) e).getStatusCode()).isEqualTo(503));
        verify(gateway, never()).extendHold(anyString(), any());
        verify(records, never()).openPending(any());
    }

    @Test
    @DisplayName("a wallet refused at the start (insufficient balance): FAILED with Paynow's words, slot freed")
    void startExpress_declined() {
        payableBooking("+263712345678");
        when(client.initiateExpress(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new PaynowApiException("Paynow refused the payment: Insufficient balance", "Insufficient balance"));

        var outcome = service.start(PaymentRail.ONEMONEY, OrderType.BOOKING, UUID.randomUUID().toString());

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.failureStatus()).isEqualTo(400);
        assertThat(outcome.failureMessage()).isEqualTo("OneMoney: Insufficient balance.");
        verify(records).markFailed(any(), eq(PaynowPaymentService.DECLINED_CODE), eq("Insufficient balance"));
    }

    @Test
    @DisplayName("no answer to a card start: closed at once (no page to pay on); to a OneMoney start: left open")
    void start_noAnswer() {
        payableBooking("+263712345678");
        when(client.initiateWeb(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new PaynowApiTransientException("read timed out"));
        var card = service.start(PaymentRail.PAYNOW_CARD, OrderType.BOOKING, UUID.randomUUID().toString());
        assertThat(card.failed()).isTrue();
        assertThat(card.failureStatus()).isEqualTo(502);
        verify(records).markFailed(any(), eq(PaynowPaymentService.UNREACHABLE_CODE), anyString());

        when(client.initiateExpress(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new PaynowApiTransientException("read timed out"));
        var oneMoney = service.start(PaymentRail.ONEMONEY, OrderType.BOOKING, UUID.randomUUID().toString());
        assertThat(oneMoney.failed()).as("a OneMoney prompt may be live: never closed on no answer").isFalse();
        assertThat(oneMoney.expiresAt()).isNotNull();
        verify(records, never()).markFailed(eq(oneMoney.payment().getId()), anyString(), anyString());
    }

    @Test
    @DisplayName("O'mari: the booking's +263 number goes to Paynow as 0771111111, method omari")
    void startOmari_phone() {
        payableBooking("+263771111111");
        when(client.initiateExpress(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new PaynowInitiation(null, POLL, "9512", "OTP-7731",
                        "https://www.paynow.co.zw/interface/remoteotp/9512", null));

        var outcome = service.start(PaymentRail.OMARI, OrderType.BOOKING, UUID.randomUUID().toString());

        assertThat(outcome.otpReference()).isEqualTo("OTP-7731");
        verify(client).initiateExpress(eq("USD"), anyString(), eq(1250L), anyString(), anyString(), eq("omari"),
                eq("0771111111"));
    }

    @Test
    @DisplayName("five OneMoney/O'mari requests to one number in the window: the sixth is a 429, nothing held")
    void start_promptCap() {
        OrderGateway gateway = payableBooking("+263712345678");
        when(records.recentCharges(any(), any(), any()))
                .thenReturn(new PaymentRecordService.RecentCharges(5, Instant.now().minus(Duration.ofMinutes(5))));

        assertThatThrownBy(() -> service.start(PaymentRail.ONEMONEY, OrderType.BOOKING, UUID.randomUUID().toString()))
                .satisfies(e -> assertThat(((InvalidPaymentRequestException) e).getStatusCode()).isEqualTo(429));
        verify(gateway, never()).extendHold(anyString(), any());
    }

    @Test
    @DisplayName("Paynow's mobile number format")
    void localPhone() {
        assertThat(PaynowPaymentService.localPhone("+263771111111")).isEqualTo("0771111111");
        assertThat(PaynowPaymentService.localPhone("263771111111")).isEqualTo("0771111111");
        assertThat(PaynowPaymentService.localPhone("0771111111")).isEqualTo("0771111111");
        assertThat(PaynowPaymentService.localPhone("771111111")).isEqualTo("0771111111");
        assertThat(PaynowPaymentService.localPhone("+27821234567")).isNull();
    }

    // -- resolveOpen -------------------------------------------------------------

    @Test
    @DisplayName("PAID by poll, echoes ours: the booking is confirmed and the payment SUCCEEDED")
    void resolve_paid() {
        Payment p = open(PaymentRail.PAYNOW_CARD, Instant.now().plusSeconds(600));
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE));
        when(resolution.confirmOrder(p)).thenReturn(ConfirmOutcome.confirmed("INN-20261125-941C78"));

        assertThat(service.resolveOpen(p, Trigger.SWEEP)).isEqualTo(CheckOutcome.PAID);
        InOrder order = inOrder(records, resolution);
        order.verify(records).claimPaid(eq(p.getId()), eq("123456"), anyString());
        order.verify(resolution).confirmOrder(p);
        order.verify(records).resolveUnconfirmed(eq(p.getId()), eq("INN-20261125-941C78"), anyString());
        verify(records, never()).markSucceeded(any(), any(), any());
        assertThat(resolutions("paid")).isEqualTo(1);
    }

    @Test
    @DisplayName("PAID with an amount or reference that is not ours: IN_DOUBT, never confirmed")
    void resolve_echoMismatch() {
        Payment p = open(PaymentRail.PAYNOW_CARD, Instant.now().plusSeconds(600));
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "1250.00", REFERENCE));

        assertThat(service.resolveOpen(p, Trigger.SWEEP)).isEqualTo(CheckOutcome.OPEN);
        verify(records).markInDoubt(eq(p.getId()), anyString());
        verify(resolution, never()).confirmOrder(any());
        assertThat(resolutions("echo_mismatch")).isEqualTo(1);

        Payment q = open(PaymentRail.ONEMONEY, Instant.now().plusSeconds(600));
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", "TKZ-OTHER-1"));
        service.resolveOpen(q, Trigger.SWEEP);
        verify(records).markInDoubt(eq(q.getId()), anyString());
    }

    @Test
    @DisplayName("Cancelled or Failed: FAILED, slot freed")
    void resolve_cancelled() {
        Payment p = open(PaymentRail.ONEMONEY, Instant.now().plusSeconds(600));
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.CANCELLED, "Failed", null, null));

        assertThat(service.resolveOpen(p, Trigger.RESULT_UPDATE)).isEqualTo(CheckOutcome.CLOSED);
        verify(records).markFailed(eq(p.getId()), eq(PaynowPaymentService.CANCELLED_CODE), anyString());
    }

    @Test
    @DisplayName("Created: open before the deadline, closed EXPIRED after it; UNKNOWN never closes")
    void resolve_openAndUnknown() {
        Payment young = open(PaymentRail.PAYNOW_CARD, Instant.now().plusSeconds(600));
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.OPEN, "Created", null, null));
        assertThat(service.resolveOpen(young, Trigger.SWEEP)).isEqualTo(CheckOutcome.OPEN);
        verify(records, never()).markExpired(any(), anyString());

        Payment old = open(PaymentRail.PAYNOW_CARD, Instant.now().minus(Duration.ofMinutes(10)));
        assertThat(service.resolveOpen(old, Trigger.SWEEP)).isEqualTo(CheckOutcome.CLOSED);
        verify(records).markExpired(eq(old.getId()), anyString());

        Payment strange = open(PaymentRail.PAYNOW_CARD, Instant.now().minus(Duration.ofMinutes(10)));
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.UNKNOWN, "Pending Review", null, null));
        assertThat(service.resolveOpen(strange, Trigger.SWEEP)).isEqualTo(CheckOutcome.OPEN);
        verify(records, never()).markExpired(eq(strange.getId()), anyString());
        verify(records, never()).markFailed(eq(strange.getId()), anyString(), anyString());
    }

    @Test
    @DisplayName("no poll URL (the start's reply was lost): looked up by trace")
    void resolve_byTrace() {
        Payment p = open(PaymentRail.ONEMONEY, Instant.now().plusSeconds(600));
        p.setPaynowPollUrl(null);
        when(client.trace("USD", p.getPaynowTrace())).thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE));
        when(resolution.confirmOrder(p)).thenReturn(ConfirmOutcome.confirmed("INN-1"));

        assertThat(service.resolveOpen(p, Trigger.SWEEP)).isEqualTo(CheckOutcome.PAID);
        verify(client, never()).poll(anyString(), anyString());
        verify(records).recordPaynowSeen(p.getId(), "123456", POLL);
    }

    @Test
    @DisplayName("Paynow unreachable: ERROR, and nothing is closed")
    void resolve_error() {
        Payment p = open(PaymentRail.PAYNOW_CARD, Instant.now().minus(Duration.ofMinutes(10)));
        when(client.poll("USD", POLL)).thenThrow(new PaynowApiTransientException("down"));

        assertThat(service.resolveOpen(p, Trigger.SWEEP)).isEqualTo(CheckOutcome.ERROR);
        verify(records, never()).markExpired(any(), anyString());
        verify(records, never()).markFailed(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a customer re-POST inside the minimum gap does not poll; a Paynow update always does")
    void resolve_gap() {
        Payment p = open(PaymentRail.PAYNOW_CARD, Instant.now().plusSeconds(600));
        p.setPaynowCheckedAt(Instant.now());
        assertThat(service.resolveOpen(p, Trigger.CUSTOMER)).isEqualTo(CheckOutcome.OPEN);
        verify(client, never()).poll(anyString(), anyString());

        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.OPEN, "Sent", null, null));
        service.resolveOpen(p, Trigger.RESULT_UPDATE);
        verify(client).poll("USD", POLL);
    }

    // -- result-URL updates ----------------------------------------------------------

    @Test
    @DisplayName("an update that does not verify is ignored with a 200; an open payment is checked by poll")
    void update_hashAndOpen() {
        Payment p = open(PaymentRail.PAYNOW_CARD, Instant.now().plusSeconds(600));
        when(repository.findByPaymentReference(REFERENCE)).thenReturn(Optional.of(p));
        when(client.verifiedUpdate(eq("USD"), any())).thenReturn(Optional.empty());

        assertThat(service.onResultUpdate("reference=" + REFERENCE + "&status=Paid&hash=00")).isEqualTo(UpdateAck.OK);
        verify(client, never()).poll(anyString(), anyString());

        when(client.verifiedUpdate(eq("USD"), any()))
                .thenReturn(Optional.of(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE)));
        when(client.poll("USD", POLL)).thenThrow(new PaynowApiTransientException("down"));
        assertThat(service.onResultUpdate("reference=" + REFERENCE + "&status=Paid&hash=AB"))
                .as("a payment we could not check is a 503, so Paynow resends").isEqualTo(UpdateAck.RETRY);
    }

    @Test
    @DisplayName("paid after we closed it: confirmed by poll, journalled and counted for an operator, status untouched")
    void update_paidAfterClose() {
        Payment p = open(PaymentRail.PAYNOW_CARD, Instant.now().minusSeconds(600));
        p.setStatus(Payment.PaymentStatus.EXPIRED);
        when(repository.findByPaymentReference(REFERENCE)).thenReturn(Optional.of(p));
        when(client.verifiedUpdate(eq("USD"), any()))
                .thenReturn(Optional.of(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE)));
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE));

        assertThat(service.onResultUpdate("reference=" + REFERENCE)).isEqualTo(UpdateAck.OK);

        verify(records).noteEvent(eq(p.getId()), anyString());
        verify(records, never()).markSucceeded(any(), any(), any());
        verify(resolution, never()).confirmOrder(any());
        assertThat(resolutions("paid_after_close")).isEqualTo(1);
    }

    @Test
    @DisplayName("refunded after the booking was confirmed: an operator's case, the booking untouched")
    void update_reversedAfterPaid() {
        Payment p = open(PaymentRail.ONEMONEY, Instant.now().minusSeconds(600));
        p.setStatus(Payment.PaymentStatus.SUCCEEDED);
        when(repository.findByPaymentReference(REFERENCE)).thenReturn(Optional.of(p));
        when(client.verifiedUpdate(eq("USD"), any()))
                .thenReturn(Optional.of(status(PaynowStatus.Outcome.REVERSED, "Refunded", "12.50", REFERENCE)));
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.REVERSED, "Refunded", "12.50", REFERENCE));

        assertThat(service.onResultUpdate("reference=" + REFERENCE)).isEqualTo(UpdateAck.OK);
        assertThat(resolutions("reversed_after_paid")).isEqualTo(1);
        verify(records).noteEvent(eq(p.getId()), anyString());
    }

    @Test
    @DisplayName("the operator outcomes exist at 0 before anything happens, so the alert sees the first one")
    void operatorOutcomesRegisteredAtZero() {
        for (String outcome : PaymentMetrics.PAYNOW_OPERATOR_OUTCOMES) {
            assertThat(registry.find("payment.payments.paynow_resolution").tag("outcome", outcome).counter())
                    .as(outcome).isNotNull();
        }
    }

    // -- O'mari code -------------------------------------------------------------

    @Test
    @DisplayName("O'mari code: refused codes are a 400 the customer can retry; an accepted one is confirmed by poll")
    void otp() {
        Payment p = open(PaymentRail.OMARI, Instant.now().plusSeconds(600));
        p.setPaynowOtpUrl("https://www.paynow.co.zw/interface/remoteotp/9512");
        when(repository.findById(p.getId())).thenReturn(Optional.of(p));

        assertThat(service.submitOtp(p.getId(), "12ab").httpStatus()).isEqualTo(400);
        verify(client, never()).submitOtp(anyString(), anyString(), anyString());

        when(client.submitOtp("USD", p.getPaynowOtpUrl(), "482913"))
                .thenThrow(new PaynowApiException("Paynow refused the code: Invalid OTP", "Invalid OTP"));
        assertThat(service.submitOtp(p.getId(), "482913").httpStatus()).isEqualTo(400);

        org.mockito.Mockito.reset(client);
        when(client.submitOtp("USD", p.getPaynowOtpUrl(), "482914"))
                .thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE));
        when(client.pollOnce("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE));
        when(resolution.confirmOrder(p)).thenReturn(ConfirmOutcome.confirmed("INN-1"));
        assertThat(service.submitOtp(p.getId(), " 482914 ").httpStatus()).isEqualTo(200);
        // One read, no retries: a customer waits on this reply behind the gateway's 60 s.
        verify(client).pollOnce("USD", POLL);
        verify(client, never()).poll(anyString(), anyString());
        verify(records).resolveUnconfirmed(eq(p.getId()), eq("INN-1"), anyString());
    }

    @Test
    @DisplayName("O'mari code that never left (breaker open, no connection): a 503 asking for the code again, "
            + "journalled as NOT sent, the payment left open; an ambiguous one stays the 502 'wait'")
    void otp_notSent() {
        Payment p = open(PaymentRail.OMARI, Instant.now().plusSeconds(600));
        p.setPaynowOtpUrl("https://www.paynow.co.zw/interface/remoteotp/9512");
        when(repository.findById(p.getId())).thenReturn(Optional.of(p));
        when(client.submitOtp("USD", p.getPaynowOtpUrl(), "482913"))
                .thenThrow(new PaynowNotSentException("Paynow is temporarily unavailable (circuit open)", null));

        var notSent = service.submitOtp(p.getId(), "482913");

        assertThat(notSent.httpStatus()).isEqualTo(503);
        assertThat(notSent.message())
                .isEqualTo("We could not reach O'mari, so your code was not sent. Enter it again in a moment.");
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(records).noteEvent(eq(p.getId()), note.capture());
        assertThat(note.getValue()).startsWith("O'mari code NOT sent").doesNotContain("code sent,");
        verify(records, never()).markFailed(any(), anyString(), anyString());
        verify(records, never()).markExpired(any(), anyString());
        verify(client, never()).pollOnce(anyString(), anyString());

        // Sent, but no answer: the code may have been used, so "wait", not "again".
        org.mockito.Mockito.reset(client);
        when(client.submitOtp("USD", p.getPaynowOtpUrl(), "482914"))
                .thenThrow(new PaynowApiTransientException("read timed out", null));
        var ambiguous = service.submitOtp(p.getId(), "482914");
        assertThat(ambiguous.httpStatus()).isEqualTo(502);
        assertThat(ambiguous.message()).isEqualTo("We could not reach O'mari. Wait a moment, then check your payment.");
    }

    @Test
    @DisplayName("O'mari code on another rail is a 404; on a paid payment, that payment (200); on one that is over, a 409")
    void otp_wrongRailOrClosed() {
        Payment card = open(PaymentRail.PAYNOW_CARD, Instant.now().plusSeconds(600));
        when(repository.findById(card.getId())).thenReturn(Optional.of(card));
        assertThat(service.submitOtp(card.getId(), "482913").httpStatus()).isEqualTo(404);

        for (Payment.PaymentStatus paid : new Payment.PaymentStatus[] {Payment.PaymentStatus.SUCCEEDED,
                Payment.PaymentStatus.COMPLETED_UNCONFIRMED, Payment.PaymentStatus.IN_DOUBT}) {
            Payment done = open(PaymentRail.OMARI, Instant.now().plusSeconds(600));
            done.setPaynowOtpUrl("https://www.paynow.co.zw/interface/remoteotp/1");
            done.setStatus(paid);
            when(repository.findById(done.getId())).thenReturn(Optional.of(done));
            var outcome = service.submitOtp(done.getId(), "482913");
            assertThat(outcome.httpStatus()).as(paid.name()).isEqualTo(200);
            assertThat(outcome.payment()).isSameAs(done);
        }
        for (Payment.PaymentStatus over : new Payment.PaymentStatus[] {Payment.PaymentStatus.FAILED,
                Payment.PaymentStatus.EXPIRED}) {
            Payment ended = open(PaymentRail.OMARI, Instant.now().plusSeconds(600));
            ended.setPaynowOtpUrl("https://www.paynow.co.zw/interface/remoteotp/1");
            ended.setStatus(over);
            when(repository.findById(ended.getId())).thenReturn(Optional.of(ended));
            assertThat(service.submitOtp(ended.getId(), "482913").httpStatus()).as(over.name()).isEqualTo(409);
        }
        // A closed attempt Paynow then reported paid (flagged): the payment as it
        // stands (200, "don't pay again"), never "start a new payment".
        for (Payment.PaymentStatus over : new Payment.PaymentStatus[] {Payment.PaymentStatus.FAILED,
                Payment.PaymentStatus.EXPIRED}) {
            Payment paidLate = open(PaymentRail.OMARI, Instant.now().plusSeconds(600));
            paidLate.setPaynowOtpUrl("https://www.paynow.co.zw/interface/remoteotp/1");
            paidLate.setStatus(over);
            paidLate.setPaynowFlaggedAt(Instant.now().minusSeconds(60));
            when(repository.findById(paidLate.getId())).thenReturn(Optional.of(paidLate));
            var outcome = service.submitOtp(paidLate.getId(), "482913");
            assertThat(outcome.httpStatus()).as("paid after close, " + over.name()).isEqualTo(200);
            assertThat(outcome.payment()).isSameAs(paidLate);
        }
        verify(client, never()).submitOtp(anyString(), anyString(), anyString());
    }

    // -- review fixes (2026-11-25) ---------------------------------------------

    @Test
    @DisplayName("Paynow's OneMoney test numbers are refused unless the host allows them: test mode pays them with no money")
    void start_testNumbers() {
        OrderGateway gateway = payableBooking("+263771111111");
        assertThatThrownBy(() -> service.start(PaymentRail.ONEMONEY, OrderType.BOOKING, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(InvalidPaymentRequestException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(400);
                    assertThat(e.getMessage()).isEqualTo("OneMoney cannot be used with this number — please pay another way");
                });
        verify(gateway, never()).extendHold(anyString(), any());
        verify(records, never()).openPending(any());
        assertThat(starts("ONEMONEY", "test_number")).isEqualTo(1);

        properties.setTestNumbersAllowed(true);
        when(client.initiateExpress(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new PaynowInitiation(null, POLL, "9513", null, null, null));
        assertThat(service.start(PaymentRail.ONEMONEY, OrderType.BOOKING, UUID.randomUUID().toString()).failed()).isFalse();
        verify(client).initiateExpress(eq("USD"), anyString(), eq(1250L), anyString(), anyString(), eq("onemoney"),
                eq("0771111111"));
    }

    @Test
    @DisplayName("a start that never reached Paynow (breaker open, no connection) is closed at once on every rail, OneMoney too")
    void start_notSent() {
        payableBooking("+263712345678");
        when(client.initiateWeb(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new PaynowNotSentException("Paynow is temporarily unavailable (circuit open)", null));
        var card = service.start(PaymentRail.PAYNOW_CARD, OrderType.BOOKING, UUID.randomUUID().toString());
        assertThat(card.failed()).isTrue();
        assertThat(card.failureStatus()).isEqualTo(502);
        verify(records).markFailed(eq(card.payment().getId()), eq(PaynowPaymentService.UNREACHABLE_CODE), anyString());

        when(client.initiateExpress(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new PaynowNotSentException("Paynow could not be reached: Connection refused", null));
        var oneMoney = service.start(PaymentRail.ONEMONEY, OrderType.BOOKING, UUID.randomUUID().toString());
        assertThat(oneMoney.failed()).as("nothing was sent, so no prompt can be live").isTrue();
        assertThat(oneMoney.failureStatus()).isEqualTo(502);
        assertThat(oneMoney.failureMessage())
                .isEqualTo("OneMoney could not be started — please try again in a moment or pay another way");
        verify(records).markFailed(eq(oneMoney.payment().getId()), eq(PaynowPaymentService.UNREACHABLE_CODE), anyString());
        assertThat(starts("ONEMONEY", "not_sent")).isEqualTo(1);
        assertThat(starts("PAYNOW_CARD", "not_sent")).isEqualTo(1);
    }

    @Test
    @DisplayName("an attempt cancelled or declined moments ago holds a new one on that option: refused with it, nothing held or sent")
    void start_declineHold() {
        OrderGateway gateway = payableBooking("+263712345678");
        String ref = UUID.randomUUID().toString();
        Payment last = open(PaymentRail.ONEMONEY, Instant.now().minusSeconds(60));
        last.setStatus(Payment.PaymentStatus.FAILED);
        last.setUpstreamErrorCode(PaynowPaymentService.CANCELLED_CODE);
        last.setUpstreamErrorMessage("Paynow reports the payment Cancelled");
        last.setCompletedAt(Instant.now().minusSeconds(10));
        when(records.latestOnRail(OrderType.BOOKING, ref, PaymentRail.ONEMONEY)).thenReturn(Optional.of(last));

        assertThatThrownBy(() -> service.start(PaymentRail.ONEMONEY, OrderType.BOOKING, ref))
                .isInstanceOfSatisfying(PaynowPaymentService.PaynowDeclinedException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(400);
                    assertThat(e.declined()).isSameAs(last);
                    assertThat(e.retryAfterSeconds()).isBetween(100L, 111L);
                    assertThat(e.getMessage())
                            .isEqualTo("OneMoney: the payment didn't go through. Try again in 2 minutes, or pay another way.");
                });
        verify(gateway, never()).fetch(anyString());
        verify(records, never()).openPending(any());
        verify(client, never()).initiateExpress(anyString(), anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString());
        assertThat(starts("ONEMONEY", "decline_held")).isEqualTo(1);
        assertThat(service.declineHoldRemaining(last)).isBetween(100L, 111L);

        // A wallet refusal holds too, in Paynow's words.
        last.setUpstreamErrorCode(PaynowPaymentService.DECLINED_CODE);
        last.setUpstreamErrorMessage("Insufficient balance");
        assertThatThrownBy(() -> service.start(PaymentRail.ONEMONEY, OrderType.BOOKING, ref))
                .hasMessage("OneMoney: Insufficient balance. Try again in 2 minutes, or pay another way.");

        // Another option is not held.
        when(client.initiateExpress(anyString(), anyString(), anyLong(), anyString(), anyString(), eq("omari"), anyString()))
                .thenReturn(new PaynowInitiation(null, POLL, "9514", "OTP-1",
                        "https://www.paynow.co.zw/interface/remoteotp/9514", null));
        assertThat(service.start(PaymentRail.OMARI, OrderType.BOOKING, ref).failed()).isFalse();

        // Past the hold, or after an attempt that merely lapsed, OneMoney starts.
        when(client.initiateExpress(anyString(), anyString(), anyLong(), anyString(), anyString(), eq("onemoney"), anyString()))
                .thenReturn(new PaynowInitiation(null, POLL, "9515", null, null, null));
        last.setCompletedAt(Instant.now().minus(Duration.ofMinutes(3)));
        assertThat(service.start(PaymentRail.ONEMONEY, OrderType.BOOKING, ref).failed()).isFalse();
        last.setCompletedAt(Instant.now());
        last.setStatus(Payment.PaymentStatus.EXPIRED);
        assertThat(service.declineHoldRemaining(last)).isNull();
        assertThat(service.start(PaymentRail.ONEMONEY, OrderType.BOOKING, ref).failed()).isFalse();

        // 0 switches it off.
        last.setStatus(Payment.PaymentStatus.FAILED);
        properties.setDeclineHold(Duration.ZERO);
        assertThat(service.declineHoldRemaining(last)).isNull();
    }

    @Test
    @DisplayName("the copy in hand is stale and the row has closed since: nothing is polled or written")
    void resolve_staleCopy() {
        Payment stale = open(PaymentRail.PAYNOW_CARD, Instant.now().plusSeconds(600));
        when(repository.findById(stale.getId()))
                .thenReturn(Optional.of(asStored(stale, Payment.PaymentStatus.EXPIRED)));

        assertThat(service.resolveOpen(stale, Trigger.SWEEP)).isEqualTo(CheckOutcome.OPEN);
        verify(client, never()).poll(anyString(), anyString());
        verify(records, never()).stampPaynowChecked(any());
    }

    @Test
    @DisplayName("Paid, but the row closed between our read and our write: the booking is NOT confirmed; an operator's case, never 'slot free'")
    void resolve_paidOnARowClosedMeanwhile() {
        Payment p = open(PaymentRail.PAYNOW_CARD, Instant.now().minusSeconds(60));
        when(repository.findById(p.getId()))
                .thenReturn(Optional.of(p), Optional.of(asStored(p, Payment.PaymentStatus.EXPIRED)));
        when(records.claimPaid(eq(p.getId()), any(), anyString())).thenReturn(false);
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE));

        // Not CLOSED: a customer re-POST reads CLOSED as "the slot is free" and
        // would start a second payment for an order already paid.
        assertThat(service.resolveOpen(p, Trigger.SWEEP)).isEqualTo(CheckOutcome.PAID_LATE);
        verify(resolution, never()).confirmOrder(any());
        verify(records, never()).resolveUnconfirmed(any(), any(), anyString());
        verify(records).flagPaynowOperatorCase(p.getId());
        assertThat(resolutions("paid_after_close")).isEqualTo(1);
        assertThat(resolutions("paid")).isZero();
    }

    @Test
    @DisplayName("Paid, and another trigger recorded it first: PAID, no second confirm, no operator case")
    void resolve_paidAlreadyRecorded() {
        Payment p = open(PaymentRail.ONEMONEY, Instant.now().plusSeconds(600));
        when(repository.findById(p.getId()))
                .thenReturn(Optional.of(p), Optional.of(asStored(p, Payment.PaymentStatus.COMPLETED_UNCONFIRMED)));
        when(records.claimPaid(eq(p.getId()), any(), anyString())).thenReturn(false);
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE));

        assertThat(service.resolveOpen(p, Trigger.RESULT_UPDATE)).isEqualTo(CheckOutcome.PAID);
        verify(resolution, never()).confirmOrder(any());
        verify(records, never()).flagPaynowOperatorCase(any());
        assertThat(resolutions("paid")).isZero();
    }

    @Test
    @DisplayName("Paid, booking confirm fails: left COMPLETED_UNCONFIRMED for the confirm-retry, never promoted")
    void resolve_paidButConfirmFails() {
        Payment p = open(PaymentRail.PAYNOW_CARD, Instant.now().plusSeconds(600));
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE));
        when(resolution.confirmOrder(p)).thenReturn(ConfirmOutcome.unreachable("booking-service down"));

        assertThat(service.resolveOpen(p, Trigger.SWEEP)).isEqualTo(CheckOutcome.PAID);
        verify(records).claimPaid(eq(p.getId()), eq("123456"), anyString());
        verify(records, never()).resolveUnconfirmed(any(), any(), anyString());
        verify(records).noteEvent(eq(p.getId()), anyString());
        assertThat(resolutions("paid_unconfirmed")).isEqualTo(1);
    }

    @Test
    @DisplayName("refunded before the booking was confirmed: parked IN_DOUBT so the confirm-retry issues no tickets")
    void update_refundBeforeConfirm() {
        Payment p = open(PaymentRail.ONEMONEY, Instant.now().minusSeconds(600));
        p.setStatus(Payment.PaymentStatus.COMPLETED_UNCONFIRMED);
        when(repository.findByPaymentReference(REFERENCE)).thenReturn(Optional.of(p));
        when(client.verifiedUpdate(eq("USD"), any()))
                .thenReturn(Optional.of(status(PaynowStatus.Outcome.REVERSED, "Refunded", "12.50", REFERENCE)));
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.REVERSED, "Refunded", "12.50", REFERENCE));

        assertThat(service.onResultUpdate("reference=" + REFERENCE)).isEqualTo(UpdateAck.OK);
        verify(records).parkUnconfirmedInDoubt(eq(p.getId()), anyString());
        assertThat(resolutions("reversed_before_paid")).isEqualTo(1);
        assertThat(resolutions("reversed_after_paid")).isZero();

        // The confirm-retry promoted it first: the tickets exist, so it is the after-paid case.
        Payment q = open(PaymentRail.ONEMONEY, Instant.now().minusSeconds(600));
        q.setStatus(Payment.PaymentStatus.COMPLETED_UNCONFIRMED);
        when(repository.findByPaymentReference(REFERENCE)).thenReturn(Optional.of(q));
        when(records.parkUnconfirmedInDoubt(eq(q.getId()), anyString())).thenReturn(false);
        service.onResultUpdate("reference=" + REFERENCE);
        assertThat(resolutions("reversed_after_paid")).isEqualTo(1);
    }

    @Test
    @DisplayName("an operator case is raised once: a repeat finds the flag and journals, logs and counts nothing")
    void operatorCase_raisedOnce() {
        Payment p = open(PaymentRail.PAYNOW_CARD, Instant.now().minusSeconds(600));
        p.setStatus(Payment.PaymentStatus.EXPIRED);
        when(repository.findByPaymentReference(REFERENCE)).thenReturn(Optional.of(p));
        when(client.verifiedUpdate(eq("USD"), any()))
                .thenReturn(Optional.of(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE)));
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE));
        when(records.flagPaynowOperatorCase(p.getId())).thenReturn(false);

        assertThat(service.onResultUpdate("reference=" + REFERENCE)).isEqualTo(UpdateAck.OK);
        verify(records, never()).noteEvent(any(), anyString());
        assertThat(resolutions("paid_after_close")).isZero();

        // Once flagged, an update is not even checked.
        p.setPaynowFlaggedAt(Instant.now());
        service.onResultUpdate("reference=" + REFERENCE);
        verify(client, times(1)).poll("USD", POLL);
    }

    @Test
    @DisplayName("a payment we closed unpaid is looked at again: paid late is an operator's case; anything else changes nothing")
    void recheckClosed() {
        Payment p = open(PaymentRail.PAYNOW_CARD, Instant.now().minus(Duration.ofMinutes(40)));
        p.setStatus(Payment.PaymentStatus.EXPIRED);
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.OPEN, "Sent", null, null));
        assertThat(service.recheckClosed(p)).isEqualTo(CheckOutcome.CLOSED);
        verify(records).stampPaynowChecked(p.getId());
        assertThat(resolutions("paid_after_close")).isZero();

        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE));
        assertThat(service.recheckClosed(p)).isEqualTo(CheckOutcome.PAID_LATE);
        verify(records).flagPaynowOperatorCase(p.getId());
        verify(records).noteEvent(eq(p.getId()), anyString());
        assertThat(resolutions("paid_after_close")).isEqualTo(1);
        verify(resolution, never()).confirmOrder(any());
        verify(records, never()).claimPaid(any(), any(), anyString());

        p.setPaynowFlaggedAt(Instant.now());
        service.recheckClosed(p);
        verify(client, times(2)).poll("USD", POLL);

        Payment q = open(PaymentRail.ONEMONEY, Instant.now().minus(Duration.ofMinutes(40)));
        q.setStatus(Payment.PaymentStatus.EXPIRED);
        q.setPaynowPollUrl("https://www.paynow.co.zw/Interface/CheckPayment/?guid=g2");
        when(client.poll("USD", q.getPaynowPollUrl())).thenThrow(new PaynowApiTransientException("down"));
        assertThat(service.recheckClosed(q)).isEqualTo(CheckOutcome.ERROR);
    }

    /** A card attempt we closed unpaid {@code closedAgo} ago, last looked at {@code checkedAgo} ago. */
    private static Payment closedCard(Duration closedAgo, Duration checkedAgo) {
        Payment p = open(PaymentRail.PAYNOW_CARD, Instant.now().minus(closedAgo).minus(Duration.ofMinutes(2)));
        p.setStatus(Payment.PaymentStatus.EXPIRED);
        p.setCompletedAt(Instant.now().minus(closedAgo));
        p.setPaynowCheckedAt(Instant.now().minus(checkedAgo));
        return p;
    }

    @Test
    @DisplayName("a customer reading a card attempt we closed: Paynow is asked once more (one read); paid late is the operator's case")
    void lookAgain_paidLate() {
        Payment p = closedCard(Duration.ofMinutes(1), Duration.ofMinutes(1));
        when(client.pollOnce("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE));

        assertThat(service.lookAgainBeforeAnswering(p)).isEqualTo(CheckOutcome.PAID_LATE);
        verify(records).stampPaynowChecked(p.getId());
        verify(records).flagPaynowOperatorCase(p.getId());
        assertThat(resolutions("paid_after_close")).isEqualTo(1);
        verify(client, never()).poll(anyString(), anyString());
        verify(resolution, never()).confirmOrder(any());
        verify(records, never()).claimPaid(any(), any(), anyString());
    }

    @Test
    @DisplayName("looked at and still unpaid: CLOSED, nothing raised")
    void lookAgain_stillUnpaid() {
        Payment p = closedCard(Duration.ofMinutes(3), Duration.ofSeconds(20));
        when(client.pollOnce("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.OPEN, "Sent", null, null));

        assertThat(service.lookAgainBeforeAnswering(p)).isEqualTo(CheckOutcome.CLOSED);
        verify(records, never()).flagPaynowOperatorCase(any());
        assertThat(resolutions("paid_after_close")).isZero();
    }

    @Test
    @DisplayName("no look when one was just made, the attempt is not a closed card one, it is an operator's case, or it closed over a day ago")
    void lookAgain_notDue() {
        assertThat(service.lookAgainBeforeAnswering(closedCard(Duration.ofMinutes(1), Duration.ofSeconds(5)))).isNull();

        Payment oneMoney = closedCard(Duration.ofMinutes(1), Duration.ofMinutes(1));
        oneMoney.setPaymentRail(PaymentRail.ONEMONEY);
        assertThat(service.lookAgainBeforeAnswering(oneMoney)).isNull();

        Payment flagged = closedCard(Duration.ofMinutes(1), Duration.ofMinutes(1));
        flagged.setPaynowFlaggedAt(Instant.now());
        assertThat(service.lookAgainBeforeAnswering(flagged)).isNull();

        assertThat(service.lookAgainBeforeAnswering(closedCard(Duration.ofHours(25), Duration.ofHours(4)))).isNull();

        Payment open = open(PaymentRail.PAYNOW_CARD, Instant.now().plusSeconds(600));
        assertThat(service.lookAgainBeforeAnswering(open)).isNull();
        assertThat(service.lookAgainBeforeAnswering(null)).isNull();

        verifyNoInteractions(client);
        verify(records, never()).stampPaynowChecked(any());
    }

    @Test
    @DisplayName("Paynow cannot be asked, or the ledger write fails: ERROR, never an exception to the customer")
    void lookAgain_failsQuietly() {
        Payment p = closedCard(Duration.ofMinutes(1), Duration.ofMinutes(1));
        when(client.pollOnce("USD", POLL)).thenThrow(new PaynowApiTransientException("down"));
        assertThat(service.lookAgainBeforeAnswering(p)).isEqualTo(CheckOutcome.ERROR);

        Payment q = closedCard(Duration.ofMinutes(1), Duration.ofMinutes(1));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(records).stampPaynowChecked(q.getId());
        assertThat(service.lookAgainBeforeAnswering(q)).isEqualTo(CheckOutcome.ERROR);
    }

    @Test
    @DisplayName("the sweep's queries: open payments due a check, closed ones due a late-payment look, cut-offs from the settings")
    void sweepQueries() {
        Instant now = Instant.parse("2026-11-25T10:00:00Z");
        service.openPaymentsDue(now, 100);
        verify(repository).findPaynowDue(Payment.PaymentStatus.TOKEN_ISSUED, PaynowPaymentService.RAILS,
                now.minusSeconds(30), now.minus(Duration.ofMinutes(2)), now.minus(Duration.ofMinutes(32)),
                now.minus(Duration.ofMinutes(2)), PageRequest.of(0, 100));

        service.closedPaymentsDue(now, 50);
        verify(repository).findPaynowClosedDue(Payment.PaymentStatus.EXPIRED, PaynowPaymentService.RAILS,
                now.minus(Duration.ofHours(24)), now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(15)),
                now.minus(Duration.ofHours(3)), PageRequest.of(0, 50));
        assertThat(service.sweepRunBudget()).isEqualTo(Duration.ofSeconds(25));
    }


    @Test
    @DisplayName("a card or O'mari start is 'in flight' while armed with no page or code yet and younger than the start's bound")
    void startInFlight() {
        Payment card = open(PaymentRail.PAYNOW_CARD, Instant.now().plusSeconds(1800));
        card.setPaynowPollUrl(null);
        card.setCreatedAt(Instant.now());
        assertThat(service.startInFlight(card)).isTrue();

        card.setPaynowBrowserUrl("https://www.paynow.co.zw/Payment/ConfirmPayment/9510");
        assertThat(service.startInFlight(card)).as("the page is there: AWAITING_PAYMENT, not in flight").isFalse();

        Payment omari = open(PaymentRail.OMARI, Instant.now().plusSeconds(600));
        omari.setCreatedAt(Instant.now());
        assertThat(service.startInFlight(omari)).isTrue();
        omari.setCreatedAt(Instant.now().minus(Duration.ofSeconds(60)));
        assertThat(service.startInFlight(omari)).as("older than connect + read + margin: a crash leftover").isFalse();

        Payment oneMoney = open(PaymentRail.ONEMONEY, Instant.now().plusSeconds(600));
        oneMoney.setCreatedAt(Instant.now());
        assertThat(service.startInFlight(oneMoney)).as("OneMoney has nothing to show: its prompt is on the phone").isFalse();
    }


    @Test
    @DisplayName("Refunded or Disputed on a payment still open: parked IN_DOUBT and raised once, like every operator case")
    void resolve_reversedWhileOpen() {
        Payment p = open(PaymentRail.ONEMONEY, Instant.now().plusSeconds(600));
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.REVERSED, "Refunded", "12.50", REFERENCE));

        assertThat(service.resolveOpen(p, Trigger.SWEEP)).isEqualTo(CheckOutcome.OPEN);
        verify(records).markInDoubt(eq(p.getId()), anyString());
        verify(records).flagPaynowOperatorCase(p.getId());
        verify(resolution, never()).confirmOrder(any());
        assertThat(resolutions("reversed_before_paid")).isEqualTo(1);
    }


    @Test
    @DisplayName("an accepted O'mari code whose one status read fails: 200 with the payment as it stands, the sweep confirms it")
    void otp_acceptedButTheReadFails() {
        Payment p = open(PaymentRail.OMARI, Instant.now().plusSeconds(600));
        p.setPaynowOtpUrl("https://www.paynow.co.zw/interface/remoteotp/9512");
        when(repository.findById(p.getId())).thenReturn(Optional.of(p));
        when(client.submitOtp("USD", p.getPaynowOtpUrl(), "482913"))
                .thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE));
        when(client.pollOnce("USD", POLL)).thenThrow(new PaynowApiTransientException("read timed out"));

        var outcome = service.submitOtp(p.getId(), "482913");

        assertThat(outcome.httpStatus()).isEqualTo(200);
        assertThat(outcome.payment().getStatus()).isEqualTo(Payment.PaymentStatus.TOKEN_ISSUED);
        verify(client, times(1)).pollOnce("USD", POLL);
        verify(client, never()).poll(anyString(), anyString());
        verify(resolution, never()).confirmOrder(any());
    }
}
