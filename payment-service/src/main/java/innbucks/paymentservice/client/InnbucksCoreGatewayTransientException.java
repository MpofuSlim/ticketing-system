package innbucks.paymentservice.client;

/**
 * Marker subclass thrown on <b>retryable</b> failure modes when calling
 * innbucks-core-gateway — connection refused, read timeout, 5xx from the
 * gateway (which itself returns 503 when veengu is unreachable), empty
 * response body, or {@code CallNotPermittedException} from an open circuit
 * breaker.
 *
 * <p>The Resilience4j Retry instance bound to {@code innbucks-core-gateway}
 * is configured in {@code application.yaml} to retry on this class only.
 * Permanent gateway rejections (4xx — request-shape errors before veengu
 * was even called) propagate as the plain {@link InnbucksCoreGatewayException}
 * so retrying doesn't burn attempts on a guaranteed-reject.
 *
 * <p>NOTE: veengu's own {@code REJECTED_*} outcomes (insufficient funds,
 * account locked, etc.) are NOT thrown as exceptions — the gateway returns
 * them as HTTP 200 with the outcome in the body. They're terminal local
 * verdicts, not retryable, so the client returns them as a
 * {@link InnbucksCoreGatewayResponse} for the caller to switch on.
 */
public class InnbucksCoreGatewayTransientException extends InnbucksCoreGatewayException {

    public InnbucksCoreGatewayTransientException(String message, int statusCode) {
        super(message, statusCode);
    }

    public InnbucksCoreGatewayTransientException(String message, int statusCode, Throwable cause) {
        super(message, statusCode, cause);
    }
}
