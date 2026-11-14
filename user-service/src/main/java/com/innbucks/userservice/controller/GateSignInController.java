package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.AuthResponseDTO;
import com.innbucks.userservice.dto.GateSignInDTOs;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.GateSignInService;
import com.innbucks.userservice.service.OtpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Organizer app's gate sign-in (V57): a phone number and an SMS code, for a
 * session that can only scan. Public: {@code permitAll} through
 * {@code /auth/**}, skipped by JwtFilter, and routed by the gateway's IP-keyed,
 * fail-safe {@code auth-gate-route} (POST, these two exact paths).
 */
@RestController
@RequestMapping("/auth/gate")
@RequiredArgsConstructor
@Tag(name = "Gate sign-in",
        description = "Sign a gate scanner in to the Organizer app with their phone number and an SMS code.")
@SecurityRequirements()
public class GateSignInController {

    private static final String INVALID_PHONE = """
            {
              "code": "400 BAD_REQUEST",
              "message": "Enter a valid phone number.",
              "data": { "errorCode": "invalid_phone" }
            }
            """;

    private static final String VALIDATION = """
            { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "phoneNumber": "phoneNumber is required" } }
            """;

    private static final String QUOTA = """
            {
              "code": "429 TOO_MANY_REQUESTS",
              "message": "Too many OTP requests. Try again in 30 minute(s).",
              "data": null
            }
            """;

    private static final String WRONG_CELL = """
            {
              "code": "409 CONFLICT",
              "message": "InnBucks isn't available in South Africa yet — we'll let you know when we launch there.",
              "data": { "errorCode": "wrong_cell", "homeCountry": "ZA", "homeBaseUrl": null }
            }
            """;

    private final GateSignInService gateSignIn;

    @PostMapping("/code")
    @Operation(summary = "Send a gate sign-in code",
            description = """
                    Sends a six-digit code by SMS (WhatsApp when SMS fails) to the number, **only when the number
                    is a gate scanner on an event's team** (any organiser, scanning switched on, not declined). The
                    answer is the same 200 for every number, so it never says whether a number is on a team: show
                    "If this number is on a gate team, a code is on its way."

                    The code lasts 5 minutes; asking again replaces it. A number may ask 3 times in 10 minutes,
                    counted whether or not a code was sent, then 429. A number on a team whose SMS and WhatsApp
                    both fail gets a 502 (nothing was sent; it can try again).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Accepted (a code is sent only to a team number)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Accepted",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "If this number is on an event's gate team, we've sent it a sign-in code.",
                                      "data": null
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Not a phone number, or no number",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Invalid phone", value = INVALID_PHONE),
                            @ExampleObject(name = "Missing", value = VALIDATION)
                    })),
            @ApiResponse(responseCode = "409", description = "A number from another InnBucks region",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Wrong cell", value = WRONG_CELL))),
            @ApiResponse(responseCode = "429", description = "Too many codes asked for this number",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Quota", value = QUOTA))),
            @ApiResponse(responseCode = "502", description = "SMS and WhatsApp both failed; nothing was sent",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not sent",
                            value = """
                                    {
                                      "code": "502 BAD_GATEWAY",
                                      "message": "We couldn't send your verification message. Please try again in a moment.",
                                      "data": null
                                    }
                                    """))),
            @ApiResponse(responseCode = "503", description = "Could not check gate teams right now; try again",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Unavailable",
                            value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "Gate sign-in isn't available right now. Try again in a minute.",
                                      "data": { "errorCode": "gate_sign_in_unavailable" }
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<Void>> code(@Valid @RequestBody GateSignInDTOs.CodeRequest request) {
        try {
            gateSignIn.requestCode(request.phoneNumber());
        } catch (OtpService.OtpRateLimitException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore())
                    .body(ApiResult.error(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.ok(GateSignInService.CODE_SENT_MESSAGE, null));
    }

    @PostMapping("/sign-in")
    @Operation(summary = "Sign in to the Organizer app with the code",
            description = """
                    Uses the code up and returns an access token (15 minutes) and a refresh token (7 days) for the
                    InnBucks account on the number (a customer account is created when there is none, the same
                    one the InnBucks app signs in to).

                    **The session can only scan.** Its one role is `GATE_SCANNER`, with no permissions. It reaches
                    `GET /events/gate`, accepting or declining a gate invite, `POST /tickets/scan`,
                    `POST /tickets/lookup`, `GET /scans/me` and `GET /scans/me/stats`, and the public event reads.
                    Everything else answers 403 with `data.errorCode: gate_session_only`. Which events it may scan
                    is the scanner's live team membership, read at every scan.

                    Renew it with `POST /auth/refresh` (refresh token as Bearer, the same `X-Device-Id`): it stays
                    gate-only for its whole life. A wrong, expired or never-sent code is one 400 `code_invalid`;
                    three wrong codes void the code, so ask for a new one.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Signed in",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Signed in for gate scanning",
                                      "data": {
                                        "token": "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlcyI6WyJHQVRFX1NDQU5ORVIiXX0.sig",
                                        "refreshToken": "eyJhbGciOiJIUzI1NiJ9.eyJ0eXAiOiJyZWZyZXNoIn0.sig",
                                        "roles": ["GATE_SCANNER"],
                                        "permissions": [],
                                        "defaultServices": [],
                                        "mfaRequired": false,
                                        "mustChangePassword": false,
                                        "tier": 1,
                                        "verified": false
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Wrong or expired code, or not a phone number",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Wrong code", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "That code is wrong or has expired. Ask for a new one.",
                                      "data": { "errorCode": "code_invalid" }
                                    }
                                    """),
                            @ExampleObject(name = "Invalid phone", value = INVALID_PHONE)
                    })),
            @ApiResponse(responseCode = "409", description = "A number from another InnBucks region",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Wrong cell", value = WRONG_CELL)))
    })
    public ResponseEntity<ApiResult<AuthResponseDTO>> signIn(
            HttpServletRequest httpRequest,
            @Valid @RequestBody GateSignInDTOs.SignInRequest request,
            @Parameter(description = "A stable id for this install of the app; the refresh token is bound to it")
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        AuthResponseDTO response = gateSignIn.signIn(request.phoneNumber(), request.code(), deviceId,
                new AuditContext(clientIp(httpRequest), httpRequest.getHeader("User-Agent")));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.ok(GateSignInService.SIGNED_IN_MESSAGE, response));
    }

    /** The address nginx sets, never X-Forwarded-For (the caller's own choice). */
    private static String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.strip();
        return request.getRemoteAddr();
    }
}
