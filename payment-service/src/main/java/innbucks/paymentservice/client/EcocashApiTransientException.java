package innbucks.paymentservice.client;

import lombok.Getter;

/**
 * Transient failure talking to the EcoCash EIP gateway: timeout, 5xx,
 * connect-refused, circuit open. The request MAY or may not have been
 * processed upstream. Query reads are retried (read-only); the CHARGE is
 * NOT — and unlike the other rails a charge that got through pushes a LIVE
 * PIN prompt to the customer's phone, so the caller leaves the row
 * TOKEN_ISSUED for the Query poller to resolve rather than closing it
 * (see docs/api/ecocash-eip.md, "ledger write ordering").
 */
@Getter
public class EcocashApiTransientException extends RuntimeException {

    private final int statusCode;

    public EcocashApiTransientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public EcocashApiTransientException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
}
