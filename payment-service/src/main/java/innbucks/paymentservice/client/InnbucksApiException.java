package innbucks.paymentservice.client;

import lombok.Getter;

/**
 * Permanent failure from the InnBucks Merchant API: the platform actively
 * refused the request (bad credentials, malformed request, unconfigured
 * client). Retrying the same request will not help — the caller closes the
 * ledger row FAILED (no money moves on a refused request) and surfaces an
 * operator-facing error.
 */
@Getter
public class InnbucksApiException extends RuntimeException {

    private final int statusCode;

    public InnbucksApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public InnbucksApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
}
