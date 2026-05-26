package com.innbucks.loyaltyservice.integration;

/**
 * Thrown when the WhatsApp notification gateway rejects a message or is
 * unreachable. Voucher delivery treats it as best-effort (logs and moves on),
 * so it never rolls back an already-issued voucher.
 */
public class NotificationDeliveryException extends RuntimeException {
    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
