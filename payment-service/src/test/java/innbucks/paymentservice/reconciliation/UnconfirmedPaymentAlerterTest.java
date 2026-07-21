package innbucks.paymentservice.reconciliation;

import innbucks.paymentservice.client.EmailNotificationClient;
import innbucks.paymentservice.client.WhatsAppNotificationClient;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * At-most-once + best-effort contract of the stuck-payment escalation:
 * marker stamped before the sends, operator email optional, customer
 * reassurance independent of the operator channel.
 */
class UnconfirmedPaymentAlerterTest {

    private static final String OPS = "ops@innbucks.co.zw";

    private EmailNotificationClient email;
    private WhatsAppNotificationClient whatsApp;
    private PaymentRepository payments;

    @BeforeEach
    void setUp() {
        email = mock(EmailNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        payments = mock(PaymentRepository.class);
    }

    private UnconfirmedPaymentAlerter alerter(String operatorEmail) {
        return new UnconfirmedPaymentAlerter(email, whatsApp, payments, operatorEmail);
    }

    private Payment stuckPayment() {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setPaymentReference("PAY-123");
        p.setBookingId(UUID.randomUUID());
        p.setAmount(new BigDecimal("25.00"));
        p.setCurrency("USD");
        p.setCustomerMsisdn("+263771234567");
        return p;
    }

    @Test
    void firstFailure_alertsOperatorAndCustomer_andStampsMarker() {
        Payment p = stuckPayment();

        alerter(OPS).onStillFailing(p, "booking-service 503");

        assertThat(p.getOperatorAlertedAt()).isNotNull();
        verify(payments).save(p);
        verify(email).sendEmail(eq(OPS), contains("PAY-123"), contains("booking-service 503"),
                eq("PAY-OPS-" + p.getId()));
        verify(whatsApp).sendCustomNotification(eq("+263771234567"), contains("confirming your booking manually"));
    }

    @Test
    void secondFailure_isSilent() {
        Payment p = stuckPayment();
        p.setOperatorAlertedAt(Instant.now());

        alerter(OPS).onStillFailing(p, "still down");

        verifyNoInteractions(email, whatsApp, payments);
    }

    @Test
    void noOperatorEmailConfigured_customerStillReassured() {
        Payment p = stuckPayment();

        alerter("").onStillFailing(p, "boom");

        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
        verify(whatsApp).sendCustomNotification(eq("+263771234567"), anyString());
        assertThat(p.getOperatorAlertedAt()).isNotNull();
    }

    @Test
    void emailFailure_doesNotBlockCustomerReassurance_orThrow() {
        Payment p = stuckPayment();
        doThrow(new RuntimeException("smtp down"))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> alerter(OPS).onStillFailing(p, "boom")).doesNotThrowAnyException();

        verify(whatsApp).sendCustomNotification(eq("+263771234567"), anyString());
        assertThat(p.getOperatorAlertedAt()).isNotNull();
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
