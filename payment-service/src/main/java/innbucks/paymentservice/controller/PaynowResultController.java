package innbucks.paymentservice.controller;

import innbucks.paymentservice.service.PaynowPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Paynow's result URL: Paynow POSTs here whenever a payment's status changes
 * ({@code docs/api/paynow.md}).
 *
 * <p><b>A trigger, never the truth.</b> The update is acted on only when its
 * hash verifies under the payment's own integration key, and even then only by
 * checking the payment with Paynow (a poll), as Paynow recommends. A forged or
 * replayed update can at most make us ask Paynow a question.
 *
 * <p>The answer is 200, whatever matched, except when we could not check the
 * payment: then 503, and Paynow sends the update again (up to ten times).
 */
@Slf4j
@RestController
@RequestMapping("/payments/paynow")
@RequiredArgsConstructor
@Tag(name = "Paynow result URL",
        description = "Machine endpoint Paynow posts payment status updates to. Not for FE use.")
public class PaynowResultController {

    private final PaynowPaymentService paynowPaymentService;

    @PostMapping("/result")
    @SecurityRequirements   // Paynow cannot send our JWTs
    @Operation(summary = "Paynow status update (machine endpoint)",
            description = """
                    Called by Paynow when a payment's status changes. Acted on only when its hash verifies, \
                    and then only by checking the payment with Paynow. 200 when handled or ignored; 503 when \
                    the payment could not be checked, so Paynow sends it again.""")
    @ApiResponse(responseCode = "200", description = "Handled or ignored")
    @ApiResponse(responseCode = "503", description = "Could not check the payment now; Paynow resends")
    public ResponseEntity<Void> result(@RequestBody(required = false) String rawBody, HttpServletRequest request) {
        String body = rawBody;
        if (body == null || body.isBlank()) {
            // Something upstream already parsed the form: rebuild it in the
            // order the container kept, which is the order Paynow sent.
            body = fromParameters(request.getParameterMap());
        }
        PaynowPaymentService.UpdateAck ack;
        try {
            ack = paynowPaymentService.onResultUpdate(body);
        } catch (RuntimeException e) {
            log.warn("[paynow-result] update could not be handled — asking Paynow to resend: {}", e.getMessage());
            ack = PaynowPaymentService.UpdateAck.RETRY;
        }
        return ack == PaynowPaymentService.UpdateAck.RETRY
                ? ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
                : ResponseEntity.ok().build();
    }

    static String fromParameters(Map<String, String[]> parameters) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> e : parameters.entrySet()) {
            for (String v : e.getValue()) {
                if (!sb.isEmpty()) sb.append('&');
                sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }
}
