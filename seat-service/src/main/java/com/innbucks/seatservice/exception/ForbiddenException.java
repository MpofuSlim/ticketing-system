package com.innbucks.seatservice.exception;

/**
 * Authenticated caller is not allowed to perform this action against this
 * resource — typically a user trying to release a seat lock owned by a
 * different user. Maps to HTTP 403.
 *
 * <p>Spring Security's own {@code AccessDeniedException} already handles
 * authorisation failures it catches at the filter / @PreAuthorize layer
 * (role check, event-ownership cross-check). This class is for the in-service
 * business checks Spring Security can't see — "this seat lock belongs to a
 * different user".
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
