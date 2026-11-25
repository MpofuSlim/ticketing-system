package innbucks.paymentservice.client;

/**
 * Paynow answered and refused: an {@code status=Error} reply. Nothing was
 * created (initiate) or the step did not happen (O'mari code). The message is
 * Paynow's own {@code error} text, cleaned to one line, e.g. "Insufficient
 * balance" or "Invalid OTP".
 */
public class PaynowApiException extends RuntimeException {

    private final String paynowError;

    public PaynowApiException(String message, String paynowError) {
        super(message);
        this.paynowError = paynowError;
    }

    /** Paynow's {@code error} text, cleaned; null when it sent none. */
    public String paynowError() {
        return paynowError;
    }
}
