package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.notification.StaffNotificationService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * (S2S) Tells the platform staff a notice type is for: event-service calls it
 * when an event is submitted for review, or published while the review step is
 * switched off. The audience is fixed per type in
 * {@link StaffNotificationService}; the caller only says what happened.
 *
 * <p>Internal like the rest of {@code /users/internal/**}: the shared
 * {@code X-Internal-Token} is checked first, constant-time
 * ({@link InternalTokenAuthorizer}); {@code SecurityConfig} permits the path so
 * that check runs; the gateway's {@code user-internal-deny} route keeps it off
 * the public internet. {@link Hidden} keeps it out of public Swagger.
 */
@RestController
@RequestMapping("/users/internal/staff-notifications")
@Slf4j
@Hidden
public class InternalStaffNotificationController {

    static final int MAX_SUBJECT = 200;
    static final int MAX_MESSAGE = 2000;

    private final InternalTokenAuthorizer tokenAuthorizer;
    private final StaffNotificationService staffNotifications;

    public InternalStaffNotificationController(InternalTokenAuthorizer tokenAuthorizer,
                                               StaffNotificationService staffNotifications) {
        this.tokenAuthorizer = tokenAuthorizer;
        this.staffNotifications = staffNotifications;
    }

    /** The notice. Everything past {@code message} is optional. */
    public record StaffNotifyRequest(String type, String subject, String message, String severity,
                                     String actorId, String actorName,
                                     String subjectKind, String subjectId, String deepLink) {}

    @PostMapping
    @Operation(summary = "(S2S) Notify the platform staff a notice type is for",
            description = "Records an in-app notification for every active holder of the type's roles and "
                    + "emails each, best-effort. 202 with the number reached; 400 for a type with no staff "
                    + "audience, or a blank or overlong subject or message; 401 without the internal token.")
    public ResponseEntity<?> notifyStaff(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody(required = false) StaffNotifyRequest body,
            HttpServletRequest request) {
        if (!tokenAuthorizer.authorized(token, request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (body == null || !StaffNotificationService.knownType(body.type())) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error(HttpStatus.BAD_REQUEST, "Unknown staff notification type"));
        }
        if (blankOrLonger(body.subject(), MAX_SUBJECT) || blankOrLonger(body.message(), MAX_MESSAGE)) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error(HttpStatus.BAD_REQUEST,
                            "subject (up to " + MAX_SUBJECT + ") and message (up to " + MAX_MESSAGE
                                    + ") are required"));
        }
        int reached = staffNotifications.notifyStaff(new StaffNotificationService.StaffNotice(
                body.type(), body.subject().trim(), body.message().trim(), body.severity(),
                clip(body.actorId(), 64), clip(body.actorName(), 200),
                clip(body.subjectKind(), 64), clip(body.subjectId(), 64), body.deepLink()));
        return ResponseEntity.accepted()
                .body(ApiResult.ok("Staff notified", Map.of("recipients", reached)));
    }

    private static boolean blankOrLonger(String value, int max) {
        return value == null || value.isBlank() || value.trim().length() > max;
    }

    private static String clip(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
