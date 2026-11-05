package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.notification.ScannerInviteNotifier;
import com.innbucks.userservice.util.MsisdnValidator;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * (S2S) event-service asks for the gate-scanner invite message to go to a
 * phone number: WhatsApp, SMS when WhatsApp fails ({@link ScannerInviteNotifier}).
 *
 * <p>The body names the number, the organizer and the event; the sentence
 * around them is fixed in user-service, so the shared internal token cannot be
 * used to send anyone a message of the caller's choosing.
 *
 * <p>Internal like the rest of {@code /users/internal/**}: the shared
 * {@code X-Internal-Token} is checked first, constant-time
 * ({@link InternalTokenAuthorizer}); {@code SecurityConfig} permits the path so
 * that check runs; the gateway's {@code user-internal-deny} route keeps it off
 * the public internet. {@link Hidden} keeps it out of public Swagger.
 */
@RestController
@RequestMapping("/users/internal/scanner-invites")
@Hidden
public class InternalScannerInviteController {

    private final InternalTokenAuthorizer tokenAuthorizer;
    private final ScannerInviteNotifier notifier;
    private final String deploymentCountry;

    public InternalScannerInviteController(InternalTokenAuthorizer tokenAuthorizer,
                                           ScannerInviteNotifier notifier,
                                           @Value("${innbucks.country:ZW}") String deploymentCountry) {
        this.tokenAuthorizer = tokenAuthorizer;
        this.notifier = notifier;
        this.deploymentCountry = deploymentCountry;
    }

    /** {@code organizerName}, {@code eventTitle} and {@code scannerId} are optional. */
    public record ScannerInviteRequest(String phoneNumber, String organizerName, String eventTitle,
                                       UUID scannerId) {}

    @PostMapping
    @Operation(summary = "(S2S) Send the gate-scanner invite message to a phone",
            description = "Queues one WhatsApp message (SMS if WhatsApp fails) telling the person an organizer "
                    + "added them as gate staff and to open the InnBucks app to accept. 202 once queued; 400 "
                    + "for a missing or invalid phone number; 401 without the internal token.")
    public ResponseEntity<ApiResult<Object>> send(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody(required = false) ScannerInviteRequest body,
            HttpServletRequest request) {
        if (!tokenAuthorizer.authorized(token, request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String phone = body == null ? null
                : MsisdnValidator.normalizeToE164(body.phoneNumber(), deploymentCountry).orElse(null);
        if (phone == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.of(HttpStatus.BAD_REQUEST, "A valid phoneNumber is required",
                            Map.of("errorCode", "invalid_phone")));
        }
        notifier.send(phone, body.organizerName(), body.eventTitle(), body.scannerId());
        return ResponseEntity.accepted().body(ApiResult.of(HttpStatus.ACCEPTED, "Invite message queued", null));
    }
}
