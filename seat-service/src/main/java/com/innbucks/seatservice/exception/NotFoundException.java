package com.innbucks.seatservice.exception;

/**
 * Entity (seat, category, …) was looked up and didn't exist. Maps to HTTP 404.
 *
 * <p>Replaces {@code throw new RuntimeException("X not found")} — the old
 * GlobalExceptionHandler mapped those to 404 by sniffing the message string
 * for the literal "not found", which is fragile: any wording change (e.g.
 * "Seat does not exist") silently downgrades the response to 400 and the
 * frontend's not-found handler stops firing. The typed exception fixes the
 * coupling.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
