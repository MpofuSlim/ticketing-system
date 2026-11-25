package innbucks.paymentservice.client;

/**
 * No usable answer from Paynow: timeout, connection failure, 5xx, a body that
 * is not a Paynow message, or a reply whose hash does not verify. The request
 * MAY have been processed. Status reads are retried; initiate and the O'mari
 * code submission are NOT (a retry could start a second payment, or spend one
 * of the customer's five code attempts), so the caller leaves the payment for
 * the status check to resolve.
 */
public class PaynowApiTransientException extends RuntimeException {

    public PaynowApiTransientException(String message) {
        super(message);
    }

    public PaynowApiTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
