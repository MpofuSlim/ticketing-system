package com.innbucks.userservice.devicesecurity.web;

import com.innbucks.userservice.devicesecurity.DeviceSecurityRequests;
import com.innbucks.userservice.devicesecurity.DeviceSignInService;
import com.innbucks.userservice.devicesecurity.PartnerKeyAuthorizer;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.ClientServiceRequest;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.OtpSendRequest;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.OtpSendResponse;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.OtpVerifyRequest;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.SignInDecision;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.TokenDecision;
import com.innbucks.userservice.dto.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DTX's sign-in step (contract §5.1–§5.3): the device check that stands in front
 * of staging's {@code /auth/client-service}. Reached only through the broker,
 * which verifies App Check and adds {@code x-api-key}.
 */
@RestController
@RequestMapping("/auth/client-service")
@RequiredArgsConstructor
@Tag(name = "DTX - Device sign-in",
        description = "The super app's first call on every sign-in and every silent renewal. Checks the device, then "
                + "hands back staging's client-service token plus a single-use login ticket. Branch on data.decision "
                + "ONLY; show message as-is. Called through the broker (x-api-key), never directly by the app.")
@SecurityRequirements({})
public class DeviceSignInController {

    private final DeviceSignInService service;
    private final PartnerKeyAuthorizer partnerKeys;
    private final RequestValidation validation;

    @PostMapping
    @Operation(summary = "Check the device and decide (§5.1)",
            description = """
                    Starts every sign-in (`context: SIGN_IN`) and every silent ~15-minute renewal (`context: RENEW`).
                    No PIN, ever. The answer is always HTTP 200 with one of four decisions in `data.decision`:

                    * **TOKEN** — send `data.clientService.accessToken` as the bearer and `data.loginTicket` as
                      `x-dtx-ticket` on `/auth/client-service/user/login`, with the number and PIN as today.
                    * **OTP_REQUIRED** — show the WhatsApp/SMS picker, then call `/otp/send` and `/otp/verify`.
                    * **TEMP_BLOCKED** — show `message` (it names the time and the reference). No retry button.
                    * **BANNED** — show `message`; when `data.ussdUnlock` is true add a button that dials `data.ussdCode`.

                    `message` is the neutral line to display; `data.reason` is for logs only and must never be shown.
                    Headers: `x-api-key` (broker), `x-device-id`, optional `x-innbucks-app-check`
                    (valid | absent | invalid) and `x-forwarded-for` (the phone's address).
                    """,
            parameters = {
                    @Parameter(in = ParameterIn.HEADER, name = "x-api-key", required = true, description = "Broker key"),
                    @Parameter(in = ParameterIn.HEADER, name = "x-device-id", description = "The install id; must equal device.installId"),
                    @Parameter(in = ParameterIn.HEADER, name = "x-innbucks-app-check", description = "valid | absent | invalid")
            })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A decision",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "TOKEN (trusted phone)", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Enter your PIN to continue.",
                                      "data": {
                                        "decision": "TOKEN",
                                        "clientService": { "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJjbGllbnQtc2VydmljZSJ9.sig", "expiresAt": "2026-09-29T12:10:12+02:00" },
                                        "loginTicket": "eyJhbGciOiJSUzI1NiIsImtpZCI6ImR0eC10aWNrZXQtMSJ9.eyJzdWIiOiIrMjYzNzcxMjM0NTEyIn0.sig",
                                        "trust": { "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "state": "TRUSTED", "trustedUntil": "2026-12-28T11:58:12+02:00", "newDevice": false },
                                        "limits": { "cooling": false, "coolingUntil": null }
                                      }
                                    }
                                    """),
                            @ExampleObject(name = "OTP_REQUIRED (new phone)", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Let's confirm it's you on this phone.",
                                      "data": {
                                        "decision": "OTP_REQUIRED",
                                        "challengeId": "chl_7k2m9q4t8v1x3z5b6c0d2f4g6h",
                                        "channels": ["WHATSAPP", "SMS"],
                                        "defaultChannel": "WHATSAPP",
                                        "destinationMasked": "+263 77 *** **12",
                                        "reason": "NEW_DEVICE"
                                      }
                                    }
                                    """),
                            @ExampleObject(name = "TEMP_BLOCKED", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "For your security, sign-in on this phone is paused until 16:30. Reference SEC-8F2KQ7.",
                                      "data": { "decision": "TEMP_BLOCKED", "blockedUntil": "2026-09-29T16:30:00+02:00", "reason": "OTP_ATTEMPTS", "supportRef": "SEC-8F2KQ7" }
                                    }
                                    """),
                            @ExampleObject(name = "BANNED (unlock on *569#)", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "This phone is blocked from InnBucks for your security. To unlock it, dial *569# from your InnBucks number and choose Unlock device. Reference SEC-8F2KQ7.",
                                      "data": { "decision": "BANNED", "reason": "FRAUD_SUSPECTED", "ussdUnlock": true, "ussdCode": "*569#", "supportPhone": null, "supportRef": "SEC-8F2KQ7" }
                                    }
                                    """)
                    })),
            @ApiResponse(responseCode = "400", description = "Malformed request, naming the field",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Missing field", value = """
                                    { "code": "400 BAD_REQUEST", "message": "device.installId is required", "data": { "errorCode": "validation_failed", "field": "device.installId", "fields": { "device.installId": "device.installId is required" } } }
                                    """),
                            @ExampleObject(name = "Header mismatch", value = """
                                    { "code": "400 BAD_REQUEST", "message": "device.installId must match the x-device-id header.", "data": { "errorCode": "invalid_request", "field": "device.installId" } }
                                    """)
                    })),
            @ApiResponse(responseCode = "401", description = "Missing or wrong broker x-api-key",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "401 UNAUTHORIZED", "message": "Invalid or missing API key.", "data": { "errorCode": "invalid_api_key" } }
                            """))),
            @ApiResponse(responseCode = "403", description = "The broker reported App Check as invalid",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "403 FORBIDDEN", "message": "Please update InnBucks from the official app store, then try again.", "data": { "errorCode": "app_check_failed" } }
                            """))),
            @ApiResponse(responseCode = "429", description = "Rate limited (LOOKUP limit, or the per-address OTP ceiling). Retry-After header set.",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "429 TOO_MANY_REQUESTS", "message": "Too many attempts. Please try again in 60 minutes.", "data": { "errorCode": "rate_limited", "retryAfter": 3600 } }
                            """))),
            @ApiResponse(responseCode = "503", description = "Nothing definitive — say 'Please try again shortly', never read it as a refusal",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "503 SERVICE_UNAVAILABLE", "message": "We can't complete this right now. Please try again shortly.", "data": { "errorCode": "temporarily_unavailable" } }
                            """)))
    })
    public ResponseEntity<ApiResult<SignInDecision>> clientService(@RequestBody(required = false) ClientServiceRequest body,
                                                                  HttpServletRequest request) {
        service.requireEnabled();
        partnerKeys.require(PartnerKeyAuthorizer.Partner.BROKER, request);
        validation.check(body);
        DeviceSignInService.Answer<SignInDecision> answer = service.decide(body, DeviceSecurityRequests.meta(request));
        return ResponseEntity.ok(ApiResult.ok(answer.message(), answer.body()));
    }

    @PostMapping("/otp/send")
    @Operation(summary = "Send, resend or switch channel (§5.2)",
            description = """
                    Sends a fresh 6-digit code on the chosen channel. The same call resends (after `resendAfter`, at
                    most twice) or switches channel (counts as a resend). A new code replaces the previous one.
                    Headers: `x-api-key` (broker) and `x-device-id` — the code only works on the phone that asked for it.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Code sent",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "We've sent a 6-digit code to your WhatsApp on +263 77 *** **12.",
                              "data": {
                                "challengeId": "chl_7k2m9q4t8v1x3z5b6c0d2f4g6h",
                                "channel": "WHATSAPP",
                                "destinationMasked": "+263 77 *** **12",
                                "expiresAt": "2026-09-29T12:05:00+02:00",
                                "resendAfter": "2026-09-29T12:01:00+02:00",
                                "attemptsLeft": 3,
                                "resendsLeft": 2
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "400", description = "Bad channel for this number, or a malformed request",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "400 BAD_REQUEST", "message": "SMS isn't available for this number. Please use WhatsApp.", "data": { "errorCode": "channel_not_available", "field": "channel", "challengeId": "chl_7k2m9q4t8v1x3z5b6c0d2f4g6h" } }
                            """))),
            @ApiResponse(responseCode = "410", description = "Challenge expired or used up — start again at /auth/client-service",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "410 GONE", "message": "This code has expired or can no longer be used. Please start again.", "data": { "errorCode": "challenge_expired", "challengeId": "chl_7k2m9q4t8v1x3z5b6c0d2f4g6h" } }
                            """))),
            @ApiResponse(responseCode = "429", description = "Resend too soon (or none left): the same body as 200 so the app can show the timer, plus retryAfter",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "429 TOO_MANY_REQUESTS",
                              "message": "Please wait 42 seconds before asking for a new code.",
                              "data": {
                                "errorCode": "resend_too_soon",
                                "challengeId": "chl_7k2m9q4t8v1x3z5b6c0d2f4g6h",
                                "channel": "WHATSAPP",
                                "destinationMasked": "+263 77 *** **12",
                                "expiresAt": "2026-09-29T12:05:00+02:00",
                                "resendAfter": "2026-09-29T12:01:00+02:00",
                                "attemptsLeft": 3,
                                "resendsLeft": 2,
                                "retryAfter": 42
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "503", description = "That channel's provider is down — offer the other one straight away",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "503 SERVICE_UNAVAILABLE", "message": "We couldn't send a WhatsApp message right now. Please try SMS instead.", "data": { "errorCode": "channel_unavailable", "challengeId": "chl_7k2m9q4t8v1x3z5b6c0d2f4g6h", "channel": "WHATSAPP", "alternatives": ["SMS"] } }
                            """)))
    })
    public ResponseEntity<ApiResult<OtpSendResponse>> send(@RequestBody(required = false) OtpSendRequest body,
                                                           HttpServletRequest request) {
        service.requireEnabled();
        partnerKeys.require(PartnerKeyAuthorizer.Partner.BROKER, request);
        validation.check(body);
        DeviceSignInService.Answer<OtpSendResponse> answer = service.sendOtp(body, DeviceSecurityRequests.meta(request));
        return ResponseEntity.ok(ApiResult.ok(answer.message(), answer.body()));
    }

    @PostMapping("/otp/verify")
    @Operation(summary = "Verify the code (§5.3)",
            description = """
                    A correct code answers the TOKEN decision (exactly the §7.2 body) and moves the phone to
                    PENDING_PIN; it becomes TRUSTED only when the broker reports a successful user login for the
                    ticket. A wrong code answers 401 with attemptsLeft; the attempt that uses the last one answers 410
                    and the customer starts again at /auth/client-service. Headers: `x-api-key`, `x-device-id`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verified — the TOKEN decision",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Enter your PIN to continue.",
                              "data": {
                                "decision": "TOKEN",
                                "clientService": { "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJjbGllbnQtc2VydmljZSJ9.sig", "expiresAt": "2026-09-29T12:10:12+02:00" },
                                "loginTicket": "eyJhbGciOiJSUzI1NiIsImtpZCI6ImR0eC10aWNrZXQtMSJ9.eyJzdWIiOiIrMjYzNzcxMjM0NTEyIn0.sig",
                                "trust": { "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "state": "PENDING_PIN", "trustedUntil": null, "newDevice": true },
                                "limits": { "cooling": false, "coolingUntil": null }
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "401", description = "Wrong code",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "401 UNAUTHORIZED", "message": "That code isn't right. You have 2 attempts left.", "data": { "errorCode": "otp_incorrect", "challengeId": "chl_7k2m9q4t8v1x3z5b6c0d2f4g6h", "attemptsLeft": 2 } }
                            """))),
            @ApiResponse(responseCode = "410", description = "Out of attempts, expired, or already used — start again",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "410 GONE", "message": "That code isn't right, and it can no longer be used. Please start again.", "data": { "errorCode": "challenge_expired", "challengeId": "chl_7k2m9q4t8v1x3z5b6c0d2f4g6h", "attemptsLeft": 0 } }
                            """)))
    })
    public ResponseEntity<ApiResult<TokenDecision>> verify(@RequestBody(required = false) OtpVerifyRequest body,
                                                           HttpServletRequest request) {
        service.requireEnabled();
        partnerKeys.require(PartnerKeyAuthorizer.Partner.BROKER, request);
        validation.check(body);
        DeviceSignInService.Answer<TokenDecision> answer = service.verifyOtp(body, DeviceSecurityRequests.meta(request));
        return ResponseEntity.ok(ApiResult.ok(answer.message(), answer.body()));
    }
}
