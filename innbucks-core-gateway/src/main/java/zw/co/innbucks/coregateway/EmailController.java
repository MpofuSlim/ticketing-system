package zw.co.innbucks.coregateway;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.core.dto.messenger.EmailPayload;
import zw.co.innbucks.core.dto.messenger.NotificationRequest;
import zw.co.innbucks.core.dto.messenger.enums.NotificationChannel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Internal gateway endpoint for ticketing services to submit an email
 * notification to the InnBucks {@code messenger-interface}.
 *
 * <p>Rides on the canonical v1 path ({@code POST /api/v1/notifications},
 * {@link NotificationRequest}) — same submit-then-reconcile contract as
 * {@link SmsController} / {@link WhatsAppController}: synchronous,
 * {@code reference} as the idempotency / status-lookup key, a downstream
 * rejection becomes 502 and an unreachable upstream becomes 503.
 *
 * <p>Recipients use the {@code to} field (primary); {@code cc} / {@code bcc}
 * are optional. {@code destination} on the wire is the comma-joined primary
 * recipients (messenger-interface's status ledger keys on it; the actual
 * SMTP envelope is taken from the {@link EmailPayload}).
 *
 * <p>No authentication — internal-only; the adapter port (8088) must not be
 * exposed publicly.
 */
@RestController
class EmailController {

    private static final Logger log = LoggerFactory.getLogger(EmailController.class);

    private final NotificationService notificationService;

    EmailController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/notifications/email")
    ResponseEntity<Map<String, String>> send(@RequestBody EmailRequest request) {
        if (request.to() == null || request.to().length == 0
                || java.util.Arrays.stream(request.to()).allMatch(t -> t == null || t.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "to is required"));
        }
        if (request.subject() == null || request.subject().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "subject is required"));
        }
        if (request.body() == null || request.body().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "body is required"));
        }

        String reference = (request.reference() != null && !request.reference().isBlank())
                ? request.reference()
                : "TKT-EMAIL-" + UUID.randomUUID();

        EmailPayload payload = EmailPayload.builder()
                .to(request.to())
                .cc(request.cc())
                .bcc(request.bcc())
                .fromAddress(request.fromAddress())
                .fromName(request.fromName())
                .subject(request.subject())
                .body(request.body())
                .isHtml(request.isHtml())
                .build();

        NotificationRequest notification = NotificationRequest.builder()
                .channel(NotificationChannel.EMAIL)
                .destination(String.join(",", request.to()))
                .reference(reference)
                .participantId(request.participantId())
                .email(payload)
                .build();

        try {
            notificationService.send(notification);
            return ResponseEntity.ok(body(reference, "SUBMITTED", null));
        } catch (FeignException e) {
            log.warn("[email] messenger-interface rejected reference={} -> HTTP {}",
                    reference, e.status());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(body(reference, "FAILED",
                            "messenger-interface rejected: HTTP " + e.status()));
        } catch (Exception e) {
            log.warn("[email] messenger-interface unreachable reference={}: {}",
                    reference, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(body(reference, "FAILED",
                            "messenger-interface unreachable: " + e.getMessage()));
        }
    }

    private static Map<String, String> body(String reference, String status, String error) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("reference", reference);
        m.put("status", status);
        if (error != null) {
            m.put("error", error);
        }
        return m;
    }
}
