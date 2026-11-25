package innbucks.paymentservice.reconciliation;

import innbucks.paymentservice.client.EmailNotificationClient;
import innbucks.paymentservice.client.WhatsAppNotificationClient;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.service.PaymentRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * At-most-once + best-effort contract of the stuck-payment escalation:
 * marker claimed on the live row before the sends, operator email optional,
 * customer reassurance independent of the operator channel, and the copy the
 * retry read never written back.
 */
class UnconfirmedPaymentAlerterTest {

    private static final String OPS = "ops@innbucks.co.zw";

    private EmailNotificationClient email;
    private WhatsAppNotificationClient whatsApp;
    private PaymentRecordService records;

    @BeforeEach
    void setUp() {
        email = mock(EmailNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        records = mock(PaymentRecordService.class);
        when(records.claimStuckPaymentAlert(any())).thenReturn(true);
    }

    private UnconfirmedPaymentAlerter alerter(String operatorEmail) {
        return new UnconfirmedPaymentAlerter(email, whatsApp, records, operatorEmail);
    }

    private Payment stuckPayment() {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setPaymentReference("PAY-123");
        p.setBookingId(UUID.randomUUID());
        p.setAmount(new BigDecimal("25.00"));
        p.setCurrency("USD");
        p.setCustomerMsisdn("+263771234567");
        p.setStatus(Payment.PaymentStatus.COMPLETED_UNCONFIRMED);
        return p;
    }

    @Test
    void firstFailure_claimsTheLiveRow_thenAlertsOperatorAndCustomer() {
        Payment p = stuckPayment();

        alerter(OPS).onStillFailing(p, "booking-service 503");

        var order = inOrder(records, email, whatsApp);
        order.verify(records).claimStuckPaymentAlert(p.getId());
        order.verify(email).sendEmail(eq(OPS), contains("PAY-123"), contains("booking-service 503"),
                eq("PAY-OPS-" + p.getId()));
        order.verify(whatsApp).sendCustomNotification(eq("+263771234567"), contains("confirming your order manually"));
        // The copy is the alert's text only: never stamped, never written back.
        assertThat(p.getOperatorAlertedAt()).isNull();
    }

    @Test
    void rowMovedOnDuringTheConfirm_orAlertedElsewhere_isSilent() {
        // The claim lost: a Paynow refund parked the row IN_DOUBT (or another
        // trigger confirmed it) while the retry's confirm was in flight, or
        // another replica alerted first. Nothing is announced and nothing is
        // written from the stale copy.
        when(records.claimStuckPaymentAlert(any())).thenReturn(false);
        Payment p = stuckPayment();

        alerter(OPS).onStillFailing(p, "booking-service 503");

        verifyNoInteractions(email, whatsApp);
    }

    @Test
    void secondFailure_isSilent() {
        Payment p = stuckPayment();
        p.setOperatorAlertedAt(Instant.now());

        alerter(OPS).onStillFailing(p, "still down");

        verifyNoInteractions(email, whatsApp, records);
    }

    @Test
    void noOperatorEmailConfigured_customerStillReassured() {
        Payment p = stuckPayment();

        alerter("").onStillFailing(p, "boom");

        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
        verify(whatsApp).sendCustomNotification(eq("+263771234567"), anyString());
        verify(records).claimStuckPaymentAlert(p.getId());
    }

    @Test
    void emailFailure_doesNotBlockCustomerReassurance_orThrow() {
        Payment p = stuckPayment();
        doThrow(new RuntimeException("smtp down"))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> alerter(OPS).onStillFailing(p, "boom")).doesNotThrowAnyException();

        verify(whatsApp).sendCustomNotification(eq("+263771234567"), anyString());
        verify(records).claimStuckPaymentAlert(p.getId());
    }

    @Test
    void customerWithoutMsisdn_operatorStillAlerted() {
        Payment p = stuckPayment();
        p.setCustomerMsisdn(null);

        alerter(OPS).onStillFailing(p, "boom");

        verify(email).sendEmail(eq(OPS), anyString(), anyString(), anyString());
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
    }
}
