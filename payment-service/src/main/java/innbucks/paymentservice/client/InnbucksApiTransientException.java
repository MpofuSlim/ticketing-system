package innbucks.paymentservice.client;

import lombok.Getter;

/**
 * Transient failure talking to the InnBucks Merchant API: timeout, 5xx,
 * connect-refused, circuit open. The request MAY or may not have been
 * processed upstream. For code-status queries this is retried (read-only);
 * for code generation it is NOT — the caller closes the row FAILED instead,
 * which is safe because generation moves no money and an undelivered code
 * simply expires upstream.
 */
@Getter
public class InnbucksApiTransientException extends RuntimeException {

    private final int statusCode;

    public InnbucksApiTransientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public InnbucksApiTransientException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
}
