package com.innbucks.bookingservice.exception;

/**
 * A downstream service the booking flow depends on is unreachable or
 * returned an empty/error envelope (circuit open, fallback null, etc.) —
 * event-service for availability release, loyalty-service for rule lookup
 * during points redeem. Maps to HTTP 503 Service Unavailable.
 *
 * <p>Returning 503 instead of the old 400 is honest: the client did
 * nothing wrong, the server is temporarily unable to honour the request.
 * It also lets infrastructure (load balancers, monitoring) treat these
 * differently from genuine 4xx client errors — important when veengu /
 * loyalty / Oradian have a blip.
 *
 * <p>Siblings: {@link SeatServiceUnavailableException} (also 503) and
 * {@link LoyaltyServiceUnavailableException} (also 503) — kept separate
 * for log-grep granularity. This one covers event-service + the generic
 * dependency-down case during the booking confirm / reverse flow.
 */
public class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(String message) {
        super(message);
    }
}
