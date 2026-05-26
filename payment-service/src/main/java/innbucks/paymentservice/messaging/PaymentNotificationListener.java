package innbucks.paymentservice.messaging;

import innbucks.paymentservice.client.WhatsAppNotificationClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the customer a WhatsApp payment confirmation once a transaction commits
 * SUCCEEDED. A second {@code @TransactionalEventListener(AFTER_COMMIT)}
 * alongside {@link TransactionEventPublisher} (which forwards the same event to
 * Kafka): decoupled from the money path, fires only after the ledger row
 * commits, and best-effort — a gateway failure is logged and never affects the
 * committed transaction.
 *
 * <p>Only SUCCEEDED is notified: failures already surfaced synchronously to the
 * caller as an error response, so a "your payment failed" WhatsApp would be
 * redundant noise.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentNotificationListener {

    private final WhatsAppNotificationClient whatsApp;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionCompleted(TransactionCompletedEvent event) {
        if (!"SUCCEEDED".equals(event.status())) {
            return;
        }
        String phone = event.customerPhone();
        if (phone == null || phone.isBlank()) {
            log.warn("Payment confirmation skipped — no customer phone txId={}", event.transactionId());
            return;
        }
        try {
            whatsApp.sendCustomNotification(phone, buildMessage(event));
            log.info("Payment confirmation sent txId={}", event.transactionId());
        } catch (RuntimeException ex) {
            log.warn("Payment confirmation delivery failed txId={} (transaction unaffected): {}",
                    event.transactionId(), ex.getMessage());
        }
    }

    private static String buildMessage(TransactionCompletedEvent event) {
        String type = event.type() == null ? "payment" : event.type().toLowerCase().replace('_', ' ');
        String reference = event.oradianReferenceNumber() != null
                ? event.oradianReferenceNumber()
                : String.valueOf(event.transactionId());
        StringBuilder sb = new StringBuilder("InnBucks: your ").append(type);
        if (event.amount() != null) {
            sb.append(" of ").append(event.amount());
            if (event.currency() != null && !event.currency().isBlank()) {
                sb.append(' ').append(event.currency());
            }
        }
        return sb.append(" was successful. Reference: ").append(reference).append('.').toString();
    }
}
