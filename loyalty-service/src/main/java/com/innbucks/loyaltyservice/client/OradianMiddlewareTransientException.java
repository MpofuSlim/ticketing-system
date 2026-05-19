package com.innbucks.loyaltyservice.client;

/**
 * Marker subclass for {@link OradianMiddlewareException} thrown on
 * <b>retryable</b> failure modes — connection refused, read timeout, 5xx
 * from the middleware (502 / 503 / 504), empty response body, etc. The
 * Resilience4j Retry instance bound to {@code oradian-middleware} is
 * configured via {@code application.yaml} to retry on this class only.
 *
 * <p>Permanent rejections — 4xx from the middleware (validation,
 * insufficient funds on withdraw, idempotency conflict, etc.) — are
 * thrown as the plain {@link OradianMiddlewareException} so they
 * propagate on the first attempt. Retrying a 4xx would just burn the
 * customer's request time and delay the inevitable failure.
 *
 * <p>The sync layer uses this distinction when stamping
 * {@code oradian_sync_transactions.failure_code}: transient ≘
 * {@code UPSTREAM_UNAVAILABLE} (reconciliation may resolve it later),
 * permanent ≘ {@code UPSTREAM_REJECTED} (won't help to retry).
 */
public class OradianMiddlewareTransientException extends OradianMiddlewareException {

    public OradianMiddlewareTransientException(String message, int statusCode) {
        super(message, statusCode);
    }

    public OradianMiddlewareTransientException(String message, int statusCode, Throwable cause) {
        super(message, statusCode, cause);
    }
}
