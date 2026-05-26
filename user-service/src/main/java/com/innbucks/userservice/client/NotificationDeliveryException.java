package com.innbucks.userservice.client;

/**
 * Thrown when the WhatsApp notification gateway rejects a message or is
 * unreachable. Mapped to HTTP 502 by GlobalExceptionHandler so callers know
 * delivery failed (and, for OTP, that the transaction was rolled back and they
 * should retry).
 */
public class NotificationDeliveryException extends RuntimeException {
    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
