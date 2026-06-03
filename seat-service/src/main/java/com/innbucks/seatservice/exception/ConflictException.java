package com.innbucks.seatservice.exception;

/**
 * Operation conflicts with the current server state — seat is no longer
 * available, category exhausted, duplicate category name on the same event,
 * stale lock at confirm time. Maps to HTTP 409.
 *
 * <p>Distinct from {@link BadRequestException} (400): the request itself is
 * structurally fine, the server state just rejects it. A client retry without
 * changing the request payload usually won't help — the state has to change
 * first (refetch, pick a different seat, restart the lock flow).
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
