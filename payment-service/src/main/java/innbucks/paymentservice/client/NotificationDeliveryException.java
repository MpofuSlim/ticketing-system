package innbucks.paymentservice.client;

/**
 * Thrown when the WhatsApp notification gateway rejects a message or is
 * unreachable. Payment confirmation treats it as best-effort (logs and moves
 * on), so it never affects the already-committed transaction.
 */
public class NotificationDeliveryException extends RuntimeException {
    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
