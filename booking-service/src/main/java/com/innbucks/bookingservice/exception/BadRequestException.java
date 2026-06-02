package com.innbucks.bookingservice.exception;

/**
 * Client supplied a request the server can't process at the business-rule
 * layer — wrong category for the event, payment split doesn't reconcile,
 * tier-1 customer trying to redeem points, etc. Maps to HTTP 400.
 *
 * <p>Use for client-fixable errors; for genuine state conflicts where
 * retrying won't help, prefer {@link BookingConflictException} (409).
 * Field-level bean-validation failures get their own 400 path via the
 * existing {@code MethodArgumentNotValidException} handler.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
