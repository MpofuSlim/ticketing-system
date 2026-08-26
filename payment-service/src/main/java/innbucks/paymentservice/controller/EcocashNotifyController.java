package innbucks.paymentservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.PaymentRail;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.service.EcocashPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receiver for EcoCash EIP's {@code notifyUrl} callback — the HTTP POST the
 * gateway makes when a charge flips to COMPLETED/FAILED.
 *
 * <p><b>The webhook is a TRIGGER, never a truth source.</b> It arrives
 * unauthenticated from the public internet, so nothing in its body is
 * trusted for money facts: the handler extracts our {@code clientCorrelator},
 * finds the row, and runs the SAME upstream-Query resolver the reconciler
 * poll uses ({@link EcocashPaymentService#resolveOpenCharge}). A forged or
 * replayed notify therefore can, at worst, make us ask EcoCash a question we
 * were going to ask within 20 seconds anyway; a LOST notify loses nothing,
 * because the poller remains the authority.
 *
 * <p>The response is a constant 200 regardless of what matched —
 * enumeration resistance (a prober learns nothing about which correlators
 * exist) and webhook hygiene (a non-2xx would make the gateway retry a
 * notification we have already acted on).
 */
@Slf4j
@RestController
@RequestMapping("/payments/ecocash")
@RequiredArgsConstructor
@Tag(name = "EcoCash notify webhook",
        description = "Machine endpoint for the EcoCash EIP gateway's notifyUrl callback. Not for FE use.")
public class EcocashNotifyController {

    private final PaymentRepository paymentRepository;
    private final EcocashPaymentService ecocashPaymentService;
    private final ObjectMapper objectMapper;

    @PostMapping("/notify")
    @SecurityRequirements   // documents "no auth" — the gateway cannot send our JWTs
    @Operation(summary = "EcoCash EIP notify callback (machine endpoint)",
            description = """
                    Called by the EcoCash gateway when a charge finishes. The body is treated as an \
                    UNTRUSTED trigger: only the clientCorrelator is read, and the payment is resolved by \
                    querying EcoCash directly — never from the posted status. Always answers 200 with an \
                    empty body, whatever matched; the reconciler poll remains the authority, so a lost or \
                    forged notification changes nothing.""")
    @ApiResponse(responseCode = "200", description = "Always — the notification was accepted (or ignored)")
    public ResponseEntity<Void> notify(@RequestBody(required = false) String rawBody) {
        String correlator = extractCorrelator(rawBody);
        if (correlator == null) {
            log.warn("[ecocash-notify] notification without a readable clientCorrelator — ignored");
            return ResponseEntity.ok().build();
        }
        paymentRepository.findByEcocashClientCorrelator(correlator).ifPresentOrElse(p -> {
            if (p.getStatus() == Payment.PaymentStatus.TOKEN_ISSUED
                    && p.getPaymentRail() == PaymentRail.ECOCASH) {
                log.info("[ecocash-notify] trigger for paymentReference={} clientCorrelator={} — querying upstream",
                        p.getPaymentReference(), correlator);
                try {
                    ecocashPaymentService.resolveOpenCharge(p);
                } catch (RuntimeException e) {
                    // The poller retries within its interval; a webhook must
                    // never surface our internals to the caller.
                    log.warn("[ecocash-notify] resolve failed for paymentReference={} — poller will retry: {}",
                            p.getPaymentReference(), e.getMessage());
                }
            } else {
                log.info("[ecocash-notify] notification for non-open row paymentReference={} status={} — ignored",
                        p.getPaymentReference(), p.getStatus());
            }
        }, () -> log.info("[ecocash-notify] notification for unknown clientCorrelator — ignored"));
        return ResponseEntity.ok().build();
    }

    /** Only the correlator is read from the body — nothing else is trusted. */
    private String extractCorrelator(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode n = root.path("clientCorrelator");
            return n.isTextual() && !n.asText().isBlank() ? n.asText()
                    : (n.isNumber() ? n.asText() : null);
        } catch (Exception e) {
            return null;
        }
    }
}
