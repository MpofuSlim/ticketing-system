package com.innbucks.eventservice.exception;

/**
 * Authenticated caller is not allowed to perform this action against this
 * resource — typically an EVENT_ORGANIZER trying to update / activate /
 * delete an event owned by another tenant. Maps to HTTP 403.
 *
 * <p>Spring Security's own {@code AccessDeniedException} already handles
 * authorisation failures it catches at the filter / @PreAuthorize layer
 * (role check, JWT scope check). This class is for the in-service business
 * checks Spring Security can't see — "owner tenant doesn't match the caller's
 * tenant".
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
