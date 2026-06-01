package zw.co.innbucks.coregateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.core.dto.messenger.NotificationDto;

import java.util.Map;
import java.util.UUID;

/**
 * Internal gateway endpoint for ticketing services to dispatch SMS via the
 * InnBucks {@code messenger-interface}.
 *
 * <p>Accepts the notification, returns 202 Accepted immediately, and dispatches
 * the Feign call to {@code messenger-interface} asynchronously via
 * {@link MessengerService}. The caller is not notified of delivery outcome;
 * check the adapter logs (reference field) for delivery status.
 *
 * <p>No authentication is applied — this endpoint is internal-only and the
 * adapter port (8088) must not be exposed publicly.
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

        log.info("[sms] Accepted notification for dispatch reference={} destination={}",
                reference, request.destination());
        messengerService.send(dto);
        return ResponseEntity.accepted()
                .body(Map.of("reference", reference, "status", "ACCEPTED"));
    }
}
