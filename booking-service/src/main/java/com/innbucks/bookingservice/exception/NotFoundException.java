package com.innbucks.bookingservice.exception;

/**
 * Entity (booking, seat, event, ...) was looked up by id and didn't exist.
 * Maps to HTTP 404.
 *
 * <p>Replaces {@code throw new RuntimeException("X not found")} — the old
 * GlobalExceptionHandler mapped those to 404 by sniffing the message string,
 * which is fragile (a wording change quietly downgrades the response to 400).
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
