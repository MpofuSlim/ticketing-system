package innbucks.paymentservice.client;

/**
 * Thrown by {@link OradianMiddlewareClient} when an outbound call to Oradian
 * middleware fails. Carries the upstream HTTP status so
 * GlobalExceptionHandler can preserve it (502 for connectivity / empty body,
 * the upstream status for HTTP errors) instead of collapsing every failure
 * into a generic 500.
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
