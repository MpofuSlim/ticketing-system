package com.innbucks.eventservice.exception;

/**
 * Operation conflicts with the current server state — duplicate event by
 * (tenant, title, venue, startDateTime); availability would underflow / over-
 * cap; idempotent-replay disagreement. Maps to HTTP 409.
 *
 * <p>Distinct from {@link BadRequestException} (400): the request itself is
 * structurally fine, the server state just rejects it. A client retry without
 * changing the request payload usually won't help — the state has to change
 * first (refetch, re-pick a non-clashing slot, wait for the held capacity to
 * release).
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
