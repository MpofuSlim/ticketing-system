package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.LoyaltyServiceClient;
import innbucks.paymentservice.client.ZimswitchProperties;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.PaymentRequest;
import innbucks.paymentservice.dto.PaymentResponse;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.PaymentRail;
import innbucks.paymentservice.order.OrderType;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.service.EcocashPaymentService;
import innbucks.paymentservice.service.EcocashPaymentService.EcocashDeclinedException;
import innbucks.paymentservice.service.InnbucksPaymentService;
import innbucks.paymentservice.service.PaymentRecordService;
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
import static org.mockito.Mockito.when;

/**
 * An EcoCash decline reaches the customer ONCE, with EcoCash's own reason:
 * a repeat {@code POST /payments} inside the hold answers with that decline
 * instead of charging (and texting) again, and {@code GET /payments/{id}}
 * tells a poller how the attempt ended.
 */
class PaymentDeclineAndStatusTest {

    private static final String REASON = "You have insufficient funds to complete this transaction.";

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final EcocashPaymentService ecocash = mock(EcocashPaymentService.class);
    private final PaymentController controller = new PaymentController(
            mock(LoyaltyServiceClient.class), new PaymentMetrics(new SimpleMeterRegistry()),
            mock(InnbucksPaymentService.class), mock(ZimswitchCardPaymentService.class),
            new ZimswitchProperties(), ecocash, mock(PaymentRecordService.class), payments);

    {
        ReflectionTestUtils.setField(controller, "cellCurrency", "USD");
        when(payments.findFirstByOrderTypeAndOrderRefAndStatusInOrderByCreatedAtDesc(any(), anyString(), any()))
                .thenReturn(Optional.empty());
    }

    private static Payment row(UUID bookingId, Payment.PaymentStatus status, String code, String message) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKZ-PETERHOUSESI-ECE9C3700A7F")
                .paymentRail(PaymentRail.ECOCASH)
                .orderType(OrderType.BOOKING)
                .orderRef(bookingId.toString())
                .bookingId(bookingId)
                .status(status)
                .upstreamErrorCode(code)
                .upstreamErrorMessage(message)
                .completedAt(Instant.now())
                .customerMsisdn("+263777000000")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .build();
    }

    private static PaymentRequest ecocashFor(UUID bookingId) {
        PaymentRequest r = new PaymentRequest();
        r.setBookingId(bookingId);
        r.setPaymentRail(PaymentRail.ECOCASH);
        return r;
    }

    @Test
    @DisplayName("POST inside the hold: 400 with EcoCash's reason, the declined attempt, and the wait — no charge")
    void repeatPostAfterADecline_answersWithTheDecline() {
        UUID bookingId = UUID.randomUUID();
        Payment declined = row(bookingId, Payment.PaymentStatus.FAILED, "subscriber_rejected", REASON);
        when(ecocash.startCharge(eq(OrderType.BOOKING), eq(bookingId.toString())))
                .thenThrow(new EcocashDeclinedException(declined, REASON, 104));

        ResponseEntity<ApiResult<PaymentResponse>> resp = controller.processPayment(ecocashFor(bookingId));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getMessage()).isEqualTo("EcoCash: " + REASON
                + " Try again in 2 minutes, or pay with an InnBucks code.");
        PaymentResponse data = resp.getBody().getData();
        assertThat(data.getTransactionId()).isEqualTo(declined.getId());
        assertThat(data.getStatus()).isEqualTo(PaymentResponse.Status.FAILED);
        assertThat(data.getStage()).isEqualTo(PaymentResponse.Stage.DECLINED);
        assertThat(data.getFundsCaptured()).isFalse();
        assertThat(data.getFailureReason()).isEqualTo(REASON);
        assertThat(data.getRetryAfterSeconds()).isEqualTo(104L);
        assertThat(data.getPaymentRail()).isEqualTo(PaymentRail.ECOCASH);
    }

    @Test
    @DisplayName("GET a declined attempt: stage DECLINED, EcoCash's reason, and the hold left")
    void getDeclined() {
        Payment declined = row(UUID.randomUUID(), Payment.PaymentStatus.FAILED, "subscriber_rejected", REASON);
        when(payments.findById(declined.getId())).thenReturn(Optional.of(declined));
        when(ecocash.declineHoldRemaining(declined)).thenReturn(90L);

        ResponseEntity<ApiResult<PaymentResponse>> resp = controller.getPayment(declined.getId().toString());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getMessage()).isEqualTo("EcoCash: " + REASON);
        PaymentResponse data = resp.getBody().getData();
        assertThat(data.getStage()).isEqualTo(PaymentResponse.Stage.DECLINED);
        assertThat(data.getStatus()).isEqualTo(PaymentResponse.Status.FAILED);
        assertThat(data.getFundsCaptured()).isFalse();
        assertThat(data.getFailureReason()).isEqualTo(REASON);
        assertThat(data.getRetryAfterSeconds()).isEqualTo(90L);
    }

    @Test
    @DisplayName("GET a failure that was not EcoCash's decline: DECLINED, but no reason of ours shown as EcoCash's")
    void getOtherFailure_hasNoReason() {
        Payment refused = row(UUID.randomUUID(), Payment.PaymentStatus.FAILED, "ecocash_refused", "400 Bad Request");
        when(payments.findById(refused.getId())).thenReturn(Optional.of(refused));
        when(ecocash.declineHoldRemaining(refused)).thenReturn(null);   // the real rule: not EcoCash's decline

        PaymentResponse data = controller.getPayment(refused.getId().toString()).getBody().getData();

        assertThat(data.getStage()).isEqualTo(PaymentResponse.Stage.DECLINED);
        assertThat(data.getFailureReason()).isNull();
        assertThat(data.getRetryAfterSeconds()).isNull();
    }

    @Test
    @DisplayName("GET a prompt that lapsed unpaid: stage LAPSED, nothing captured")
    void getLapsed() {
        Payment expired = row(UUID.randomUUID(), Payment.PaymentStatus.EXPIRED, null, null);
        when(payments.findById(expired.getId())).thenReturn(Optional.of(expired));

        PaymentResponse data = controller.getPayment(expired.getId().toString()).getBody().getData();

        assertThat(data.getStage()).isEqualTo(PaymentResponse.Stage.LAPSED);
        assertThat(data.getStatus()).isEqualTo(PaymentResponse.Status.FAILED);
        assertThat(data.getFundsCaptured()).isFalse();
    }

    @Test
    @DisplayName("GET an open EcoCash prompt: AWAITING_PAYMENT with its deadline, as POST replays it")
    void getOpenPrompt() {
        Payment open = row(UUID.randomUUID(), Payment.PaymentStatus.TOKEN_ISSUED, null, null);
        open.setCompletedAt(null);
        open.setEcocashClientCorrelator("1793198767975175244");
        open.setCodeExpiresAt(Instant.now().plusSeconds(240));
        when(payments.findById(open.getId())).thenReturn(Optional.of(open));

        PaymentResponse data = controller.getPayment(open.getId().toString()).getBody().getData();

        assertThat(data.getStage()).isEqualTo(PaymentResponse.Stage.AWAITING_PAYMENT);
        assertThat(data.getPromptExpiresAt()).isNotNull();
        assertThat(data.getFailureReason()).isNull();
    }

    @Test
    @DisplayName("GET an unknown or malformed id: 404, no data")
    void getUnknown() {
        UUID id = UUID.randomUUID();
        when(payments.findById(id)).thenReturn(Optional.empty());

        assertThat(controller.getPayment(id.toString()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.getPayment("not-a-uuid").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.getPayment("not-a-uuid").getBody().getData()).isNull();
    }
}
