package innbucks.paymentservice.client;

/**
 * Paynow was never reached: our own circuit breaker refused the call, or no
 * connection could be opened (refused, unknown host, no route, connect
 * timeout, no pooled connection free). Nothing was sent, so nothing can exist
 * at Paynow: a start that ends here has no live prompt, page or code.
 *
 * <p>A subclass of {@link PaynowApiTransientException}, so the circuit breaker
 * counts it and every caller that only knows "no answer" treats it as one. A
 * read timeout, a reset connection or a reply we cannot use are NOT this:
 * those can come after the request was written, and stay ambiguous.
 */
public class PaynowNotSentException extends PaynowApiTransientException {

    public PaynowNotSentException(String message, Throwable cause) {
        super(message, cause);
    }
}
