package zw.co.innbucks.coregateway;

import org.springframework.stereotype.Component;
import zw.co.innbucks.core.dto.veengu.ErrorResponse;

/**
 * Pure logic that maps a veengu response (success or {@link ErrorResponse}) to
 * a {@link PaymentOutcome}. Kept stateless and Spring-context-free so it can
 * be exercised by a fast unit test — the truth table here IS the
 * payments-classification contract and any change to it is a behaviour change
 * the test must catch.
 *
 * <p>Codes mirror the catalog at
 * {@code core-platform-v2/src/main/java/zw/co/innbucks/core/dto/veengu/enums/ErrorCode.java}.
 * Strings, not the enum: a future code we don't know about should fall through
 * to a generic outcome based on HTTP status, never blow up with an
 * {@code IllegalArgumentException} on enum parsing.
 */
@Component
class VeenguErrorClassifier {

    /**
     * Classify a successful (2xx) veengu response. We do NOT trust the
     * {@code responseCode} field on the generic {@code TransactionDto} for the
     * success/processing distinction yet — that field's vocabulary isn't
     * documented in the core jar today, so the safest contract is: HTTP 2xx
     * from veengu means it accepted the submission. If a future iteration
     * needs to surface PROCESSING from a 2xx, this is the seam to add it.
     */
    PaymentOutcome classifySuccess() {
        return PaymentOutcome.COMPLETED;
    }

    /**
     * Classify a non-2xx veengu response. {@code errorBody} may be null when
     * the response body wasn't parseable as {@link ErrorResponse} (e.g. an
     * upstream load-balancer returned plain text or an HTML error page).
     *
     * <p>Truth table:
     * <pre>
     *   code                       | outcome
     *   ---------------------------+---------------------------------
     *   NOT_SUFFICIENT_FUNDS       | REJECTED_INSUFFICIENT_FUNDS
     *   LIMIT_REACHED              | REJECTED_LIMIT_REACHED
     *   PARTY_NOT_AVAILABLE        | REJECTED_ACCOUNT_UNAVAILABLE
     *   OPERATION_NOT_APPLICABLE   | REJECTED_ACCOUNT_UNAVAILABLE
     *   CURRENCY_NOT_SUPPORTED     | REJECTED_CURRENCY
     *   PARAMETER_MISSING          | REJECTED_VALIDATION
     *   PARAMETER_INVALID          | REJECTED_VALIDATION
     *   RESOURCE_MISSING           | REJECTED_VALIDATION
     *   NOT_FOUND                  | REJECTED_VALIDATION
     *   NOT_AUTHORIZED             | REJECTED_NOT_AUTHORIZED
     *   NOT_AUTHENTICATED          | REJECTED_NOT_AUTHORIZED
     *   RESOURCE_ALREADY_EXISTS    | DUPLICATE_DETECTED
     *   RESOURCE_NOT_AVAILABLE     | UPSTREAM_UNAVAILABLE
     *   OTHER_SERVER_ERROR         | UPSTREAM_UNAVAILABLE
     *   OTHER_CLIENT_ERROR         | REJECTED_OTHER
     *   (unknown / null code)      | httpStatus &gt;= 500 ? UPSTREAM_UNAVAILABLE : REJECTED_OTHER
     * </pre>
     */
    PaymentOutcome classifyFailure(ErrorResponse errorBody, int httpStatus) {
        String code = errorBody == null ? null : errorBody.getCode();
        if (code == null || code.isBlank()) {
            return httpStatus >= 500 ? PaymentOutcome.UPSTREAM_UNAVAILABLE : PaymentOutcome.REJECTED_OTHER;
        }
        return switch (code) {
            case "NOT_SUFFICIENT_FUNDS"     -> PaymentOutcome.REJECTED_INSUFFICIENT_FUNDS;
            case "LIMIT_REACHED"            -> PaymentOutcome.REJECTED_LIMIT_REACHED;
            case "PARTY_NOT_AVAILABLE",
                 "OPERATION_NOT_APPLICABLE" -> PaymentOutcome.REJECTED_ACCOUNT_UNAVAILABLE;
            case "CURRENCY_NOT_SUPPORTED"   -> PaymentOutcome.REJECTED_CURRENCY;
            case "PARAMETER_MISSING",
                 "PARAMETER_INVALID",
                 "RESOURCE_MISSING",
                 "NOT_FOUND"                -> PaymentOutcome.REJECTED_VALIDATION;
            case "NOT_AUTHORIZED",
                 "NOT_AUTHENTICATED"        -> PaymentOutcome.REJECTED_NOT_AUTHORIZED;
            case "RESOURCE_ALREADY_EXISTS"  -> PaymentOutcome.DUPLICATE_DETECTED;
            case "RESOURCE_NOT_AVAILABLE",
                 "OTHER_SERVER_ERROR"       -> PaymentOutcome.UPSTREAM_UNAVAILABLE;
            case "OTHER_CLIENT_ERROR"       -> PaymentOutcome.REJECTED_OTHER;
            default -> httpStatus >= 500 ? PaymentOutcome.UPSTREAM_UNAVAILABLE : PaymentOutcome.REJECTED_OTHER;
        };
    }
}
