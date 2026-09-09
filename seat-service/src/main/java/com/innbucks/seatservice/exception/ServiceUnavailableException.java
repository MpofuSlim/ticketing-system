package com.innbucks.seatservice.exception;

/**
 * A dependency this operation needs could not be reached, so the request is
 * refused rather than completed on an assumption. Maps to HTTP 503.
 *
 * <p>Distinct from {@link ConflictException} (409): 409 means the server
 * checked and the state says no. This means the server could not check. The
 * client should retry unchanged once the dependency is back — which is exactly
 * what 503 tells it, and what a 409 or 400 would not.
 *
 * <p>Use this only where failing open would be unsafe. Most seat-service reads
 * deliberately degrade instead (a public category listing falls back to its
 * stored availability mirror rather than 503-ing when booking-service is down);
 * the guard on {@code deleteCategory} is the opposite case, because assuming
 * "no bookings" when the count is unavailable would let through the exact
 * delete the guard exists to stop.
 */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
