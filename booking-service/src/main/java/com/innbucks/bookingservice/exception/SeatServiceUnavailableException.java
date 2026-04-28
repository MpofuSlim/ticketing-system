package com.innbucks.bookingservice.exception;

/**
 * Raised by the seat-service Feign fallback when the downstream is failing
 * or the circuit breaker is open. Mapped to 503 by the global handler.
 */
public class SeatServiceUnavailableException extends RuntimeException {
    public SeatServiceUnavailableException(String message) {
        super(message);
    }
}
