package com.innbucks.bookingservice.client;

/**
 * Thrown when an outbound notification gateway (WhatsApp, SMS) rejects a
 * message or is unreachable. Booking-confirm notifications treat it as
 * best-effort — logged and swallowed — so a gateway hiccup never affects the
 * already-committed booking.
 */
public class NotificationDeliveryException extends RuntimeException {
    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
