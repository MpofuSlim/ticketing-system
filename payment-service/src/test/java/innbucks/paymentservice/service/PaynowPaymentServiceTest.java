package innbucks.paymentservice.service;

import innbucks.paymentservice.client.PaynowApiException;
import innbucks.paymentservice.client.PaynowApiTransientException;
import innbucks.paymentservice.client.PaynowClient;
import innbucks.paymentservice.client.PaynowInitiation;
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
        assertThat(returnUrl.getValue()).isEqualTo("https://ticketize.example.co.zw/checkout/paynow-return?transactionId="
                + draft.getValue().getId());
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
        payableBooking("+263774444444");
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
        payableBooking("+263771111111");
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
        OrderGateway gateway = payableBooking("+263771111111");
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
        verify(records).markSucceeded(p.getId(), "123456", "INN-20261125-941C78");
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
        when(client.poll("USD", POLL)).thenReturn(status(PaynowStatus.Outcome.PAID, "Paid", "12.50", REFERENCE));
        when(resolution.confirmOrder(p)).thenReturn(ConfirmOutcome.confirmed("INN-1"));
        assertThat(service.submitOtp(p.getId(), " 482914 ").httpStatus()).isEqualTo(200);
        verify(client).poll("USD", POLL);
        verify(records).markSucceeded(p.getId(), "123456", "INN-1");
    }

    @Test
    @DisplayName("O'mari code on another rail is a 404; on a payment no longer waiting, a 409")
    void otp_wrongRailOrClosed() {
        Payment card = open(PaymentRail.PAYNOW_CARD, Instant.now().plusSeconds(600));
        when(repository.findById(card.getId())).thenReturn(Optional.of(card));
        assertThat(service.submitOtp(card.getId(), "482913").httpStatus()).isEqualTo(404);

        Payment done = open(PaymentRail.OMARI, Instant.now().plusSeconds(600));
        done.setPaynowOtpUrl("https://www.paynow.co.zw/interface/remoteotp/1");
        done.setStatus(Payment.PaymentStatus.SUCCEEDED);
        when(repository.findById(done.getId())).thenReturn(Optional.of(done));
        assertThat(service.submitOtp(done.getId(), "482913").httpStatus()).isEqualTo(409);
        verify(client, never()).submitOtp(anyString(), anyString(), anyString());
    }
}
