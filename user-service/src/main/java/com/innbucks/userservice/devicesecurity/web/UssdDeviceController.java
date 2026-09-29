package com.innbucks.userservice.devicesecurity.web;

import com.innbucks.userservice.devicesecurity.DeviceSecurityRequests;
import com.innbucks.userservice.devicesecurity.DeviceSignInService;
import com.innbucks.userservice.devicesecurity.PartnerKeyAuthorizer;
import com.innbucks.userservice.devicesecurity.UssdDeviceService;
import com.innbucks.userservice.devicesecurity.dto.UssdDTOs.UssdDeviceActionRequest;
import com.innbucks.userservice.devicesecurity.dto.UssdDTOs.UssdDeviceActionResponse;
import com.innbucks.userservice.devicesecurity.dto.UssdDTOs.UssdDevicesRequest;
import com.innbucks.userservice.devicesecurity.dto.UssdDTOs.UssdDevicesResponse;
import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.service.AuditContext;
import io.swagger.v3.oas.annotations.Operation;
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
 * The *569# "Unlock device" and "Block device" menus (contract §5.8, §9). Called
 * by the USSD service — never by the app — with its own {@code x-api-key}, the
 * DIALLING number, and only after it has checked the customer's PIN with
 * staging. Every response carries {@code menuText}, the exact screen to show.
 */
@RestController
@RequestMapping("/device-security/ussd/devices")
@RequiredArgsConstructor
@Tag(name = "DTX - USSD *569#",
        description = "For the USSD service: list and unlock the dialling number's blocked phones, and list and block "
                + "its active ones (lost or stolen phone). Always answer with data.menuText on screen. The customer "
                + "gets an SMS confirming every unlock and block.")
@SecurityRequirements({})
public class UssdDeviceController {

    private final UssdDeviceService service;
    private final DeviceSignInService signIn;
    private final PartnerKeyAuthorizer partnerKeys;
    private final RequestValidation validation;

    @PostMapping("/blocked")
    @Operation(summary = "Unlock device, step 1: list the blocked phones (§9.1 step 4)",
            description = "Blocked = BANNED or TEMP_BLOCKED. `eligible: false` means only support can lift it; the "
                    + "unlock call then answers NOT_ELIGIBLE with the support line. With one blocked phone, confirm it "
                    + "rather than showing a list. Header: `x-api-key` (USSD).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The number's blocked phones",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "One blocked phone", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Blocked phones retrieved",
                                      "data": {
                                        "devices": [
                                          { "option": 1, "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "label": "Samsung SM-A155F", "state": "BANNED", "blockedAt": "2026-09-28T19:02:44+02:00", "blockedUntil": null, "eligible": true, "menuLabel": "Samsung SM-A155F (blocked 28 Sep)" }
                                        ],
                                        "menuText": "Choose the phone to unlock:\\n1. Samsung SM-A155F (blocked 28 Sep)"
                                      }
                                    }
                                    """),
                            @ExampleObject(name = "Nothing blocked", value = """
                                    { "code": "200 OK", "message": "Blocked phones retrieved", "data": { "devices": [], "menuText": "None of your phones is blocked from InnBucks. If you still can't sign in, call InnBucks support." } }
                                    """)
                    })),
            @ApiResponse(responseCode = "400", description = "msisdn missing or not a mobile number",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "400 BAD_REQUEST", "message": "msisdn is not a valid mobile number.", "data": { "errorCode": "invalid_request", "field": "msisdn" } }
                            """))),
            @ApiResponse(responseCode = "401", description = "Missing or wrong USSD x-api-key",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "401 UNAUTHORIZED", "message": "Invalid or missing API key.", "data": { "errorCode": "invalid_api_key" } }
                            """)))
    })
    public ResponseEntity<ApiResult<UssdDevicesResponse>> blocked(@RequestBody(required = false) UssdDevicesRequest body,
                                                                  HttpServletRequest request) {
        signIn.requireEnabled();
        partnerKeys.require(PartnerKeyAuthorizer.Partner.USSD, request);
        validation.check(body);
        return ResponseEntity.ok(ApiResult.ok("Blocked phones retrieved", service.blocked(body)));
    }

    @PostMapping("/unlock")
    @Operation(summary = "Unlock device, step 2: unlock the chosen phone (§9.1 steps 5–6)",
            description = """
                    Moves the phone from BANNED/TEMP_BLOCKED to STEP_UP: the next sign-in on it asks for a code, then
                    the PIN. The customer gets an SMS confirming the unlock. Results:
                    **UNLOCKED** · **NOT_ELIGIBLE** (support only — SHARED_DEVICE, CONFIRMED_FRAUD, SIM swap, unlocked
                    in the last 7 days, or a security-check hold) · **TRY_LATER** (3 attempts a day used, or a fraud-desk
                    block younger than 24 hours) · **NOT_FOUND** (not one of this number's phones).
                    Show `menuText` verbatim. Header: `x-api-key` (USSD).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The outcome, with the screen to show",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "UNLOCKED", value = """
                                    { "code": "200 OK", "message": "Unlock processed", "data": { "result": "UNLOCKED", "deviceLabel": "Samsung SM-A155F", "supportRef": "SEC-3M7Q2X", "menuText": "Your phone is unlocked. Open InnBucks and sign in. We will send you a code to confirm it's you." } }
                                    """),
                            @ExampleObject(name = "NOT_ELIGIBLE", value = """
                                    { "code": "200 OK", "message": "Unlock processed", "data": { "result": "NOT_ELIGIBLE", "deviceLabel": "Samsung SM-A155F", "supportRef": "SEC-8F2KQ7", "menuText": "This phone can only be unlocked by InnBucks support. Call InnBucks support, reference SEC-8F2KQ7." } }
                                    """),
                            @ExampleObject(name = "TRY_LATER", value = """
                                    { "code": "200 OK", "message": "Unlock processed", "data": { "result": "TRY_LATER", "deviceLabel": null, "supportRef": null, "menuText": "You can't unlock a phone on *569# right now. Please try again tomorrow." } }
                                    """)
                    })),
            @ApiResponse(responseCode = "400", description = "Malformed request, naming the field",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "400 BAD_REQUEST", "message": "ussdSessionId is required", "data": { "errorCode": "validation_failed", "field": "ussdSessionId", "fields": { "ussdSessionId": "ussdSessionId is required" } } }
                            """))),
            @ApiResponse(responseCode = "401", description = "Missing or wrong USSD x-api-key",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "401 UNAUTHORIZED", "message": "Invalid or missing API key.", "data": { "errorCode": "invalid_api_key" } }
                            """)))
    })
    public ResponseEntity<ApiResult<UssdDeviceActionResponse>> unlock(
            @RequestBody(required = false) UssdDeviceActionRequest body, HttpServletRequest request) {
        signIn.requireEnabled();
        partnerKeys.require(PartnerKeyAuthorizer.Partner.USSD, request);
        validation.check(body);
        return ResponseEntity.ok(ApiResult.ok("Unlock processed", service.unlock(body, audit(request))));
    }

    @PostMapping("/active")
    @Operation(summary = "Block device, step 1: list the phones that can be blocked",
            description = "Every phone on this number that is not already blocked, most recently used first — "
                    + "including a phone that only TRIED to sign in (the one a 'new phone' SMS warned about). "
                    + "Header: `x-api-key` (USSD).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The number's blockable phones",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Phones retrieved",
                              "data": {
                                "devices": [
                                  { "option": 1, "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "label": "Samsung SM-A155F", "state": "TRUSTED", "blockedAt": null, "blockedUntil": null, "eligible": true, "menuLabel": "Samsung SM-A155F (last used 29 Sep)" },
                                  { "option": 2, "deviceId": "6a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", "label": "Tecno KL4", "state": "NEW", "blockedAt": null, "blockedUntil": null, "eligible": true, "menuLabel": "Tecno KL4 (last used 29 Sep)" }
                                ],
                                "menuText": "Choose the phone to block:\\n1. Samsung SM-A155F (last used 29 Sep)\\n2. Tecno KL4 (last used 29 Sep)"
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "401", description = "Missing or wrong USSD x-api-key",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "401 UNAUTHORIZED", "message": "Invalid or missing API key.", "data": { "errorCode": "invalid_api_key" } }
                            """)))
    })
    public ResponseEntity<ApiResult<UssdDevicesResponse>> active(@RequestBody(required = false) UssdDevicesRequest body,
                                                                 HttpServletRequest request) {
        signIn.requireEnabled();
        partnerKeys.require(PartnerKeyAuthorizer.Partner.USSD, request);
        validation.check(body);
        return ResponseEntity.ok(ApiResult.ok("Phones retrieved", service.active(body)));
    }

    @PostMapping("/block")
    @Operation(summary = "Block device, step 2: block the chosen phone (lost or stolen)",
            description = """
                    Blocks the phone from this InnBucks account until the customer unlocks it on *569# (a
                    CUSTOMER_REPORTED ban). Open codes and unspent login tickets die at once; a silent renewal cuts the
                    phone off within 15 minutes. The customer gets an SMS with the reference. Results: **BLOCKED** ·
                    **ALREADY_BLOCKED** · **TRY_LATER** (5 blocks a day) · **NOT_FOUND**. Header: `x-api-key` (USSD).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The outcome, with the screen to show",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Block processed", "data": { "result": "BLOCKED", "deviceLabel": "Tecno KL4", "supportRef": "SEC-5T9W1H", "menuText": "Your Tecno KL4 is now blocked from InnBucks. To use it again, dial *569# and choose Unlock device. Reference SEC-5T9W1H." } }
                            """))),
            @ApiResponse(responseCode = "401", description = "Missing or wrong USSD x-api-key",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "401 UNAUTHORIZED", "message": "Invalid or missing API key.", "data": { "errorCode": "invalid_api_key" } }
                            """)))
    })
    public ResponseEntity<ApiResult<UssdDeviceActionResponse>> block(
            @RequestBody(required = false) UssdDeviceActionRequest body, HttpServletRequest request) {
        signIn.requireEnabled();
        partnerKeys.require(PartnerKeyAuthorizer.Partner.USSD, request);
        validation.check(body);
        return ResponseEntity.ok(ApiResult.ok("Block processed", service.block(body, audit(request))));
    }

    private static AuditContext audit(HttpServletRequest request) {
        return new AuditContext(DeviceSecurityRequests.clientIp(request), request.getHeader("User-Agent"));
    }
}
