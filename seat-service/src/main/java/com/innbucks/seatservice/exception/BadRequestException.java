package com.innbucks.seatservice.exception;

/**
 * Client supplied a request the server can't process at the business-rule
 * layer — total seats exceed the per-category cap, malformed section
 * descriptor, etc. Maps to HTTP 400.
 *
 * <p>Use for client-fixable errors. For state-coupled conflicts where the
 * input is fine but the current server state rejects the operation (duplicate
 * category name, seat already locked / booked, stale lock at confirm time),
 * prefer {@link ConflictException} (409). Field-level bean-validation
 * failures get their own 400 path via the existing
 * {@code MethodArgumentNotValidException} handler.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
