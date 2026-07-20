package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.InternalNotifyRequestDTO;
import com.innbucks.userservice.dto.UserContactDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.notification.UserNotificationDispatcher;
import com.innbucks.userservice.repository.UserRepository;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service contact lookup consumed by loyalty-service: given a user's
 * stable {@code user_uuid}, returns that user's phone / email / first name so
 * loyalty can notify them (WhatsApp → SMS) that they've been attached to a
 * tenant. loyalty only ever holds the user's UUID, so it can't reach the phone
 * any other way.
 *
 * <p>Gated by the shared {@code X-Internal-Token} header (constant-time compare
 * inside {@link InternalTokenAuthorizer}) — the caller is another backend, not a
 * logged-in user. Mirrors {@link InternalMerchantAssignmentController} and
 * {@link InternalTenantLookupController}; class-level {@link Hidden} keeps it out
 * of public Swagger, and the gateway additionally blocks {@code /users/internal/**}
 * at the edge via the {@code user-internal-deny} route, so this is unreachable
 * from the public internet.
 */
@RestController
@RequestMapping("/users/internal")
@Slf4j
@Hidden
public class InternalUserLookupController {

    private final UserRepository userRepository;
    private final InternalTokenAuthorizer tokenAuthorizer;
    private final UserNotificationDispatcher notificationDispatcher;

    public InternalUserLookupController(UserRepository userRepository,
                                        InternalTokenAuthorizer tokenAuthorizer,
                                        UserNotificationDispatcher notificationDispatcher) {
        this.userRepository = userRepository;
        this.tokenAuthorizer = tokenAuthorizer;
        this.notificationDispatcher = notificationDispatcher;
    }

    @GetMapping("/{userUuid}/contact")
    @Operation(summary = "(S2S) Resolve a user's contact details by user_uuid",
            description = "Returns the phone number, email and first name for the user with the given "
                    + "user_uuid. loyalty-service uses this to notify a user (WhatsApp then SMS) that "
                    + "they've been added to a tenant. Requires X-Internal-Token; 404 when the uuid "
                    + "resolves to no user.")
    public ResponseEntity<?> getContact(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID userUuid,
            HttpServletRequest request) {
        if (!tokenAuthorizer.authorized(token, request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return userRepository.findByUserUuid(userUuid)
                .<ResponseEntity<?>>map(user -> {
                    UserContactDTO body = toContact(user);
                    log.debug("Internal contact lookup resolved user_uuid={}", userUuid);
                    return ResponseEntity.ok(ApiResult.ok("User contact resolved", body));
                })
                .orElseGet(() -> {
                    log.debug("Internal contact lookup found no user for user_uuid={}", userUuid);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(ApiResult.error(HttpStatus.NOT_FOUND, "User not found"));
                });
    }

    @PostMapping("/{userUuid}/notify")
    @Operation(summary = "(S2S) Send a best-effort notification to a user by user_uuid",
            description = "Resolves the user's channels and sends the supplied subject + message "
                    + "email-first, WhatsApp-fallback. Used by event-service to tell an organizer their "
                    + "event was approved. Fire-and-forget: returns 202 immediately and delivery runs "
                    + "async/best-effort. Requires X-Internal-Token; 404 when the uuid resolves to no user, "
                    + "400 when subject or message is blank.")
    public ResponseEntity<?> notifyUser(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID userUuid,
            @RequestBody(required = false) InternalNotifyRequestDTO body,
            HttpServletRequest request) {
        // Token check FIRST so the endpoint stays fail-closed before it reveals
        // anything about the body or the user.
        if (!tokenAuthorizer.authorized(token, request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (body == null || body.subject() == null || body.subject().isBlank()
                || body.message() == null || body.message().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error(HttpStatus.BAD_REQUEST, "subject and message are required"));
        }
        return userRepository.findByUserUuid(userUuid)
                .<ResponseEntity<?>>map(user -> {
                    notificationDispatcher.dispatch(
                            user.getEmail(), user.getPhoneNumber(), body.subject(), body.message());
                    log.debug("Internal notify dispatched user_uuid={}", userUuid);
                    return ResponseEntity.accepted()
                            .body(ApiResult.ok("Notification queued", null));
                })
                .orElseGet(() -> {
                    log.debug("Internal notify found no user for user_uuid={}", userUuid);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(ApiResult.error(HttpStatus.NOT_FOUND, "User not found"));
                });
    }

    private static UserContactDTO toContact(User user) {
        return UserContactDTO.builder()
                .userUuid(user.getUserUuid())
                .phoneNumber(user.getPhoneNumber())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .build();
    }
}
