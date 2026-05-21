package com.innbucks.userservice.exception;

/**
 * Domain "the resource you named doesn't exist" exception. Mapped to 404 by
 * {@link GlobalExceptionHandler} so callers can distinguish "wrong id"
 * from "wrong payload shape" (which surface as 400 via the bean-validation
 * handler) and from genuine server bugs (500 via the RuntimeException
 * fallback).
 *
 * <p>Use this in service-layer code that does {@code findById(...).orElseThrow(...)}.
 * The previous convention of {@code throw new RuntimeException("User not found: " + id)}
 * landed every miss as a 400 BAD_REQUEST through the catch-all RuntimeException
 * handler — wrong status code, wrong category in error-rate dashboards, and
 * confusing to API consumers.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
