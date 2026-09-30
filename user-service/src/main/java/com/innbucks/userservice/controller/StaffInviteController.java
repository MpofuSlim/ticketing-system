package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.StaffDTOs;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.StaffInviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staff invite link's two calls (V44) — public, because the token IS the
 * credential: {@code permitAll} through {@code /auth/**}, skipped by JwtFilter,
 * and routed by the gateway's IP-limited, fail-safe
 * {@code auth-staff-invite-route} (POST only).
 *
 * <p>Both take the token in a POST body, never a URL, so it stays out of every
 * access log and browser history; the console reads it from the link's
 * fragment. Every failure is one opaque 400 {@code invite_invalid}.
 */
@RestController
@RequestMapping("/auth/staff-invite")
@RequiredArgsConstructor
@Tag(name = "Staff invites", description = "Redeem the single-use link a new staff member is emailed.")
@SecurityRequirements()
public class StaffInviteController {

    private static final String INVITE_INVALID = """
            {
              "code": "400 BAD_REQUEST",
              "message": "This invite link is no longer valid. Ask your administrator to send a new one.",
              "data": { "errorCode": "invite_invalid" }
            }
            """;

    private final StaffInviteService staffInviteService;

    @PostMapping("/inspect")
    @Operation(summary = "Who an invite is for (does not use it up)",
            description = "Read-only: returns the first name, email and expiry so the set-password page can greet "
                    + "the person. Does NOT consume the token. A POST so the token never appears in a URL. "
                    + "Every failure — unknown, used, revoked or expired, or an account that can no longer "
                    + "accept — is the same 400 `invite_invalid` (never 401, so a console's session "
                    + "interceptor leaves it alone).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The invite is live",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Live invite",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Invite is valid",
                                      "data": {
                                        "firstName": "Tariro",
                                        "email": "tariro.moyo@innbucks.co.zw",
                                        "expiresAt": "2026-10-02T10:15:02+02:00"
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Invalid link, or no token",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Invite invalid", value = INVITE_INVALID),
                            @ExampleObject(name = "No token", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "token": "token is required" } }
                                    """)
                    }))
    })
    public ResponseEntity<ApiResult<StaffDTOs.InviteInspection>> inspect(
            @Valid @RequestBody StaffDTOs.InviteTokenRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.ok("Invite is valid", staffInviteService.inspect(request.token())));
    }

    @PostMapping("/accept")
    @Operation(summary = "Set a password with an invite",
            description = """
                    Uses the invite up: sets the password (8–72 characters), confirms the email and ends
                    every session the account held. **It issues no session** — the person then signs in with
                    their email and new password, and sets up two-step verification at that first sign-in.

                    A password mismatch is checked before the link is used, so a typo does not burn it.
                    Every other failure is the same 400 `invite_invalid` — the link is single-use, expires,
                    and stops working when a newer invite is sent or the account is deactivated.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password set; sign in next",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Accepted",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Your password is set. Sign in to finish setting up two-step verification.",
                                      "data": { "email": "tariro.moyo@innbucks.co.zw" }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Invalid link, passwords that differ, or validation",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Invite invalid", value = INVITE_INVALID),
                            @ExampleObject(name = "Passwords differ", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Passwords do not match", "data": null }
                                    """),
                            @ExampleObject(name = "Password too short", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Validation failed",
                                      "data": { "newPassword": "Password must be between 8 and 72 characters" }
                                    }
                                    """)
                    })),
            @ApiResponse(responseCode = "503", description = "The audit row could not be written; nothing changed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Audit unavailable",
                            value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "We couldn't record this change, so it wasn't made. Try again.",
                                      "data": { "errorCode": "audit_unavailable" }
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<StaffDTOs.InviteAccepted>> accept(
            @Valid @RequestBody StaffDTOs.AcceptRequest request, HttpServletRequest httpRequest) {
        StaffDTOs.InviteAccepted accepted = staffInviteService.accept(request,
                new AuditContext(clientIp(httpRequest), httpRequest.getHeader("User-Agent")));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.ok(StaffInviteService.ACCEPTED_MESSAGE, accepted));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            if (!first.isEmpty()) return first;
        }
        return request.getRemoteAddr();
    }
}
