package zw.co.innbucks.coregateway;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.core.dto.messenger.NotificationDto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Internal gateway endpoint for ticketing services to submit an SMS to the
 * InnBucks {@code messenger-interface}.
 *
 * <p>Synchronous: the call blocks until messenger-interface confirms it accepted
 * the notification into its queue, then returns 200 (SUBMITTED). A
 * messenger-interface rejection -> 502; an unreachable messenger-interface
 * (discovery/connectivity failure) -> 503. Surfacing the real submission outcome
 * is what lets the caller fall back to another channel — the ticketing
 * user-service falls back to WhatsApp for OTP/approval delivery. Actual handset
 * delivery is reconciled asynchronously by messenger-interface and is out of
 * scope for this endpoint.
 *
 * <p>No authentication — internal-only; the adapter port (8088) must not be
 * exposed publicly.
 */
@RestController
class SmsController {

    private static final Logger log = LoggerFactory.getLogger(SmsController.class);
    private static final int MAX_SMS_LENGTH = 1600;

    private final MessengerService messengerService;

    SmsController(MessengerService messengerService) {
        this.messengerService = messengerService;
    }

    @PostMapping("/notifications/sms")
    ResponseEntity<Map<String, String>> send(@RequestBody SmsRequest request) {
        if (request.destination() == null || request.destination().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "destination is required"));
        }
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }
        if (request.message().length() > MAX_SMS_LENGTH) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "message exceeds " + MAX_SMS_LENGTH + " characters"));
        }

        String reference = (request.reference() != null && !request.reference().isBlank())
                ? request.reference()
                : "TKT-SMS-" + UUID.randomUUID();
        String senderId = (request.senderId() != null && !request.senderId().isBlank())
                ? request.senderId()
                : "INNBUCKS";

        NotificationDto dto = NotificationDto.builder()
                .destination(request.destination())
                .message(request.message())
                .reference(reference)
                .senderId(senderId)
                .participantId(request.participantId())
                .build();

        try {
            messengerService.send(dto);
            return ResponseEntity.ok(body(reference, "SUBMITTED", null));
        } catch (FeignException e) {
            // Reached messenger-interface; it returned a non-2xx. The body is the
            // contract we want (e.g. required fields, rejected X-Source-Component).
            log.warn("[sms] messenger-interface rejected reference={} -> HTTP {}", reference, e.status());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(body(reference, "FAILED", "messenger-interface rejected: HTTP " + e.status()));
        } catch (Exception e) {
            // Never reached messenger-interface — discovery or connectivity failure.
            log.warn("[sms] messenger-interface unreachable reference={}: {}", reference, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(body(reference, "FAILED", "messenger-interface unreachable: " + e.getMessage()));
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
