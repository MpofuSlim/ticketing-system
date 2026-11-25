package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.LoyaltyServiceClient;
import innbucks.paymentservice.client.ZimswitchProperties;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.PaymentOtpRequest;
import innbucks.paymentservice.dto.PaymentRequest;
import innbucks.paymentservice.dto.PaymentResponse;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.PaymentRail;
import innbucks.paymentservice.order.OrderType;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.service.EcocashPaymentService;
import innbucks.paymentservice.service.InnbucksPaymentService;
import innbucks.paymentservice.service.PaymentRecordService;
import innbucks.paymentservice.service.PaynowPaymentService;
import innbucks.paymentservice.service.ZimswitchCardPaymentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Paynow options on {@code POST /payments}, their replay, and
 * {@code POST /payments/{transactionId}/otp}: what each tells the website.
 */
class PaynowPaymentControllerTest {

    private static final String PAGE = "https://www.paynow.co.zw/Payment/ConfirmPayment/9510";

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaynowPaymentService paynow = mock(PaynowPaymentService.class);
    private final PaymentController controller = new PaymentController(
            mock(LoyaltyServiceClient.class), new PaymentMetrics(new SimpleMeterRegistry()),
            mock(InnbucksPaymentService.class), mock(ZimswitchCardPaymentService.class),
            new ZimswitchProperties(), mock(EcocashPaymentService.class), mock(PaymentRecordService.class), payments);

    {
        ReflectionTestUtils.setField(controller, "cellCurrency", "USD");
        controller.setPaynowPaymentService(paynow);
        when(payments.findFirstByOrderTypeAndOrderRefAndStatusInOrderByCreatedAtDesc(any(), anyString(), any()))
                .thenReturn(Optional.empty());
    }

    private static Payment row(PaymentRail rail, UUID bookingId) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKZ-AMA2KFESTIVA-CF604A7376B7")
                .paymentRail(rail)
                .orderType(OrderType.BOOKING)
                .orderRef(bookingId.toString())
                .bookingId(bookingId)
                .status(Payment.PaymentStatus.TOKEN_ISSUED)
                .paynowTrace("0123456789abcdef0123456789abcdef")
                .customerMsisdn("+263771111111")
                .amount(new BigDecimal("12.50"))
                .currency("USD")
                .codeExpiresAt(Instant.now().plusSeconds(1800))
                .createdAt(Instant.now())
                .build();
    }

    private static PaymentRequest request(UUID bookingId, PaymentRail rail) {
        PaymentRequest r = new PaymentRequest();
        r.setBookingId(bookingId);
        r.setPaymentRail(rail);
        return r;
    }

    @Test
    @DisplayName("PAYNOW_CARD: AWAITING_PAYMENT with Paynow's page in redirectUrl, no InnBucks code fields")
    void card_redirect() {
        UUID bookingId = UUID.randomUUID();
        Payment p = row(PaymentRail.PAYNOW_CARD, bookingId);
        when(paynow.start(PaymentRail.PAYNOW_CARD, OrderType.BOOKING, bookingId.toString()))
                .thenReturn(new PaynowPaymentService.StartOutcome(p, false, 0, null, PAGE, null, p.getCodeExpiresAt()));

        ResponseEntity<ApiResult<PaymentResponse>> resp = controller.processPayment(request(bookingId, PaymentRail.PAYNOW_CARD));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getMessage()).isEqualTo("Pay on the Paynow page to complete your booking");
        PaymentResponse data = resp.getBody().getData();
        assertThat(data.getPaymentRail()).isEqualTo(PaymentRail.PAYNOW_CARD);
        assertThat(data.getStage()).isEqualTo(PaymentResponse.Stage.AWAITING_PAYMENT);
        assertThat(data.getRedirectUrl()).isEqualTo(PAGE);
        assertThat(data.getCheckoutExpiresAt()).isNotNull();
        assertThat(data.getPaymentCode()).isNull();
        assertThat(data.getPromptExpiresAt()).isNull();
    }

    @Test
    @DisplayName("a refused start answers with its status and message, data null")
    void refusedStart() {
        UUID bookingId = UUID.randomUUID();
        when(paynow.start(PaymentRail.ONEMONEY, OrderType.BOOKING, bookingId.toString()))
                .thenReturn(new PaynowPaymentService.StartOutcome(row(PaymentRail.ONEMONEY, bookingId), true, 400,
                        "OneMoney: Insufficient balance.", null, null, null));

        ResponseEntity<ApiResult<PaymentResponse>> resp = controller.processPayment(request(bookingId, PaymentRail.ONEMONEY));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getMessage()).isEqualTo("OneMoney: Insufficient balance.");
        assertThat(resp.getBody().getData()).isNull();
    }

    @Test
    @DisplayName("a re-POST on an open O'mari payment checks it, then replays it with the code reference")
    void omari_replay() {
        UUID bookingId = UUID.randomUUID();
        Payment p = row(PaymentRail.OMARI, bookingId);
        p.setPaynowOtpUrl("https://www.paynow.co.zw/interface/remoteotp/1");
        p.setPaynowOtpReference("OTP-7731");
        when(payments.findFirstByOrderTypeAndOrderRefAndStatusInOrderByCreatedAtDesc(eq(OrderType.BOOKING),
                eq(bookingId.toString()), any())).thenReturn(Optional.of(p));
        when(payments.findById(p.getId())).thenReturn(Optional.of(p));
        when(paynow.resolveOpen(p, PaynowPaymentService.Trigger.CUSTOMER))
                .thenReturn(PaynowPaymentService.CheckOutcome.OPEN);

        ResponseEntity<ApiResult<PaymentResponse>> resp = controller.processPayment(request(bookingId, PaymentRail.OMARI));

        PaymentResponse data = resp.getBody().getData();
        assertThat(data.getStage()).isEqualTo(PaymentResponse.Stage.AWAITING_PAYMENT);
        assertThat(data.getOtpReference()).isEqualTo("OTP-7731");
        assertThat(data.getPromptExpiresAt()).isNotNull();
        assertThat(resp.getBody().getMessage()).isEqualTo("Enter the code O'mari sent to your phone to complete your booking");
        verify(paynow, never()).start(any(), any(), anyString());
    }

    @Test
    @DisplayName("OTP: a refusal is its status and message; an accepted code answers with the payment")
    void otp() {
        Payment p = row(PaymentRail.OMARI, UUID.randomUUID());
        when(paynow.submitOtp(p.getId(), "111"))
                .thenReturn(new PaynowPaymentService.OtpOutcome(p, 400, "Enter the code from the SMS O'mari sent you"));
        ResponseEntity<ApiResult<PaymentResponse>> refused =
                controller.submitOtp(p.getId().toString(), new PaymentOtpRequest("111"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().getMessage()).isEqualTo("Enter the code from the SMS O'mari sent you");

        Payment paid = row(PaymentRail.OMARI, UUID.randomUUID());
        paid.setStatus(Payment.PaymentStatus.SUCCEEDED);
        paid.setConfirmationNumber("INN-1");
        when(paynow.submitOtp(paid.getId(), "482913")).thenReturn(new PaynowPaymentService.OtpOutcome(paid, 200, null));
        ResponseEntity<ApiResult<PaymentResponse>> ok =
                controller.submitOtp(paid.getId().toString(), new PaymentOtpRequest("482913"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().getData().getStage()).isEqualTo(PaymentResponse.Stage.COMPLETED);

        assertThat(controller.submitOtp("not-a-uuid", new PaymentOtpRequest("482913")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Never sent: a 503 the checkout can tell apart from the 502 "wait".
        Payment notSent = row(PaymentRail.OMARI, UUID.randomUUID());
        when(paynow.submitOtp(notSent.getId(), "482915")).thenReturn(new PaynowPaymentService.OtpOutcome(notSent, 503,
                "We could not reach O'mari, so your code was not sent. Enter it again in a moment."));
        ResponseEntity<ApiResult<PaymentResponse>> again =
                controller.submitOtp(notSent.getId().toString(), new PaymentOtpRequest("482915"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(again.getBody().getCode()).isEqualTo("503 SERVICE_UNAVAILABLE");
        assertThat(again.getBody().getMessage())
                .isEqualTo("We could not reach O'mari, so your code was not sent. Enter it again in a moment.");
        assertThat(again.getBody().getData()).isNull();
    }

    @Test
    @DisplayName("a Paynow wallet decline reads with its method's name, not EcoCash's")
    void declineLabel() {
        Payment p = row(PaymentRail.ONEMONEY, UUID.randomUUID());
        p.setStatus(Payment.PaymentStatus.FAILED);
        p.setUpstreamErrorCode(PaynowPaymentService.DECLINED_CODE);
        p.setUpstreamErrorMessage("Insufficient balance");
        when(payments.findById(p.getId())).thenReturn(Optional.of(p));

        ResponseEntity<ApiResult<PaymentResponse>> resp = controller.getPayment(p.getId().toString());

        assertThat(resp.getBody().getMessage()).isEqualTo("OneMoney: Insufficient balance");
        assertThat(resp.getBody().getData().getFailureReason()).isEqualTo("Insufficient balance");
        assertThat(resp.getBody().getData().getStage()).isEqualTo(PaymentResponse.Stage.DECLINED);
    }

    @Test
    @DisplayName("a code sent again for a paid O'mari payment answers with the receipt; one that is over says how it ended")
    void otp_paidOrOver() {
        Payment paid = row(PaymentRail.OMARI, UUID.randomUUID());
        paid.setStatus(Payment.PaymentStatus.SUCCEEDED);
        paid.setConfirmationNumber("INN-20261125-941C78");
        when(paynow.submitOtp(paid.getId(), "482913")).thenReturn(new PaynowPaymentService.OtpOutcome(paid, 200, null));
        ResponseEntity<ApiResult<PaymentResponse>> again =
                controller.submitOtp(paid.getId().toString(), new PaymentOtpRequest("482913"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().getData().getStage()).isEqualTo(PaymentResponse.Stage.COMPLETED);
        assertThat(again.getBody().getData().getConfirmationNumber()).isEqualTo("INN-20261125-941C78");

        Payment declined = row(PaymentRail.OMARI, UUID.randomUUID());
        declined.setStatus(Payment.PaymentStatus.FAILED);
        declined.setUpstreamErrorCode(PaynowPaymentService.CANCELLED_CODE);
        when(paynow.submitOtp(declined.getId(), "482913")).thenReturn(new PaynowPaymentService.OtpOutcome(declined, 409,
                "This payment is no longer waiting for a code — start a new payment"));
        ResponseEntity<ApiResult<PaymentResponse>> over =
                controller.submitOtp(declined.getId().toString(), new PaymentOtpRequest("482913"));
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(over.getBody().getData().getStage()).isEqualTo(PaymentResponse.Stage.DECLINED);
        assertThat(over.getBody().getData().getFundsCaptured()).isFalse();
    }

    @Test
    @DisplayName("a re-POST after the customer cancelled the OneMoney prompt: 400 with that decline, never a new prompt")
    void cancelledThenRePosted_answersTheDecline() {
        UUID bookingId = UUID.randomUUID();
        Payment open = row(PaymentRail.ONEMONEY, bookingId);
        when(payments.findFirstByOrderTypeAndOrderRefAndStatusInOrderByCreatedAtDesc(eq(OrderType.BOOKING),
                eq(bookingId.toString()), any())).thenReturn(Optional.of(open));
        when(paynow.resolveOpen(open, PaynowPaymentService.Trigger.CUSTOMER))
                .thenReturn(PaynowPaymentService.CheckOutcome.CLOSED);
        Payment cancelled = row(PaymentRail.ONEMONEY, bookingId);
        cancelled.setId(open.getId());
        cancelled.setStatus(Payment.PaymentStatus.FAILED);
        cancelled.setUpstreamErrorCode(PaynowPaymentService.CANCELLED_CODE);
        cancelled.setCompletedAt(Instant.now());
        when(paynow.start(PaymentRail.ONEMONEY, OrderType.BOOKING, bookingId.toString()))
                .thenThrow(new PaynowPaymentService.PaynowDeclinedException(cancelled, 118));

        ResponseEntity<ApiResult<PaymentResponse>> resp = controller.processPayment(request(bookingId, PaymentRail.ONEMONEY));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getMessage())
                .isEqualTo("OneMoney: the payment didn't go through. Try again in 2 minutes, or pay another way.");
        PaymentResponse data = resp.getBody().getData();
        assertThat(data.getStage()).isEqualTo(PaymentResponse.Stage.DECLINED);
        assertThat(data.getTransactionId()).isEqualTo(open.getId());
        assertThat(data.getRetryAfterSeconds()).isEqualTo(118L);
    }

    @Test
    @DisplayName("GET of a held OneMoney decline carries retryAfterSeconds from the Paynow hold")
    void getPayment_heldDecline() {
        Payment p = row(PaymentRail.ONEMONEY, UUID.randomUUID());
        p.setStatus(Payment.PaymentStatus.FAILED);
        p.setUpstreamErrorCode(PaynowPaymentService.CANCELLED_CODE);
        when(payments.findById(p.getId())).thenReturn(Optional.of(p));
        when(paynow.declineHoldRemaining(p)).thenReturn(90L);

        ResponseEntity<ApiResult<PaymentResponse>> resp = controller.getPayment(p.getId().toString());

        assertThat(resp.getBody().getData().getStage()).isEqualTo(PaymentResponse.Stage.DECLINED);
        assertThat(resp.getBody().getData().getRetryAfterSeconds()).isEqualTo(90L);
    }

    @Test
    @DisplayName("a card attempt we closed that Paynow then reported paid: VERIFYING and 'don't pay again', never 'nothing was charged'")
    void getPayment_paidAfterClose() {
        Payment p = row(PaymentRail.PAYNOW_CARD, UUID.randomUUID());
        p.setStatus(Payment.PaymentStatus.EXPIRED);
        p.setPaynowFlaggedAt(Instant.now());
        when(payments.findById(p.getId())).thenReturn(Optional.of(p));

        ResponseEntity<ApiResult<PaymentResponse>> resp = controller.getPayment(p.getId().toString());

        PaymentResponse data = resp.getBody().getData();
        assertThat(data.getStage()).isEqualTo(PaymentResponse.Stage.VERIFYING);
        assertThat(data.getFundsCaptured()).isNull();
        assertThat(data.getStatus()).isEqualTo(PaymentResponse.Status.PROCESSING);
        assertThat(resp.getBody().getMessage())
                .isEqualTo("We received your payment after this attempt had timed out. Don't pay again: support will "
                        + "confirm your booking or refund you.")
                .doesNotContain("Nothing was charged");

        Payment lapsed = row(PaymentRail.PAYNOW_CARD, UUID.randomUUID());
        lapsed.setStatus(Payment.PaymentStatus.EXPIRED);
        when(payments.findById(lapsed.getId())).thenReturn(Optional.of(lapsed));
        assertThat(controller.getPayment(lapsed.getId().toString()).getBody().getData().getStage())
                .isEqualTo(PaymentResponse.Stage.LAPSED);
    }

    @Test
    @DisplayName("an order Paynow reported paid after we closed its attempt: every later POST answers that attempt "
            + "(VERIFYING, don't pay again) and starts nothing, on any rail")
    void paidAfterClose_aLaterPostStartsNoSecondPayment() {
        InnbucksPaymentService innbucks = mock(InnbucksPaymentService.class);
        ZimswitchCardPaymentService zimswitch = mock(ZimswitchCardPaymentService.class);
        EcocashPaymentService ecocash = mock(EcocashPaymentService.class);
        PaymentController guarded = new PaymentController(mock(LoyaltyServiceClient.class),
                new PaymentMetrics(new SimpleMeterRegistry()), innbucks, zimswitch, new ZimswitchProperties(),
                ecocash, mock(PaymentRecordService.class), payments);
        ReflectionTestUtils.setField(guarded, "cellCurrency", "USD");
        guarded.setPaynowPaymentService(paynow);

        UUID bookingId = UUID.randomUUID();
        Payment late = row(PaymentRail.PAYNOW_CARD, bookingId);
        late.setStatus(Payment.PaymentStatus.EXPIRED);
        late.setPaynowFlaggedAt(Instant.now());
        when(payments.findFirstByOrderTypeAndOrderRefAndPaynowFlaggedAtIsNotNullAndStatusInOrderByCreatedAtDesc(
                eq(OrderType.BOOKING), eq(bookingId.toString()), eq(PaynowPaymentService.CLOSED_UNPAID)))
                .thenReturn(Optional.of(late));

        // null = the default InnBucks code.
        for (PaymentRail rail : new PaymentRail[] {PaymentRail.ONEMONEY, PaymentRail.OMARI, PaymentRail.PAYNOW_CARD,
                PaymentRail.ECOCASH, PaymentRail.ZIMSWITCH_CARD, null}) {
            ResponseEntity<ApiResult<PaymentResponse>> resp = guarded.processPayment(request(bookingId, rail));

            assertThat(resp.getStatusCode()).as("rail %s", rail).isEqualTo(HttpStatus.OK);
            PaymentResponse data = resp.getBody().getData();
            assertThat(data.getStage()).as("rail %s", rail).isEqualTo(PaymentResponse.Stage.VERIFYING);
            assertThat(data.getTransactionId()).as("rail %s", rail).isEqualTo(late.getId());
            assertThat(data.getFundsCaptured()).as("rail %s", rail).isNull();
            assertThat(resp.getBody().getMessage()).as("rail %s", rail)
                    .isEqualTo("We received your payment after this attempt had timed out. Don't pay again: support "
                            + "will confirm your booking or refund you.");
        }
        verify(paynow, never()).start(any(), any(), anyString());
        verify(innbucks, never()).processPayment(any(), any(), any(), any());
        verify(ecocash, never()).startCharge(any(), any());
        verify(zimswitch, never()).startCheckout(any(), any());
    }

    @Test
    @DisplayName("GET of a card attempt we closed looks at Paynow once more first: paid late reads VERIFYING, "
            + "never 'nothing was charged'")
    void getPayment_closedCard_isLookedAtBeforeAnswering() {
        Payment closed = row(PaymentRail.PAYNOW_CARD, UUID.randomUUID());
        closed.setStatus(Payment.PaymentStatus.EXPIRED);
        Payment flagged = row(PaymentRail.PAYNOW_CARD, UUID.fromString(closed.getOrderRef()));
        flagged.setId(closed.getId());
        flagged.setStatus(Payment.PaymentStatus.EXPIRED);
        flagged.setPaynowFlaggedAt(Instant.now());
        when(payments.findById(closed.getId())).thenReturn(Optional.of(closed), Optional.of(flagged));
        when(paynow.lookAgainBeforeAnswering(closed)).thenReturn(PaynowPaymentService.CheckOutcome.PAID_LATE);

        ResponseEntity<ApiResult<PaymentResponse>> resp = controller.getPayment(closed.getId().toString());

        PaymentResponse data = resp.getBody().getData();
        assertThat(data.getStage()).isEqualTo(PaymentResponse.Stage.VERIFYING);
        assertThat(data.getFundsCaptured()).isNull();
        assertThat(resp.getBody().getMessage()).doesNotContain("Nothing was charged");

        // No look due: answered from the row as it is.
        Payment other = row(PaymentRail.PAYNOW_CARD, UUID.randomUUID());
        other.setStatus(Payment.PaymentStatus.EXPIRED);
        when(payments.findById(other.getId())).thenReturn(Optional.of(other));
        assertThat(controller.getPayment(other.getId().toString()).getBody().getData().getStage())
                .isEqualTo(PaymentResponse.Stage.LAPSED);
    }

    @Test
    @DisplayName("before a new payment starts, the order's last card attempt we closed is looked at once more: paid "
            + "late answers it (VERIFYING) and starts nothing")
    void post_looksAtTheClosedCardAttemptFirst() {
        UUID bookingId = UUID.randomUUID();
        Payment closed = row(PaymentRail.PAYNOW_CARD, bookingId);
        closed.setStatus(Payment.PaymentStatus.EXPIRED);
        when(payments.findFirstByOrderTypeAndOrderRefAndPaymentRailOrderByCreatedAtDesc(OrderType.BOOKING,
                bookingId.toString(), PaymentRail.PAYNOW_CARD)).thenReturn(Optional.of(closed));
        Payment flagged = row(PaymentRail.PAYNOW_CARD, bookingId);
        flagged.setId(closed.getId());
        flagged.setStatus(Payment.PaymentStatus.EXPIRED);
        flagged.setPaynowFlaggedAt(Instant.now());
        when(paynow.lookAgainBeforeAnswering(closed)).thenReturn(PaynowPaymentService.CheckOutcome.PAID_LATE);
        when(payments.findFirstByOrderTypeAndOrderRefAndPaynowFlaggedAtIsNotNullAndStatusInOrderByCreatedAtDesc(
                eq(OrderType.BOOKING), eq(bookingId.toString()), any())).thenReturn(Optional.of(flagged));

        ResponseEntity<ApiResult<PaymentResponse>> resp = controller.processPayment(request(bookingId, PaymentRail.ONEMONEY));

        assertThat(resp.getBody().getData().getStage()).isEqualTo(PaymentResponse.Stage.VERIFYING);
        assertThat(resp.getBody().getData().getTransactionId()).isEqualTo(closed.getId());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(paynow, payments);
        order.verify(paynow).lookAgainBeforeAnswering(closed);
        order.verify(payments).findFirstByOrderTypeAndOrderRefAndPaynowFlaggedAtIsNotNullAndStatusInOrderByCreatedAtDesc(
                eq(OrderType.BOOKING), eq(bookingId.toString()), any());
        verify(paynow, never()).start(any(), any(), anyString());
    }

    @Test
    @DisplayName("the re-POST's own check finds Paynow paid on a row another trigger had just closed: it answers "
            + "that row (VERIFYING), never a new payment")
    void paidLate_onTheReposts_ownCheck_answersTheRow() {
        UUID bookingId = UUID.randomUUID();
        Payment open = row(PaymentRail.PAYNOW_CARD, bookingId);
        open.setPaynowBrowserUrl(PAGE);
        when(payments.findFirstByOrderTypeAndOrderRefAndStatusInOrderByCreatedAtDesc(eq(OrderType.BOOKING),
                eq(bookingId.toString()), any())).thenReturn(Optional.of(open));
        when(paynow.resolveOpen(open, PaynowPaymentService.Trigger.CUSTOMER))
                .thenReturn(PaynowPaymentService.CheckOutcome.PAID_LATE);
        Payment flagged = row(PaymentRail.PAYNOW_CARD, bookingId);
        flagged.setId(open.getId());
        flagged.setStatus(Payment.PaymentStatus.EXPIRED);
        flagged.setPaynowFlaggedAt(Instant.now());
        when(payments.findById(open.getId())).thenReturn(Optional.of(flagged));

        ResponseEntity<ApiResult<PaymentResponse>> resp = controller.processPayment(request(bookingId, PaymentRail.ONEMONEY));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getData().getStage()).isEqualTo(PaymentResponse.Stage.VERIFYING);
        assertThat(resp.getBody().getData().getTransactionId()).isEqualTo(open.getId());
        assertThat(resp.getBody().getData().getRedirectUrl()).isNull();
        verify(paynow, never()).start(any(), any(), anyString());
    }


    @Test
    @DisplayName("a second tap while the card start waits on Paynow: IN_PROGRESS (retry in a moment), never 'expired', no status check")
    void startInFlight_isInProgress() {
        UUID bookingId = UUID.randomUUID();
        Payment armed = row(PaymentRail.PAYNOW_CARD, bookingId);
        when(payments.findFirstByOrderTypeAndOrderRefAndStatusInOrderByCreatedAtDesc(eq(OrderType.BOOKING),
                eq(bookingId.toString()), any())).thenReturn(Optional.of(armed));
        when(payments.findById(armed.getId())).thenReturn(Optional.of(armed));
        when(paynow.startInFlight(armed)).thenReturn(true);

        ResponseEntity<ApiResult<PaymentResponse>> resp = controller.processPayment(request(bookingId, PaymentRail.PAYNOW_CARD));

        PaymentResponse data = resp.getBody().getData();
        assertThat(data.getStage()).isEqualTo(PaymentResponse.Stage.IN_PROGRESS);
        assertThat(data.getFundsCaptured()).isFalse();
        assertThat(data.getRedirectUrl()).isNull();
        assertThat(resp.getBody().getMessage()).isEqualTo("Your payment is already being processed — please retry in a moment");
        verify(paynow, never()).resolveOpen(any(), any());
        verify(paynow, never()).start(any(), any(), anyString());

        // Once it is no longer in flight (a crash leftover past the bound), the old answer stands.
        when(paynow.startInFlight(armed)).thenReturn(false);
        assertThat(controller.getPayment(armed.getId().toString()).getBody().getData().getStage())
                .isEqualTo(PaymentResponse.Stage.INSTRUMENT_EXPIRED);
    }
}
