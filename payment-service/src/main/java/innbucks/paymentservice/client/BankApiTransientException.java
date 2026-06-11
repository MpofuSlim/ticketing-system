package innbucks.paymentservice.client;

/**
 * Transient Bank API failure: timeout, connectivity error, 5xx, or circuit
 * open. For a payment submission this means the outcome is UNKNOWN — money
 * may or may not have moved — so the caller parks the ledger row IN_DOUBT
 * and the reconciler resolves it via {@code GET /bank/api/transaction/inquiry}.
 * Retry (where wired) fires only on this class, and only for idempotent
 * operations (login / inquiry / account lookup) — never for the payment
 * submission itself.
 */
public class BankApiTransientException extends BankApiException {

    public BankApiTransientException(String message, int statusCode) {
        super(message, statusCode);
    }

    public BankApiTransientException(String message, int statusCode, Throwable cause) {
        super(message, statusCode, cause);
    }
}
