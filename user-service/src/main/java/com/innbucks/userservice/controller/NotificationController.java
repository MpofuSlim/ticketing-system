package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.NotificationDTO;
import com.innbucks.userservice.entity.Notification;
import com.innbucks.userservice.notification.NotificationService;
import com.innbucks.userservice.security.AuthenticatedCaller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * The console's notification bell.
 *
 * <p>Every endpoint is scoped to the CALLER — the recipient comes from the
 * JWT's {@code userUuid} claim and there is no path or query parameter naming a
 * user. That is a property of the shape rather than a check that could be
 * forgotten: there is nothing here to point at somebody else.
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Slf4j
@Validated
@Tag(name = "Notifications", description = "The signed-in user's own in-app notifications.")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "List my notifications",
            description = "Newest first. `unreadOnly=true` narrows to the unread ones. Scoped to the "
                    + "caller — there is no way to read another user's notifications.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Notifications retrieved",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Notifications retrieved",
                              "data": {
                                "content": [
                                  {
                                    "id": "0d4f2b1a-7c3e-4a58-9b6d-2e1f8c7a4b03",
                                    "type": "SERVICE_REQUEST_APPROVED",
                                    "title": "Marketplace access approved",
                                    "body": "Your request for Marketplace access was approved. Sign in again to see it.",
                                    "severity": "SUCCESS",
                                    "createdAt": "2026-09-09T08:30:00Z",
                                    "readAt": null,
                                    "subject": { "kind": "SERVICE_REQUEST", "id": "14" },
                                    "deepLink": "/system-users/service-requests?highlight=14"
                                  }
                                ],
                                "totalElements": 1,
                                "totalPages": 1,
                                "number": 0,
                                "size": 20
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Token carries no userUuid claim",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "400 BAD_REQUEST", "message": "Your session is missing your user identity. Please sign out and log in again.", "data": null }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid JWT")
    })
    public ResponseEntity<ApiResult<Page<NotificationDTO>>> list(
            Authentication authentication,
            @Parameter(description = "Only notifications not yet read")
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        UUID me = requireCaller(authentication);
        Page<NotificationDTO> body = notificationService
                .list(me, unreadOnly, PageRequest.of(page, size))
                .map(NotificationDTO::from);
        return ResponseEntity.ok(ApiResult.ok("Notifications retrieved", body));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "How many unread notifications I have",
            description = "The badge. One small response instead of the three list fetches the console "
                    + "used to count.\n\n"
                    + "**Send `If-None-Match` with the previous `ETag`** and an unchanged badge answers "
                    + "**304** with no body — which is what makes a short poll interval cheap. The "
                    + "validator pairs the count with the newest arrival time, so it changes both when "
                    + "something arrives and when something is read.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Current unread count",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Unread count retrieved", "data": { "unread": 3 } }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "304",
                    description = "Unchanged since the supplied If-None-Match — no body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Token carries no userUuid claim"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid JWT")
    })
    public ResponseEntity<ApiResult<Map<String, Long>>> unreadCount(
            Authentication authentication,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        UUID me = requireCaller(authentication);
        long unread = notificationService.unreadCount(me);
        String etag = notificationService.unreadEtag(me, unread);
        if (etag.equals(ifNoneMatch)) {
            // No body, and deliberately still carrying the ETag so the client's
            // stored validator stays fresh across a long run of 304s.
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag)
                .body(ApiResult.ok("Unread count retrieved", Map.of("unread", unread)));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one notification read",
            description = "Idempotent — re-reading keeps the ORIGINAL read time, since when they first "
                    + "saw it is the answer with any value. Another user's notification is a 404, "
                    + "indistinguishable from one that does not exist.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Marked read",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Notification marked read",
                              "data": {
                                "id": "0d4f2b1a-7c3e-4a58-9b6d-2e1f8c7a4b03",
                                "type": "SERVICE_REQUEST_APPROVED",
                                "title": "Marketplace access approved",
                                "body": "Your request for Marketplace access was approved. Sign in again to see it.",
                                "severity": "SUCCESS",
                                "createdAt": "2026-09-09T08:30:00Z",
                                "readAt": "2026-09-09T09:02:11Z"
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No such notification for this caller",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "404 NOT_FOUND", "message": "Notification not found: 0d4f2b1a-7c3e-4a58-9b6d-2e1f8c7a4b03", "data": null }
                            """)))
    })
    public ResponseEntity<ApiResult<NotificationDTO>> markRead(
            Authentication authentication, @PathVariable UUID id) {
        UUID me = requireCaller(authentication);
        Notification updated = notificationService.markRead(me, id);
        return ResponseEntity.ok(ApiResult.ok("Notification marked read",
                NotificationDTO.from(updated)));
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all my notifications read",
            description = "One statement, not a page-and-save loop — an admin back from leave can have "
                    + "thousands unread. Returns how many were actually flipped; 0 when everything was "
                    + "already read.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "All marked read",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Notifications marked read", "data": { "marked": 7 } }
                            """)))
    })
    public ResponseEntity<ApiResult<Map<String, Integer>>> markAllRead(Authentication authentication) {
        UUID me = requireCaller(authentication);
        int marked = notificationService.markAllRead(me);
        return ResponseEntity.ok(ApiResult.ok("Notifications marked read", Map.of("marked", marked)));
    }

    /**
     * The recipient is the caller, full stop. A token with no {@code userUuid}
     * claim is a 400 telling them to sign in again — never an empty list, which
     * would look like "you have no notifications" and hide a broken session.
     */
    private static UUID requireCaller(Authentication authentication) {
        UUID me = AuthenticatedCaller.userUuid(authentication);
        if (me == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Your session is missing your user identity. Please sign out and log in again.");
        }
        return me;
    }
}
