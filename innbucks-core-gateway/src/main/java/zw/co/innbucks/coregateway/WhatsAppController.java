package zw.co.innbucks.coregateway;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.core.dto.messenger.NotificationRequest;
import zw.co.innbucks.core.dto.messenger.WhatsAppPayload;
import zw.co.innbucks.core.dto.messenger.enums.NotificationChannel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Internal gateway endpoint for ticketing services to submit a WhatsApp
 * notification to the InnBucks {@code messenger-interface}.
 *
 * <p>Rides on the canonical v1 path ({@code POST /api/v1/notifications},
 * {@link NotificationRequest}) — the multi-channel successor to the legacy
 * SMS-only {@code POST /api/notifications}. Behaviour mirrors
 * {@link SmsController}:
 * <ul>
 *   <li>Synchronous submission so the outcome reaches the ticketing caller
 *       (a downstream rejection becomes 502, an unreachable upstream becomes
 *       503; on 2xx we return 200 SUBMITTED with the reference).</li>
 *   <li>{@code reference} is caller-assigned and used by messenger as the
 *       idempotency / status-lookup key; a {@code TKT-WA-<uuid>} is generated
 *       when the caller omits it.</li>
 *   <li>Either a {@code templateId} or {@code bodyText} must be provided —
 *       free-text is only deliverable inside WhatsApp's 24h session window,
 *       so template is the default for business-initiated push.</li>
 * </ul>
 *
 * <p>No authentication — internal-only; the adapter port (8088) must not be
 * exposed publicly.
 */
@RestController
class WhatsAppController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppController.class);
    // WhatsApp Cloud API caps a single message body at 1024 chars for free-text
    // and 1600 for templates with variables; 1600 matches the existing SMS guard
    // and the external wasenda gateway the ticketing services use today.
    private static final int MAX_BODY_LENGTH = 1600;

    private final NotificationService notificationService;

    WhatsAppController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/notifications/whatsapp")
    ResponseEntity<Map<String, String>> send(@RequestBody WhatsAppRequest request) {
        if (request.destination() == null || request.destination().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "destination is required"));
        }
        boolean hasTemplate = request.templateId() != null && !request.templateId().isBlank();
        boolean hasBody = request.bodyText() != null && !request.bodyText().isBlank();
        if (!hasTemplate && !hasBody) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "templateId or bodyText is required"));
        }
        if (hasTemplate && hasBody) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "templateId and bodyText are mutually exclusive"));
        }
        if (hasBody && request.bodyText().length() > MAX_BODY_LENGTH) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "bodyText exceeds " + MAX_BODY_LENGTH + " characters"));
        }

        String reference = (request.reference() != null && !request.reference().isBlank())
                ? request.reference()
                : "TKT-WA-" + UUID.randomUUID();

        WhatsAppPayload payload = WhatsAppPayload.builder()
                .templateId(request.templateId())
                .templateVariables(request.templateVariables())
                .bodyText(request.bodyText())
                .mediaUrl(request.mediaUrl())
                .build();

        NotificationRequest notification = NotificationRequest.builder()
                .channel(NotificationChannel.WHATSAPP)
                .destination(request.destination())
                .reference(reference)
                .participantId(request.participantId())
                .whatsApp(payload)
                .build();

        try {
            notificationService.send(notification);
            return ResponseEntity.ok(body(reference, "SUBMITTED", null));
        } catch (FeignException e) {
            log.warn("[whatsapp] messenger-interface rejected reference={} -> HTTP {}",
                    reference, e.status());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(body(reference, "FAILED",
                            "messenger-interface rejected: HTTP " + e.status()));
        } catch (Exception e) {
            log.warn("[whatsapp] messenger-interface unreachable reference={}: {}",
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
