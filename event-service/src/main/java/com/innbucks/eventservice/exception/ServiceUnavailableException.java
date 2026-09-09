package com.innbucks.eventservice.exception;

/**
 * A dependency this operation needs could not be reached, so the request is
 * refused rather than completed on an assumption. Maps to HTTP 503.
 *
 * <p>Distinct from {@link ConflictException} (409): 409 means the server
 * checked and the state says no. This means the server could not check. The
 * client should retry unchanged once the dependency is back — which is exactly
 * what 503 tells it, and what a 409 or 400 would not.
 *
 * <p>Use this only where failing open would be unsafe. Most event-service reads
 * deliberately degrade instead ({@code SeatCategoryGateway.fetchForEvent} falls
 * back to an empty category list so an event still renders without its seat
 * detail); the capacity guard on {@code updateEvent} is the opposite case,
 * because reading an unavailable allocation as "nothing allocated" would let
 * through the exact capacity cut the guard exists to stop.
 */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
