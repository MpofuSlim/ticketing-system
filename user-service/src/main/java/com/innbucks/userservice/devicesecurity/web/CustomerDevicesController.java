package com.innbucks.userservice.devicesecurity.web;

import com.innbucks.userservice.devicesecurity.CustomerDeviceService;
import com.innbucks.userservice.devicesecurity.DeviceSecurityRequests;
import com.innbucks.userservice.devicesecurity.DeviceSignInService;
import com.innbucks.userservice.devicesecurity.PartnerKeyAuthorizer;
import com.innbucks.userservice.devicesecurity.dto.CustomerDeviceDTOs.CustomerDevicesResponse;
import com.innbucks.userservice.devicesecurity.dto.CustomerDeviceDTOs.StepUpRequest;
import com.innbucks.userservice.devicesecurity.dto.CustomerDeviceDTOs.StepUpResponse;
import com.innbucks.userservice.devicesecurity.dto.CustomerDeviceDTOs.StepUpVerifyRequest;
import com.innbucks.userservice.devicesecurity.dto.CustomerDeviceDTOs.StepUpVerifyResponse;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.OtpSendRequest;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.OtpSendResponse;
import com.innbucks.userservice.dto.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Profile › Security › Your devices (contract §5.6) and the in-session step-up
 * (§5.5). Authenticated with the customer's fleet session (the CUSTOMER token
 * from {@code POST /auth/exchange}) AND the broker's {@code x-api-key}.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "DTX - Your devices",
        description = "The signed-in customer's phones, removing one, and the in-session step-up. Bearer = the fleet "
                + "CUSTOMER session; through the broker (x-api-key); x-device-id identifies the phone making the call.")
@SecurityRequirement(name = "bearerAuth")
public class CustomerDevicesController {

    private final CustomerDeviceService service;
    private final DeviceSignInService signIn;
    private final PartnerKeyAuthorizer partnerKeys;
    private final RequestValidation validation;

    @GetMapping("/auth/devices")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "List my phones (§5.6)",
            description = "Each bound phone, the phone making the call (`current: true`) and any blocked phone. "
                    + "`status` is a plain-words line to print as-is. Never includes install ids, coordinates or "
                    + "risk facts; `lastSeenNear` is a town name.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The customer's phones",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Your devices",
                              "data": {
                                "devices": [
                                  { "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "label": "Samsung SM-A155F · Android 15", "firstSeenAt": "2026-07-02T08:14:03+02:00", "lastSeenAt": "2026-09-29T11:58:12+02:00", "lastSeenNear": "Harare", "current": true, "state": "TRUSTED", "status": "Signed in", "trustedUntil": "2026-12-28T11:58:12+02:00" },
                                  { "deviceId": "6a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", "label": "iPhone 12 · iOS 17.5", "firstSeenAt": "2025-11-20T18:40:10+02:00", "lastSeenAt": "2026-08-30T21:05:44+02:00", "lastSeenNear": "Bulawayo", "current": false, "state": "TRUSTED", "status": "Signed in", "trustedUntil": "2026-10-14T09:12:00+02:00" }
                                ],
                                "tidyUpSuggested": false
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "401", description = "No/invalid bearer, or missing/wrong broker x-api-key",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "401 UNAUTHORIZED", "message": "Invalid token", "data": null }
                            """)))
    })
    public ResponseEntity<ApiResult<CustomerDevicesResponse>> list(Authentication auth, HttpServletRequest request) {
        signIn.requireEnabled();
        partnerKeys.require(PartnerKeyAuthorizer.Partner.BROKER, request);
        return ResponseEntity.ok(ApiResult.ok("Your devices",
                service.list(auth, request.getHeader(DeviceSecurityRequests.DEVICE_HEADER))));
    }

    @DeleteMapping("/auth/devices/{deviceId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Remove a phone (§5.6)",
            description = "The phone's next silent renewal fails (within 15 minutes) and signing in on it again starts "
                    + "as a new phone, with a code. The customer is told by SMS/WhatsApp. A BLOCKED phone can't be "
                    + "removed (that would dodge the block) — manage it on *569#.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Removed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Samsung SM-A155F was removed from your InnBucks.", "data": { "deviceId": "6a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", "removed": true } }
                            """))),
            @ApiResponse(responseCode = "404", description = "Not one of this customer's phones",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "404 NOT_FOUND", "message": "We couldn't find that phone on your account.", "data": { "errorCode": "device_not_found" } }
                            """))),
            @ApiResponse(responseCode = "409", description = "The phone is blocked",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "409 CONFLICT", "message": "This phone is blocked, so it can't be removed. Dial *569# to manage it.", "data": { "errorCode": "device_blocked" } }
                            """)))
    })
    public ResponseEntity<ApiResult<java.util.Map<String, Object>>> remove(@PathVariable UUID deviceId,
                                                                           Authentication auth,
                                                                           HttpServletRequest request) {
        signIn.requireEnabled();
        partnerKeys.require(PartnerKeyAuthorizer.Partner.BROKER, request);
        String label = service.remove(auth, deviceId);
        return ResponseEntity.ok(ApiResult.ok(label + " was removed from your InnBucks.",
                java.util.Map.of("deviceId", deviceId, "removed", true)));
    }

    @PostMapping("/auth/device/challenge")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "In-session step-up: does this action need a code? (§5.5)",
            description = """
                    Ask before a first large transfer, adding a new person to pay, a cash-out or a PIN change. DTX
                    answers `NOT_REQUIRED` for a phone trusted for more than 24 hours, else `OTP_REQUIRED` with a
                    challenge — then `/auth/device/challenge/send` and `/verify`, exactly like sign-in. `force: true`
                    always asks. A verified step-up returns `stepUpProof` (RS256, 5 minutes) naming the action.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The decision",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "OTP_REQUIRED", value = """
                                    { "code": "200 OK", "message": "For your security, let's confirm it's you before you continue.", "data": { "decision": "OTP_REQUIRED", "challengeId": "chl_9d4f6h8k0m2p4r6t8v0x2z4b6c", "channels": ["WHATSAPP", "SMS"], "defaultChannel": "WHATSAPP", "destinationMasked": "+263 77 *** **12" } }
                                    """),
                            @ExampleObject(name = "NOT_REQUIRED", value = """
                                    { "code": "200 OK", "message": "No extra check is needed. You can continue.", "data": { "decision": "NOT_REQUIRED", "challengeId": null, "channels": null, "defaultChannel": null, "destinationMasked": null } }
                                    """)
                    })),
            @ApiResponse(responseCode = "403", description = "This phone is not trusted for this account",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "403 FORBIDDEN", "message": "This phone isn't signed in to your InnBucks. Please sign in again.", "data": { "errorCode": "device_not_trusted" } }
                            """)))
    })
    public ResponseEntity<ApiResult<StepUpResponse>> stepUp(@RequestBody(required = false) StepUpRequest body,
                                                            Authentication auth, HttpServletRequest request) {
        signIn.requireEnabled();
        partnerKeys.require(PartnerKeyAuthorizer.Partner.BROKER, request);
        validation.check(body);
        DeviceSignInService.Answer<StepUpResponse> answer = service.stepUp(auth,
                request.getHeader(DeviceSecurityRequests.DEVICE_HEADER), body, DeviceSecurityRequests.clientIp(request));
        return ResponseEntity.ok(ApiResult.ok(answer.message(), answer.body()));
    }

    @PostMapping("/auth/device/challenge/send")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "In-session step-up: send the code",
            description = "Same body, answers and errors as `/auth/client-service/otp/send` (200 / 410 / 429 / 503).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Code sent",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "We've sent a 6-digit code to your WhatsApp on +263 77 *** **12.", "data": { "challengeId": "chl_9d4f6h8k0m2p4r6t8v0x2z4b6c", "channel": "WHATSAPP", "destinationMasked": "+263 77 *** **12", "expiresAt": "2026-09-29T14:05:00+02:00", "resendAfter": "2026-09-29T14:01:00+02:00", "attemptsLeft": 3, "resendsLeft": 2 } }
                            """))),
            @ApiResponse(responseCode = "410", description = "Challenge expired or used up",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "410 GONE", "message": "This code has expired or can no longer be used. Please start again.", "data": { "errorCode": "challenge_expired", "challengeId": "chl_9d4f6h8k0m2p4r6t8v0x2z4b6c" } }
                            """)))
    })
    public ResponseEntity<ApiResult<OtpSendResponse>> sendStepUp(@RequestBody(required = false) OtpSendRequest body,
                                                                 Authentication auth, HttpServletRequest request) {
        signIn.requireEnabled();
        partnerKeys.require(PartnerKeyAuthorizer.Partner.BROKER, request);
        validation.check(body);
        OtpSendResponse sent = service.sendStepUp(auth, request.getHeader(DeviceSecurityRequests.DEVICE_HEADER), body);
        return ResponseEntity.ok(ApiResult.ok(sent.channel() == com.innbucks.userservice.devicesecurity.OtpChannel.WHATSAPP
                ? "We've sent a 6-digit code to your WhatsApp on " + sent.destinationMasked() + "."
                : "We've sent a 6-digit code by SMS to " + sent.destinationMasked() + ".", sent));
    }

    @PostMapping("/auth/device/challenge/verify")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "In-session step-up: verify the code",
            description = "401 with attemptsLeft on a wrong code; 410 when the challenge is dead. On success, "
                    + "`stepUpProof` is proof that this number re-proved possession on this phone just now.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verified",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Thanks, that's confirmed. You can continue.", "data": { "verified": true, "action": "LARGE_TRANSFER", "stepUpProof": "eyJhbGciOiJSUzI1NiJ9.eyJ0eXAiOiJzdGVwX3VwIn0.sig", "expiresAt": "2026-09-29T14:07:00+02:00" } }
                            """))),
            @ApiResponse(responseCode = "401", description = "Wrong code",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "401 UNAUTHORIZED", "message": "That code isn't right. You have 2 attempts left.", "data": { "errorCode": "otp_incorrect", "challengeId": "chl_9d4f6h8k0m2p4r6t8v0x2z4b6c", "attemptsLeft": 2 } }
                            """)))
    })
    public ResponseEntity<ApiResult<StepUpVerifyResponse>> verifyStepUp(
            @RequestBody(required = false) StepUpVerifyRequest body, Authentication auth, HttpServletRequest request) {
        signIn.requireEnabled();
        partnerKeys.require(PartnerKeyAuthorizer.Partner.BROKER, request);
        validation.check(body);
        DeviceSignInService.Answer<StepUpVerifyResponse> answer = service.verifyStepUp(auth,
                request.getHeader(DeviceSecurityRequests.DEVICE_HEADER), body);
        return ResponseEntity.ok(ApiResult.ok(answer.message(), answer.body()));
    }
}
