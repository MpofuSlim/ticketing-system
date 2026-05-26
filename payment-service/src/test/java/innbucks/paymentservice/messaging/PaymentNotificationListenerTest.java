package innbucks.paymentservice.messaging;

import innbucks.paymentservice.client.NotificationDeliveryException;
import innbucks.paymentservice.client.WhatsAppNotificationClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentNotificationListenerTest {

    private TransactionCompletedEvent event(String status, String phone) {
        return new TransactionCompletedEvent(
                UUID.randomUUID(), Instant.now(), UUID.randomUUID(), "TRANSFER", status,
                phone, "src-acct", "dst-acct",
                new BigDecimal("50.00"), "USD", "wallet", null,
                null, null, Instant.now(),
                Instant.now(), "oradian-tx-1", "REF-9876",
                null, null, null, "corr-1");
    }

    @Test
    void succeeded_sendsConfirmationWithAmountAndReference() {
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);

        new PaymentNotificationListener(whatsApp)
                .onTransactionCompleted(event("SUCCEEDED", "+263771234567"));

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendCustomNotification(eq("+263771234567"), msg.capture());
        assertTrue(msg.getValue().contains("50.00"), "message should carry the amount");
        assertTrue(msg.getValue().contains("REF-9876"), "message should carry the reference");
        assertTrue(msg.getValue().toLowerCase().contains("successful"), "message should confirm success");
    }

    @Test
    void failed_doesNotNotify() {
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);

        new PaymentNotificationListener(whatsApp)
                .onTransactionCompleted(event("FAILED", "+263771234567"));

        verifyNoInteractions(whatsApp);
    }

    @Test
    void succeeded_withNoPhone_skips() {
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);

        new PaymentNotificationListener(whatsApp)
                .onTransactionCompleted(event("SUCCEEDED", null));

        verifyNoInteractions(whatsApp);
    }

    @Test
    void gatewayFailure_isSwallowed_soCommittedTxnIsUnaffected() {
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        doThrow(new NotificationDeliveryException("gateway down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        // Must NOT throw — the transaction already committed; delivery is best-effort.
        new PaymentNotificationListener(whatsApp)
                .onTransactionCompleted(event("SUCCEEDED", "+263771234567"));

        verify(whatsApp).sendCustomNotification(eq("+263771234567"), anyString());
    }
}
