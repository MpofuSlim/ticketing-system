package com.innbucks.bookingservice.exception;

/**
 * Raised by the user-service Feign fallback when the downstream is failing
 * or the circuit breaker is open. Mapped to 503 by the global handler.
 */
public class UserServiceUnavailableException extends RuntimeException {
    public UserServiceUnavailableException(String message) {
        super(message);
    }
}
