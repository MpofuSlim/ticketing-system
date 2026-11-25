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
}
