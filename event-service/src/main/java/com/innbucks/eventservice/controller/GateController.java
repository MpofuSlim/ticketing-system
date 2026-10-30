package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.dto.ApiResult;
import com.innbucks.eventservice.dto.scanner.GateInviteDecisionDTO;
import com.innbucks.eventservice.dto.scanner.GateResponseDTO;
import com.innbucks.eventservice.dto.scanner.ScannerAccessDTO;
import com.innbucks.eventservice.security.AuthenticatedCaller;
import com.innbucks.eventservice.service.ScannerService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;

/**
 * The super app's side of gate scanning: the signed-in person's events and
 * invites, accepting and declining, and the internal check booking-service
 * makes before every scan.
 *
 * <p><b>Nothing here takes a phone number or an account from a request.</b>
 * Both come from the caller's session (the {@code /auth/exchange} token), so
 * there is no number to point at someone else's teams.
 */
@RestController
@Slf4j
@Tag(name = "Gate scanning", description = "The super app's scanning events and invites.")
public class GateController {

    private static final String GATE_EXAMPLE = """
            {
              "code": "200 OK",
              "message": "Your scanning events",
              "data": {
                "events": [
                  {
                    "scannerId": "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e",
                    "eventId": "5772554b-9c1d-4e2f-a3b4-c5d6e7f8a9b0",
                    "title": "Peterhouse Sixth Form Ball",
                    "venue": "Peterhouse, Marondera",
                    "startDateTime": "2026-11-07T18:00:00+02:00",
                    "endDateTime": "2026-11-08T01:00:00+02:00",
                    "status": "UPCOMING",
                    "organizerName": "Peterhouse Group of Schools"
                  }
                ],
                "invites": [
                  {
                    "scannerId": "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d",
                    "organizerName": "Harare Arena Events",
                    "name": "Tendai Moyo",
                    "invitedAt": "2026-10-30T14:05:12+02:00",
                    "events": [
                      {
                        "eventId": "0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
                        "title": "Jazz on the Lawn",
                        "venue": "Harare Gardens",
                        "startDateTime": "2026-11-14T15:00:00+02:00",
                        "endDateTime": "2026-11-14T21:00:00+02:00"
                      }
                    ]
                  }
                ]
              }
            }""";

    private static final String INVITE_NOT_FOUND_EXAMPLE = """
            { "code": "404 NOT_FOUND", "message": "Invite not found.", "data": { "errorCode": "invite_not_found" } }""";

    private static final String UNAUTHORIZED_EXAMPLE = """
            { "code": "401 UNAUTHORIZED", "message": "Invalid token", "data": null }""";

    private static final String PHONE_REQUIRED_EXAMPLE = """
            { "code": "400 BAD_REQUEST", "message": "Sign in with the InnBucks app to scan tickets.", "data": { "errorCode": "phone_required" } }""";

    private final ScannerService scannerService;
    private final String expectedInternalToken;

    public GateController(ScannerService scannerService,
                          @Value("${innbucks.internal-api-token}") String expectedInternalToken) {
        this.scannerService = scannerService;
        this.expectedInternalToken = expectedInternalToken;
    }

    @GetMapping("/events/gate")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "My scanning events and invites (super app)",
            description = "For the signed-in person: every event of a team they accepted that has not ended, "
                    + "soonest first (`status` UPCOMING, or LIVE between start and end), and every invite "
                    + "waiting for them. Both empty is the normal answer: hide the \"Scan tickets\" tool. The "
                    + "phone and account come from the session; nothing is taken from the request.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Events and invites",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "A scanner", value = GATE_EXAMPLE),
                            @ExampleObject(name = "Not a scanner", value = """
                                    { "code": "200 OK", "message": "Your scanning events", "data": { "events": [], "invites": [] } }""")
                    })),
            @ApiResponse(responseCode = "400", description = "The session carries no phone number",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = PHONE_REQUIRED_EXAMPLE))),
            @ApiResponse(responseCode = "401", description = "No session",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<GateResponseDTO>> gate(Authentication authentication) {
        GateResponseDTO gate = scannerService.gate(
                AuthenticatedCaller.userUuid(authentication), AuthenticatedCaller.phoneNumber(authentication));
        // Display only: scanning itself is checked live on every scan.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePrivate())
                .body(ApiResult.ok("Your scanning events", gate));
    }

    @PostMapping("/events/gate/invites/{scannerId}/accept")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Accept an invite (super app)",
            description = "Binds the team to the account that accepted; only that account scans for it. "
                    + "Idempotent for the account that accepted.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Accepted",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Invite accepted", "data": { "scannerId": "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d", "status": "ACTIVE", "organizerName": "Harare Arena Events" } }"""))),
            @ApiResponse(responseCode = "400", description = "The session carries no phone number",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = PHONE_REQUIRED_EXAMPLE))),
            @ApiResponse(responseCode = "404", description = "Not an invite for this number (also: removed, unknown)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = INVITE_NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "Already declined; the organizer must invite again",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "409 CONFLICT", "message": "You declined this invite. Ask the organizer to invite you again.", "data": { "errorCode": "invite_not_pending" } }""")))
    })
    public ResponseEntity<ApiResult<GateInviteDecisionDTO>> accept(@PathVariable UUID scannerId,
                                                                   Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Invite accepted", scannerService.accept(
                AuthenticatedCaller.userUuid(authentication), AuthenticatedCaller.phoneNumber(authentication),
                scannerId)));
    }

    @PostMapping("/events/gate/invites/{scannerId}/decline")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Decline an invite, or leave a team (super app)",
            description = "Works on a pending invite or an accepted team. Afterwards only the organizer can "
                    + "invite the person again. Idempotent.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Declined",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Invite declined", "data": { "scannerId": "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d", "status": "DECLINED", "organizerName": "Harare Arena Events" } }"""))),
            @ApiResponse(responseCode = "400", description = "The session carries no phone number",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = PHONE_REQUIRED_EXAMPLE))),
            @ApiResponse(responseCode = "404", description = "Not an invite or team for this number",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = INVITE_NOT_FOUND_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<GateInviteDecisionDTO>> decline(@PathVariable UUID scannerId,
                                                                    Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Invite declined", scannerService.decline(
                AuthenticatedCaller.userUuid(authentication), AuthenticatedCaller.phoneNumber(authentication),
                scannerId)));
    }

    /**
     * booking-service, before it reads a ticket: may this account scan this
     * event? The internal trio: this token check, the {@code /events/internal/*}
     * GET permitAll in SecurityConfig, and the gateway's event-internal-deny.
     */
    @Hidden
    @GetMapping("/events/internal/scanner-access")
    public ResponseEntity<ApiResult<ScannerAccessDTO>> scannerAccess(
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
            @RequestParam UUID userUuid,
            @RequestParam UUID eventId) {
        if (!authorizedInternal(internalToken)) {
            log.warn("Unauthorized GET /events/internal/scanner-access — missing or wrong X-Internal-Token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error(HttpStatus.UNAUTHORIZED, "Missing or invalid X-Internal-Token"));
        }
        return ResponseEntity.ok(ApiResult.ok("Scanner access resolved", scannerService.access(userUuid, eventId)));
    }

    private boolean authorizedInternal(String presented) {
        if (expectedInternalToken == null || expectedInternalToken.isBlank() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedInternalToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
