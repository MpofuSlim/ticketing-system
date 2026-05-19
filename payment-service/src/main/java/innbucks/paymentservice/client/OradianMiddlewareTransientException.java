package innbucks.paymentservice.client;

/**
 * Marker subclass for {@link OradianMiddlewareException} thrown on
 * <b>retryable</b> failure modes — connection refused, read timeout, 5xx
 * from Oradian middleware (502 / 503 / 504), empty response body, etc.
 * The Resilience4j Retry instance bound to {@code oradian-middleware} is
 * configured via {@code application.yaml} to retry on this class only.
 *
 * <p>Permanent rejections — 4xx from Oradian (validation, ownership,
 * insufficient funds, account suspended) — are thrown as the plain
 * {@link OradianMiddlewareException} so they propagate to the caller on
 * the first attempt. Retrying a 4xx would just burn attempts and delay
 * the customer-facing error.
 *
 * <p>{@code GlobalExceptionHandler} maps this subclass exactly the same
 * way as the superclass (it doesn't pattern-match on the subclass), so
 * the customer-facing response shape is unchanged. The distinction
 * exists purely for retry-eligibility classification.
 */
public class OradianMiddlewareTransientException extends OradianMiddlewareException {

    public OradianMiddlewareTransientException(String message, int statusCode) {
        super(message, statusCode);
    }

    public OradianMiddlewareTransientException(String message, int statusCode, Throwable cause) {
        super(message, statusCode, cause);
    }
}
