package com.innbucks.eventservice.exception;

/**
 * Client supplied a request the server can't process at the business-rule
 * layer — missing country claim, non-positive count on an internal
 * availability call, end-before-start date pair, oversized / wrong-MIME
 * banner upload, etc. Maps to HTTP 400.
 *
 * <p>Use for client-fixable errors. For state-couplled conflicts where the
 * input is fine but the current server state rejects the operation (e.g.
 * "would exceed totalCapacity"), prefer {@link ConflictException} (409).
 * Field-level bean-validation failures get their own 400 path via the
 * existing {@code MethodArgumentNotValidException} handler.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
