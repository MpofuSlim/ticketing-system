package innbucks.paymentservice.service;

import innbucks.paymentservice.client.NotificationDeliveryException;
import innbucks.paymentservice.client.SmsNotificationClient;
import innbucks.paymentservice.client.WhatsAppNotificationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pins the delivery contract that makes the 2D-code flow FE-free:
 * WhatsApp primary → SMS fallback, never throwing — a delivery failure must
 * not fail the payment (the code is still echoed on the response and the
 * row self-expires if unpaid).
 */
class PaymentCodeNotifierTest {

    private WhatsAppNotificationClient whatsApp;
    private SmsNotificationClient sms;
    private PaymentCodeNotifier notifier;

    @BeforeEach
    void setUp() {
        whatsApp = mock(WhatsAppNotificationClient.class);
        sms = mock(SmsNotificationClient.class);
        notifier = new PaymentCodeNotifier(whatsApp, sms);
    }

    @Test
    void whatsAppSucceeds_smsNeverTried() {
        PaymentCodeNotifier.Delivery delivery = notifier.sendPaymentCode(
                "+263770000001", "701285660", new BigDecimal("50.00"), "USD",
                Duration.ofMinutes(10), "TKT-PMT-1");

        assertEquals(PaymentCodeNotifier.Delivery.WHATSAPP, delivery);
        verifyNoInteractions(sms);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendCustomNotification(eq("+263770000001"), message.capture());
        assertTrue(message.getValue().contains("701285660"), "message must carry the code");
        assertTrue(message.getValue().contains("USD 50.00"), "message must quote the exact amount");
        assertTrue(message.getValue().contains("10 minutes"), "message must state the deadline");
    }

    @Test
    void whatsAppFails_fallsBackToSms_singleSegment() {
        doThrow(new NotificationDeliveryException("gateway down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        PaymentCodeNotifier.Delivery delivery = notifier.sendPaymentCode(
                "+263770000001", "701285660", new BigDecimal("50.00"), "USD",
                Duration.ofMinutes(10), "TKT-PMT-1");

        assertEquals(PaymentCodeNotifier.Delivery.SMS, delivery);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sms).sendSms(eq("+263770000001"), message.capture(), eq("TKT-PMT-1"));
        assertTrue(message.getValue().contains("701285660"));
        assertTrue(message.getValue().contains("USD 50.00"));
        assertTrue(message.getValue().length() <= 160,
                "SMS copy must fit one segment, was " + message.getValue().length());
    }

    @Test
    void bothChannelsFail_returnsFailed_neverThrows() {
        doThrow(new NotificationDeliveryException("wa down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());
        doThrow(new NotificationDeliveryException("sms down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        PaymentCodeNotifier.Delivery delivery = assertDoesNotThrow(() ->
                notifier.sendPaymentCode("+263770000001", "701285660",
                        new BigDecimal("50.00"), "USD", Duration.ofMinutes(10), "TKT-PMT-1"));

        assertEquals(PaymentCodeNotifier.Delivery.FAILED, delivery);
    }
}
