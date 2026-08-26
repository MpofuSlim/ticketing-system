package innbucks.paymentservice.client;

import lombok.Getter;

/**
 * Permanent failure from the EcoCash EIP gateway: the platform actively
 * refused the request (bad credentials, malformed request, duplicate
 * clientCorrelator, unregistered subscriber). Retrying the same request will
 * not help — for a refused CHARGE the caller closes the ledger row FAILED
 * (a refused charge pushes no prompt and moves no money).
 */
@Getter
public class EcocashApiException extends RuntimeException {

    private final int statusCode;

    public EcocashApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public EcocashApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
}
