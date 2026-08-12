package innbucks.paymentservice.client;

import lombok.Getter;

/**
 * Transient failure talking to the ZimSwitch Online (COPYandPAY) gateway:
 * timeout, 5xx, connect-refused, circuit open. The request MAY or may not
 * have been processed upstream. Status reads are retried (read-only);
 * prepare-checkout is NOT — the caller closes the row FAILED instead, which
 * is safe because preparing a checkout moves no money and an undelivered
 * checkout simply expires upstream within 30 minutes.
 */
@Getter
public class ZimswitchApiTransientException extends RuntimeException {

    private final int statusCode;

    public ZimswitchApiTransientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public ZimswitchApiTransientException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
}
