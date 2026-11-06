package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.SalesAgentInviteDTOs;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.OtpService;
import com.innbucks.userservice.service.SalesAgentInviteService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sales-agent portal invite link's three calls (V56). Public, because the
 * token IS the credential: {@code permitAll} through {@code /auth/**}, skipped
 * by JwtFilter, and routed by the gateway's IP-limited, fail-safe
 * {@code auth-sales-agent-invite-route} (POST only).
 *
 * <p>Every call takes the token in a POST body, never a URL, so it stays out of
 * every access log and browser history; the portal reads it from the link's
 * fragment. An unknown, used, revoked or expired link is one opaque 400
 * {@code invite_invalid}.
 */
@RestController
@RequestMapping("/auth/sales-agent-invite")
@RequiredArgsConstructor
@Tag(name = "Sales-agent invites",
        description = "Set up Foundry portal sign-in for a sales agent from the link an organiser sent.")
@SecurityRequirements()
public class SalesAgentInviteController {

    private static final String INVITE_INVALID = """
            {
              "code": "400 BAD_REQUEST",
              "message": "This invite link is no longer valid. Ask the organiser to send a new one.",
              "data": { "errorCode": "invite_invalid" }
            }
            """;

    private static final String NO_TOKEN = """
            { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "token": "token is required" } }
            """;

    private static final String ACCOUNT_NOT_ELIGIBLE = """
            {
              "code": "409 CONFLICT",
              "message": "The InnBucks account on this phone number can't be used to sell tickets on the portal. Contact InnBucks support.",
              "data": { "errorCode": "account_not_eligible" }
            }
            """;

    private static final String EMAIL_MISMATCH = """
            {
              "code": "409 CONFLICT",
              "message": "The InnBucks account on this phone number already uses a different email address. Ask the organiser to send the invite to that address instead.",
              "data": { "errorCode": "email_mismatch" }
            }
            """;

    private static final String EMAIL_IN_USE = """
            {
              "code": "409 CONFLICT",
              "message": "This email address already belongs to another InnBucks account. Ask the organiser to send the invite to a different address.",
              "data": { "errorCode": "email_in_use" }
            }
            """;

    private static final String EMAIL_DOMAIN_RESERVED = """
            {
              "code": "400 BAD_REQUEST",
              "message": "InnBucks staff addresses can't be used here. Your administrator will invite you.",
              "data": { "errorCode": "email_domain_reserved", "field": "email" }
            }
            """;

    private final SalesAgentInviteService salesAgentInvites;

    @PostMapping("/inspect")
    @Operation(summary = "Who an invite is from and for (does not use it up)",
            description = "Read-only: the organiser's name, the email the person will sign in with, the phone the "
                    + "code goes to (masked) and when the link expires. Does NOT consume the token. A POST so the "
                    + "token never appears in a URL. Every failure is the same 400 `invite_invalid` (never 401, "
                    + "so a portal session interceptor leaves it alone).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The invite is live",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Live invite",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Invite is valid",
                                      "data": {
                                        "organizerName": "Harare Arena Events",
                                        "email": "tendai.moyo@gmail.com",
                                        "maskedPhone": "****4567",
                                        "expiresAt": "2026-11-09T12:00:00+02:00"
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Invalid link, or no token",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Invite invalid", value = INVITE_INVALID),
                            @ExampleObject(name = "No token", value = NO_TOKEN)
                    }))
    })
    public ResponseEntity<ApiResult<SalesAgentInviteDTOs.Inspection>> inspect(
            @Valid @RequestBody SalesAgentInviteDTOs.TokenRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.ok(SalesAgentInviteService.INSPECT_MESSAGE,
                        salesAgentInvites.inspect(request.token())));
    }

    @PostMapping("/phone-code")
    @Operation(summary = "Send the code that proves the invite's phone",
            description = """
                    Sends a six-digit code by SMS (WhatsApp when SMS fails) to the phone number on the invite. It
                    does not use the link up and can be asked for again (the usual code quota applies; a new code
                    replaces the last one).

                    Only the link is checked: the answer is the same whatever account the phone holds, so it
                    never says whether the number has an InnBucks account. A refusal about the account or the email
                    comes from accept, once the code has proved the phone.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Code sent",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Sent",
                            value = """
                                    { "code": "200 OK", "message": "We sent a code to the phone number on this invite.", "data": null }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Invalid link or no token",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Invite invalid", value = INVITE_INVALID),
                            @ExampleObject(name = "No token", value = NO_TOKEN)
                    })),
            @ApiResponse(responseCode = "429", description = "Too many codes asked for this phone",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Quota",
                            value = """
                                    {
                                      "code": "429 TOO_MANY_REQUESTS",
                                      "message": "Too many OTP requests. Try again in 30 minute(s).",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<Void>> phoneCode(@Valid @RequestBody SalesAgentInviteDTOs.TokenRequest request) {
        try {
            salesAgentInvites.sendPhoneCode(request.token());
        } catch (OtpService.OtpRateLimitException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore())
                    .body(ApiResult.error(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.ok(SalesAgentInviteService.CODE_SENT_MESSAGE, null));
    }

    @PostMapping("/accept")
    @Operation(summary = "Set up Foundry portal sign-in with an invite",
            description = """
                    Uses the invite up. The InnBucks account on the invite's phone (created when there is none)
                    gets the invite's email (when it has none), the chosen password (8 to 72 characters) and the
                    CUSTOMER and SALES_AGENT roles. **It issues no session**: the person signs in next with their
                    email and password, and sets up two-step verification at that first sign-in. An account that
                    already existed is signed out everywhere and told its password changed.

                    A wrong code is checked before the link is used, so it does not burn the link (three wrong
                    codes void the code: ask for a new one). An unknown, used, revoked or expired link is the same
                    400 `invite_invalid`. The code is checked before anything about the account on the phone or the
                    email, so a 409 (or `email_domain_reserved`) only ever answers someone who proved the phone, and
                    uses that code up.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sign-in set up; sign in next",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Accepted",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Your sign-in is set up. Sign in to the Foundry portal with your email and new password, then set up two-step verification.",
                                      "data": { "email": "tendai.moyo@gmail.com", "next": "SIGN_IN" }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Invalid link, wrong code, a staff address, or validation",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Invite invalid", value = INVITE_INVALID),
                            @ExampleObject(name = "Wrong code", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "That code is wrong or has expired. Ask for a new one.",
                                      "data": { "errorCode": "phone_code_invalid" }
                                    }
                                    """),
                            @ExampleObject(name = "Staff address", value = EMAIL_DOMAIN_RESERVED),
                            @ExampleObject(name = "Password too short", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Validation failed",
                                      "data": { "password": "Password must be between 8 and 72 characters" }
                                    }
                                    """)
                    })),
            @ApiResponse(responseCode = "409", description = "The account on the phone, or the email, can't be used",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Account not eligible", value = ACCOUNT_NOT_ELIGIBLE),
                            @ExampleObject(name = "Email mismatch", value = EMAIL_MISMATCH),
                            @ExampleObject(name = "Email in use", value = EMAIL_IN_USE),
                            @ExampleObject(name = "Try again", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "Something changed while we were setting up your sign-in. Ask for a new code and try again.",
                                      "data": { "errorCode": "try_again" }
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
    public ResponseEntity<ApiResult<SalesAgentInviteDTOs.Accepted>> accept(
            @Valid @RequestBody SalesAgentInviteDTOs.AcceptRequest request, HttpServletRequest httpRequest) {
        SalesAgentInviteDTOs.Accepted accepted = salesAgentInvites.accept(request,
                new AuditContext(clientIp(httpRequest), httpRequest.getHeader("User-Agent")));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.ok(SalesAgentInviteService.ACCEPTED_MESSAGE, accepted));
    }

    private static String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.strip();
        return request.getRemoteAddr();
    }
}
