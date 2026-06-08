package zw.co.innbucks.coregateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Internal gateway endpoint for ticketing's payment-service to debit a
 * customer's InnBucks wallet via veengu, and to post a reversal against a
 * previous debit.
 *
 * <p>Submit-then-classify contract, distinct from the notification controllers
 * because money rules differ from messaging rules:
 *
 * <ul>
 *   <li><b>200 OK</b> on every authoritative veengu verdict — success AND
 *       terminal rejection (insufficient funds, account locked, validation,
 *       duplicate-detected). {@link PaymentResponse#outcome()} disambiguates.
 *       This is deliberate: it keeps Resilience4j retry in payment-service from
 *       firing on a permanent rejection, AND lets the caller persist the
 *       upstream code/message without parsing HTTP status semantics.</li>
 *   <li><b>503 Service Unavailable</b> only when we couldn't get an answer —
 *       veengu unreachable, network failure, 5xx that classified to
 *       {@code UPSTREAM_UNAVAILABLE}. Caller leaves its ledger PENDING and the
 *       reconciler resolves later by re-querying veengu.</li>
 *   <li><b>400 Bad Request</b> for request-shape failures the gateway catches
 *       BEFORE submitting to veengu (blank reference, non-positive amount,
 *       etc.). No veengu round-trip wasted on guaranteed-rejects.</li>
 * </ul>
 *
 * <p>The {@code paymentReference} on debit is caller-assigned (payment-service
 * owns the local ledger row and the upstream id together) and is what veengu
 * uses for duplicate detection when {@code validateDuplicates=true} is set on
 * the merchant participant config.
 *
 * <p>No authentication on this controller — internal-only (port 8088), not
 * publicly routable. The veengu side authenticates via the
 * {@code X-Source-Component} header that the core jar's {@code FeignInterceptor}
 * stamps on every outbound call.
 *
 * <p>WARNING: this is REAL MONEY when wired to a production veengu. Only
 * deploy after the target environment is confirmed sandbox or after the new
 * payment path has been smoked end-to-end with the dummy fallback intact in
 * payment-service.
 */
@RestController
class PaymentsController {

    private static final Logger log = LoggerFactory.getLogger(PaymentsController.class);

    private final VeenguPaymentService paymentService;

    PaymentsController(VeenguPaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/payments/debit")
    ResponseEntity<PaymentResponse> debit(@RequestBody PaymentDebitRequest request) {
        ResponseEntity<PaymentResponse> validation = validateDebit(request);
        if (validation != null) {
            return validation;
        }
        PaymentResponse response = paymentService.debit(request);
        return toResponseEntity(response);
    }

    @PostMapping("/payments/{originalPaymentReference}/reverse")
    ResponseEntity<PaymentResponse> reverse(@PathVariable("originalPaymentReference") String originalPaymentReference,
                                            @RequestBody PaymentReversalRequest request) {
        if (originalPaymentReference == null || originalPaymentReference.isBlank()) {
            return badRequest(null, "originalPaymentReference is required");
        }
        ResponseEntity<PaymentResponse> validation = validateReversal(request);
        if (validation != null) {
            return validation;
        }
        PaymentResponse response = paymentService.reverse(originalPaymentReference, request);
        return toResponseEntity(response);
    }

    private ResponseEntity<PaymentResponse> validateDebit(PaymentDebitRequest r) {
        if (r == null) return badRequest(null, "request body is required");
        if (blank(r.paymentReference())) return badRequest(null, "paymentReference is required");
        if (blank(r.customerMsisdn())) return badRequest(r.paymentReference(), "customerMsisdn is required");
        if (blank(r.customerAccount())) return badRequest(r.paymentReference(), "customerAccount is required");
        if (blank(r.merchantAccount())) return badRequest(r.paymentReference(), "merchantAccount is required");
        if (r.amount() == null || r.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return badRequest(r.paymentReference(), "amount must be > 0");
        }
        if (r.currency() == null) return badRequest(r.paymentReference(), "currency is required");
        return null;
    }

    private ResponseEntity<PaymentResponse> validateReversal(PaymentReversalRequest r) {
        if (r == null) return badRequest(null, "request body is required");
        if (blank(r.reversalReference())) return badRequest(null, "reversalReference is required");
        if (blank(r.customerMsisdn())) return badRequest(r.reversalReference(), "customerMsisdn is required");
        if (blank(r.customerAccount())) return badRequest(r.reversalReference(), "customerAccount is required");
        if (blank(r.merchantAccount())) return badRequest(r.reversalReference(), "merchantAccount is required");
        if (r.amount() == null || r.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return badRequest(r.reversalReference(), "amount must be > 0");
        }
        if (r.currency() == null) return badRequest(r.reversalReference(), "currency is required");
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseEntity<PaymentResponse> badRequest(String reference, String error) {
        return ResponseEntity.badRequest().body(
                new PaymentResponse(reference, PaymentOutcome.REJECTED_VALIDATION,
                        null, null, null, error));
    }

    private static ResponseEntity<PaymentResponse> toResponseEntity(PaymentResponse r) {
        HttpStatus status = r.outcome() == PaymentOutcome.UPSTREAM_UNAVAILABLE
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(r);
    }
}
