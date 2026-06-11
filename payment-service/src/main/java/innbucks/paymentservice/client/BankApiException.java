package innbucks.paymentservice.client;

/**
 * Permanent (non-retryable) Bank API failure: the request reached the bank
 * and was rejected for a reason a retry will not change (request-shape 4xx,
 * auth misconfiguration, etc.). Money did NOT move.
 */
public class BankApiException extends RuntimeException {
    private final int statusCode;

    public BankApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public BankApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
