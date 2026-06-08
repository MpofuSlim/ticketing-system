package zw.co.innbucks.coregateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import zw.co.innbucks.core.dto.TransactionDto;
import zw.co.innbucks.core.dto.enums.Channel;
import zw.co.innbucks.core.dto.enums.PaymentTypeDto;
import zw.co.innbucks.core.dto.enums.TransactionTypeDto;
import zw.co.innbucks.core.dto.veengu.ErrorResponse;
import zw.co.innbucks.core.rest.client.VeenguClient;

/**
 * Submits ticketing payments and reversals to veengu via the core jar's
 * {@link VeenguClient} and classifies the response into a typed
 * {@link PaymentResponse} the caller can switch on.
 *
 * <p>Two responsibilities, kept narrow:
 * <ol>
 *   <li>Build the wire {@link TransactionDto} from the gateway's request
 *       record — pinning channel/payment-type/transaction-type to the
 *       ticketing-payment values, and stamping the caller's
 *       {@code paymentReference} as the veengu {@code reference} (idempotency
 *       key).</li>
 *   <li>Translate veengu's three response shapes (2xx, 4xx/5xx with
 *       {@link ErrorResponse} body, network failure) into a
 *       {@link PaymentResponse} via {@link VeenguErrorClassifier}.</li>
 * </ol>
 *
 * The controller stays a thin HTTP shell; this service is the one place that
 * understands what a veengu transaction LOOKS like for ticketing.
 *
 * <p>{@link FeignInterceptor} (imported in
 * {@link InnbucksCoreGatewayApplication}) already stamps the
 * {@code X-Source-Component} header on every outbound veengu call —
 * no per-call auth wiring needed here.
 */
@Service
class VeenguPaymentService {

    private static final Logger log = LoggerFactory.getLogger(VeenguPaymentService.class);

    private final VeenguClient veenguClient;
    private final VeenguErrorClassifier classifier;
    private final ObjectMapper objectMapper;

    VeenguPaymentService(VeenguClient veenguClient,
                         VeenguErrorClassifier classifier,
                         ObjectMapper objectMapper) {
        this.veenguClient = veenguClient;
        this.classifier = classifier;
        this.objectMapper = objectMapper;
    }

    PaymentResponse debit(PaymentDebitRequest request) {
        TransactionDto dto = TransactionDto.builder()
                .reference(request.paymentReference())
                .transactionType(TransactionTypeDto.PAYMENT)
                .paymentType(PaymentTypeDto.ACCOUNT)
                .channel(Channel.API)
                .amount(request.amount())
                .currency(request.currency())
                .msisdn(request.customerMsisdn())
                .sourceMsisdn(request.customerMsisdn())
                .sourceAccount(request.customerAccount())
                .destinationAccount(request.merchantAccount())
                .participantId(request.participantId())
                .narration(request.narration())
                .build();
        return submit(dto, request.paymentReference(), "debit");
    }

    PaymentResponse reverse(String originalPaymentReference, PaymentReversalRequest request) {
        TransactionDto dto = TransactionDto.builder()
                .reference(request.reversalReference())
                // Original reference is how veengu locates the debit being
                // reversed; populated on BOTH the wire and our local audit so
                // a reconciliation join is trivial.
                .originalReference(originalPaymentReference)
                .transactionType(TransactionTypeDto.REVERSAL)
                .paymentType(PaymentTypeDto.ACCOUNT)
                .channel(Channel.API)
                .amount(request.amount())
                .currency(request.currency())
                .msisdn(request.customerMsisdn())
                // On a reversal the money direction flips: merchant -> customer.
                .sourceAccount(request.merchantAccount())
                .destinationAccount(request.customerAccount())
                .destinationMsisdn(request.customerMsisdn())
                .participantId(request.participantId())
                .narration(request.narration())
                .isReversed(true)
                .build();
        return submit(dto, request.reversalReference(), "reverse");
    }

    private PaymentResponse submit(TransactionDto dto, String paymentReference, String op) {
        try {
            TransactionDto response = veenguClient.postTransaction(dto);
            PaymentOutcome outcome = classifier.classifySuccess();
            log.info("[payments] {} accepted reference={} upstreamRef={} outcome={}",
                    op, paymentReference, response == null ? null : response.getUpstreamReference(), outcome);
            return new PaymentResponse(
                    paymentReference,
                    outcome,
                    response == null ? null : response.getUpstreamReference(),
                    response == null ? null : response.getResponseCode(),
                    response == null ? null : response.getResponseDescription(),
                    null);
        } catch (FeignException e) {
            // Reached veengu; got a non-2xx. Try to parse the typed error body so
            // we can classify by code rather than by HTTP status — that's the
            // banking-discipline bit: NOT_SUFFICIENT_FUNDS is a permanent
            // rejection, RESOURCE_NOT_AVAILABLE is transient, both can return
            // as HTTP 400 from the gateway in front of veengu.
            ErrorResponse errorBody = parseError(e);
            PaymentOutcome outcome = classifier.classifyFailure(errorBody, e.status());
            // PII-safe log: reference + classified outcome + upstream code.
            // No msisdn / account / amount on the WARN line.
            log.warn("[payments] {} reference={} -> HTTP {} code={} -> outcome={}",
                    op, paymentReference, e.status(),
                    errorBody == null ? "<unparseable>" : errorBody.getCode(),
                    outcome);
            return new PaymentResponse(
                    paymentReference,
                    outcome,
                    null,
                    errorBody == null ? null : errorBody.getCode(),
                    errorBody == null ? null : errorBody.getMessage(),
                    "veengu " + op + " rejected: HTTP " + e.status()
                            + (errorBody == null ? "" : " " + errorBody.getCode()));
        } catch (Exception e) {
            // Never reached veengu — discovery / connectivity failure / timeout.
            // Caller leaves PENDING; reconciler resolves later.
            log.warn("[payments] {} reference={} unreachable: {}", op, paymentReference, e.getMessage());
            return new PaymentResponse(
                    paymentReference,
                    PaymentOutcome.UPSTREAM_UNAVAILABLE,
                    null, null, null,
                    "veengu " + op + " unreachable: " + e.getMessage());
        }
    }

    private ErrorResponse parseError(FeignException e) {
        String body = e.contentUTF8();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, ErrorResponse.class);
        } catch (JsonProcessingException jpe) {
            // Body wasn't a JSON ErrorResponse — could be a load-balancer HTML
            // page or upstream plain text. Classifier falls back to HTTP status.
            return null;
        }
    }
}
