package innbucks.paymentservice.client;

/**
 * Thrown by {@link InnbucksCoreGatewayClient} when an outbound call to the
 * innbucks-core-gateway fails in a way the caller must surface to the user
 * (4xx) or the operator (unparseable response). Carries the upstream HTTP
 * status so {@code GlobalExceptionHandler} can preserve it instead of
 * collapsing every failure into a generic 500.
 *
 * <p>Modelled on {@link OradianMiddlewareException} — same shape, same
 * conventions, so the two outbound clients feel uniform.
 */
public class InnbucksCoreGatewayException extends RuntimeException {

    private final int statusCode;

    public InnbucksCoreGatewayException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public InnbucksCoreGatewayException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
