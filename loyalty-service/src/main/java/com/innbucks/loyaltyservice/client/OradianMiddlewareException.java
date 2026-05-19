package com.innbucks.loyaltyservice.client;

/**
 * Thrown by {@link OradianMiddlewareClient} when an outbound S2S call to
 * Oradian middleware fails. Carries the upstream HTTP status so the
 * sync layer can classify the failure (4xx → UPSTREAM_REJECTED,
 * 5xx/network → UPSTREAM_UNAVAILABLE) when stamping
 * {@code oradian_sync_transactions.failure_code} instead of collapsing
 * every failure into a generic 500.
 *
 * <p>Mirrors the {@code OradianMiddlewareException} in payment-service so
 * the two services classify Oradian failures identically.
 */
public class OradianMiddlewareException extends RuntimeException {

    private final int statusCode;

    public OradianMiddlewareException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public OradianMiddlewareException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
