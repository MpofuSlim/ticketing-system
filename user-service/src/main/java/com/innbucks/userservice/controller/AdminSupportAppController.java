package com.innbucks.userservice.controller;

import com.innbucks.userservice.devicesecurity.DeviceSecurityRequests;
import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.support.InnbucksAppSupportActions;
import com.innbucks.userservice.support.InnbucksAppSupportActions.Op;
import com.innbucks.userservice.support.InnbucksAppSupportActions.Target;
import com.innbucks.userservice.support.InnbucksAppSupportActions.Write;
import com.innbucks.userservice.support.SupportAgent;
import com.innbucks.userservice.support.SupportAgentResolver;
import com.innbucks.userservice.support.dto.SupportDTOs.AppActionResult;
import com.innbucks.userservice.support.dto.SupportDTOs.AppBanWrite;
import com.innbucks.userservice.support.dto.SupportDTOs.AppBlockWrite;
import com.innbucks.userservice.support.dto.SupportDTOs.AppDeviceWrite;
import com.innbucks.userservice.support.dto.SupportDTOs.AppFraudFlagWrite;
import com.innbucks.userservice.support.dto.SupportDTOs.AppPhoneWrite;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.innbucks.userservice.controller.AdminSupportController.AGENT_NOT_RESOLVED_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.AUDIT_UNAVAILABLE_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.CONSOLE_STAFF_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.DISABLED_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.FORBIDDEN_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.IDEMPOTENCY_REQUIRED_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.IDEMPOTENCY_REUSED_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.LIMITED_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.LOOKUP_EXPIRED_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.LOOKUP_MISSING_SECTION_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.LOOKUP_REQUIRED_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.NOTE_EMPTY_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.NOTE_REQUIRED_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.SELF_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.STAFF_TARGET_EXAMPLE;
import static com.innbucks.userservice.controller.AdminSupportController.TARGET_NOT_FOUND_EXAMPLE;

/**
 * The InnBucks app (DTX) section's support writes, bound to a lookup
 * ({@link InnbucksAppSupportActions}): the seven device-security actions that
 * used to run only, unbound, on {@code /admin/device-security/**}, and "sign out
 * of the Super app". Each needs the lookupId of the agent's own search (at most
 * 30 minutes old) that returned the innbucksApp section, a note, and an
 * {@code Idempotency-Key}; each counts against the agent's lookup limit and is
 * sealed on the audit chain. A number rides in the BODY, never a URL.
 * {@code /admin/support/**} rides the gateway's {@code user-admin-route}.
 */
@RestController
@RequestMapping("/admin/support/innbucks-app")
@Tag(name = "Admin - Customer support (InnBucks app)",
        description = "Block, ban, unlock, remove or reset one of the customer's phones; set the fraud flag; cancel "
                + "open codes; sign the customer out of the Super app. Bound to your search for the customer.")
@SecurityRequirement(name = "bearerAuth")
public class AdminSupportAppController {

    private static final String KEY_EXAMPLE = "3f6c1a52-7b0e-4d8a-9c21-5e4f7a8b9c0d";

    private static final String DEVICE_HEAD = """
            { "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "msisdn": "+263771234512", "label": "Samsung SM-A155F · Android 15", "platform": "android", "osVersion": "15", "model": "SM-A155F", "manufacturer": "samsung", "appVersion": "2.4.0", """;
    private static final String DEVICE_TAIL = """
            "boundAt": "2026-07-02T08:16:40+02:00", "coolingUntil": null, "otpVerifiedAt": "2026-07-02T08:15:52+02:00", "firstSeenAt": "2026-07-02T08:14:03+02:00", "lastSeenAt": "2026-09-29T16:14:51+02:00", "lastSeenNear": "Harare", "lastIp": "41.221.x.x", "lastUnlockedAt": null, "deviceWideBan": null, "otherAccountsOnDevice": 0, """;
    private static final String DEVICE_BLOCKED = DEVICE_HEAD + """
            "state": "BANNED", "stateReason": "CUSTOMER_REPORTED", "blockedAt": "2026-09-29T16:40:12+02:00", "blockedUntil": null, "supportRef": "SEC-5T9W1H", "ussdUnlockable": true, "unlockableAfter": null, "trustedUntil": null, """
            + DEVICE_TAIL + """
            "agentGuidance": "The customer blocked it (lost or stolen). The customer can unlock it themselves on *569# (Unlock device). You may unlock it after verifying the caller." }""";
    private static final String DEVICE_TRUSTED = DEVICE_HEAD + """
            "state": "TRUSTED", "stateReason": null, "blockedAt": null, "blockedUntil": null, "supportRef": null, "ussdUnlockable": false, "unlockableAfter": null, "trustedUntil": "2026-10-29T08:16:40+02:00", """
            + DEVICE_TAIL + """
            "agentGuidance": "Signed in and trusted. Nothing is stopping this phone." }""";
    private static final String DEVICE_UNLOCKED = DEVICE_HEAD + """
            "state": "STEP_UP", "stateReason": "UNLOCKED", "blockedAt": null, "blockedUntil": null, "supportRef": "SEC-5T9W1H", "ussdUnlockable": false, "unlockableAfter": null, "trustedUntil": null, """
            + DEVICE_TAIL + """
            "agentGuidance": "The next sign-in on this phone asks for a code (it was just unlocked). If codes aren't arriving, check the counters and try the other channel." }""";

    private static final String PROFILE = """
            "customerProfile": { "registrationTier": 2, "verified": false, "phoneVerified": true, "phoneVerifiedAt": "2026-07-02T08:15:52+02:00" }, """;
    private static final String COUNTERS = """
            "counters": { "otpChallengesLastHour": 0, "otpChallengesLastDay": 1, "ussdUnlockAttemptsLastDay": 0, "openChallenges": 0 }, "recentEvents": [], """;
    private static final String PHONE_BLOCKED = """
            { "msisdn": "+263771234512", """ + PROFILE + """
            "deviceSecurity": { "profile": { "preferredChannel": "WHATSAPP", "fraudFlagged": false, "fraudFlaggedAt": null, "fraudFlagNote": null, "pinIssuedAt": "2026-07-02T08:15:30+02:00", "lastSignInAt": "2026-09-29T11:58:14+02:00" }, "devices": [ """
            + DEVICE_BLOCKED + " ], " + COUNTERS + """
            "summary": "0 phones signed in; Samsung SM-A155F · Android 15 blocked (SEC-5T9W1H)." } }""";
    private static final String PHONE_UNLOCKED = """
            { "msisdn": "+263771234512", """ + PROFILE + """
            "deviceSecurity": { "profile": { "preferredChannel": "WHATSAPP", "fraudFlagged": false, "fraudFlaggedAt": null, "fraudFlagNote": null, "pinIssuedAt": "2026-07-02T08:15:30+02:00", "lastSignInAt": "2026-09-29T11:58:14+02:00" }, "devices": [ """
            + DEVICE_UNLOCKED + " ], " + COUNTERS + """
            "summary": "0 phones signed in." } }""";
    private static final String PHONE_FLAGGED = """
            { "msisdn": "+263771234512", """ + PROFILE + """
            "deviceSecurity": { "profile": { "preferredChannel": "WHATSAPP", "fraudFlagged": true, "fraudFlaggedAt": "2026-09-29T17:05:00+02:00", "fraudFlagNote": "Customer was the victim of a SIM-swap attempt on 2026-09-20.", "pinIssuedAt": "2026-07-02T08:15:30+02:00", "lastSignInAt": "2026-09-29T11:58:14+02:00" }, "devices": [ """
            + DEVICE_TRUSTED + " ], " + COUNTERS + """
            "summary": "1 phone signed in." } }""";
    private static final String PHONE_TRUSTED = """
            { "msisdn": "+263771234512", """ + PROFILE + """
            "deviceSecurity": { "profile": { "preferredChannel": "WHATSAPP", "fraudFlagged": false, "fraudFlaggedAt": null, "fraudFlagNote": null, "pinIssuedAt": "2026-07-02T08:15:30+02:00", "lastSignInAt": "2026-09-29T11:58:14+02:00" }, "devices": [ """
            + DEVICE_TRUSTED + " ], " + COUNTERS + """
            "summary": "1 phone signed in." } }""";

    private static final String PHONE_REQUIRED = """
            { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "phone": "phone is required" } }
            """;

    private final InnbucksAppSupportActions actions;
    private final SupportAgentResolver agents;

    public AdminSupportAppController(InnbucksAppSupportActions actions, SupportAgentResolver agents) {
        this.actions = actions;
        this.agents = agents;
    }

    // ---- one phone ---------------------------------------------------------------------------

    @PostMapping("/devices/{deviceId}/block")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_MANAGE + "')")
    @Operation(summary = "Block one of the customer's phones",
            description = "`UNTIL_UNLOCKED` for a lost or stolen phone: blocked until the customer unlocks it on "
                    + "*569# or support lifts it. `TEMPORARY` pauses sign-in for `durationMinutes` (15 min to 7 days) "
                    + "and lifts on its own. The phone's open codes and login tickets die at once, a silent renewal "
                    + "cuts it off within 15 minutes, and the customer gets an SMS with the reference. The phone must "
                    + "be on a number YOUR search returned in innbucksApp.")
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true, example = KEY_EXAMPLE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Blocked (or the stored answer to a repeated key)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Phone blocked", "data": { "outcome": "SUCCESS", "whatHappensNext": "The phone is blocked from this account until the customer unlocks it on *569# (or support lifts it). The customer has been sent an SMS with reference SEC-5T9W1H.", "replayed": false, "phone": """
                            + PHONE_BLOCKED + " } }"))),
            @ApiResponse(responseCode = "400", description = "A missing or bad field, no lookupId, no Idempotency-Key, or a note that is empty once markup is removed",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "validation", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "mode": "mode must be TEMPORARY or UNTIL_UNLOCKED" } }
                                    """),
                            @ExampleObject(name = "duration_required", value = """
                                    { "code": "400 BAD_REQUEST", "message": "durationMinutes is required for a TEMPORARY block.", "data": { "errorCode": "invalid_request", "field": "durationMinutes" } }
                                    """),
                            @ExampleObject(name = "lookup_required", value = LOOKUP_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_required", value = IDEMPOTENCY_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "note_required", value = NOTE_EMPTY_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "403", description = "Missing device-security:manage, your account couldn't be verified, or not on this customer",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "missing_permission", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "agent_not_resolved", value = AGENT_NOT_RESOLVED_EXAMPLE),
                            @ExampleObject(name = "support_self_action", value = SELF_EXAMPLE),
                            @ExampleObject(name = "staff_target_requires_supervisor", value = STAFF_TARGET_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "404", description = "No such phone, or not on a number of this search; or support switched off",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "target_not_found", value = TARGET_NOT_FOUND_EXAMPLE),
                            @ExampleObject(name = "support_disabled", value = DISABLED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "409", description = "Already blocked, a stale lookup, or the key was used for another action",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "device_already_blocked", value = """
                                    { "code": "409 CONFLICT", "message": "This phone is already blocked (reference SEC-5T9W1H).", "data": { "errorCode": "device_already_blocked" } }
                                    """),
                            @ExampleObject(name = "lookup_expired", value = LOOKUP_EXPIRED_EXAMPLE),
                            @ExampleObject(name = "section_not_in_lookup", value = LOOKUP_MISSING_SECTION_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_reused", value = IDEMPOTENCY_REUSED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "429", description = "Your lookup limit",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "503", description = "The change could not be sealed, so it was not made",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = AUDIT_UNAVAILABLE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<AppActionResult>> block(@PathVariable UUID deviceId,
                                                            @Valid @RequestBody AppBlockWrite body,
                                                            @RequestHeader(value = "Idempotency-Key", required = false)
                                                            String idempotencyKey,
                                                            Authentication auth, HttpServletRequest request) {
        return run(Op.BLOCK, "Phone blocked", Target.device(deviceId),
                new Write(body.lookupId(), body.note(), body.caseId(), body.mode(), body.durationMinutes(), null, null),
                idempotencyKey, auth, request);
    }

    @PostMapping("/devices/{deviceId}/ban")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_FRAUD + "')")
    @Operation(summary = "Ban one of the customer's phones for fraud (fraud desk)",
            description = "`FRAUD_SUSPECTED` bans this account on this phone; the customer can unlock on *569# after "
                    + "24 hours. `CONFIRMED_FRAUD` and `SHARED_DEVICE` ban the PHONE for every account on it, support "
                    + "only. Same lookup, note and Idempotency-Key rules as block.")
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true, example = KEY_EXAMPLE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Banned",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Phone banned", "data": { "outcome": "SUCCESS", "whatHappensNext": "The phone is blocked from this account. The customer can unlock it on *569# after 24 hours. Reference SEC-5T9W1H.", "replayed": false, "phone": """
                            + PHONE_BLOCKED + " } }"))),
            @ApiResponse(responseCode = "400", description = "A missing or bad field, no lookupId, no Idempotency-Key, or an empty note",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "validation", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "reason": "reason must be FRAUD_SUSPECTED, CONFIRMED_FRAUD or SHARED_DEVICE" } }
                                    """),
                            @ExampleObject(name = "lookup_required", value = LOOKUP_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_required", value = IDEMPOTENCY_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "note_required", value = NOTE_EMPTY_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "403", description = "Missing device-security:fraud, or not on this customer",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "missing_permission", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "support_self_action", value = SELF_EXAMPLE),
                            @ExampleObject(name = "staff_target_requires_supervisor", value = STAFF_TARGET_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "404", description = "No such phone, or not on a number of this search",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = TARGET_NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "A stale lookup, or the key was used for another action",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "lookup_expired", value = LOOKUP_EXPIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_reused", value = IDEMPOTENCY_REUSED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "429", description = "Your lookup limit",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "503", description = "The change could not be sealed, so it was not made",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = AUDIT_UNAVAILABLE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<AppActionResult>> ban(@PathVariable UUID deviceId,
                                                          @Valid @RequestBody AppBanWrite body,
                                                          @RequestHeader(value = "Idempotency-Key", required = false)
                                                          String idempotencyKey,
                                                          Authentication auth, HttpServletRequest request) {
        return run(Op.BAN, "Phone banned", Target.device(deviceId),
                new Write(body.lookupId(), body.note(), body.caseId(), null, null, body.reason(), null),
                idempotencyKey, auth, request);
    }

    @PostMapping("/devices/{deviceId}/unlock")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_MANAGE + "')")
    @Operation(summary = "Unlock one of the customer's paused or blocked phones",
            description = "Lifts a pause or a block. The phone does NOT go straight back to trusted: the next sign-in "
                    + "asks for a code, then the PIN. A ban only the fraud desk may lift (SHARED_DEVICE, "
                    + "CONFIRMED_FRAUD, SIM_SWAP, any ban on every account) also needs device-security:fraud.")
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true, example = KEY_EXAMPLE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Unlocked",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Phone unlocked", "data": { "outcome": "SUCCESS", "whatHappensNext": "Unlocked. The next sign-in on this phone will ask for a code, then the PIN. The customer has been sent an SMS.", "replayed": false, "phone": """
                            + PHONE_UNLOCKED + " } }"))),
            @ApiResponse(responseCode = "400", description = "No lookupId, no Idempotency-Key, or no note",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "validation", value = NOTE_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "lookup_required", value = LOOKUP_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_required", value = IDEMPOTENCY_REQUIRED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "403", description = "Missing permission, the fraud desk's ban, or not on this customer",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "missing_permission", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "fraud_desk_required", value = """
                                    { "code": "403 FORBIDDEN", "message": "Only the fraud desk can lift this block. Escalate with reference SEC-2B6N9R.", "data": { "errorCode": "fraud_desk_required" } }
                                    """),
                            @ExampleObject(name = "support_self_action", value = SELF_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "404", description = "No such phone, or not on a number of this search",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = TARGET_NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "Not blocked, a stale lookup, or a reused key",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "device_not_blocked", value = """
                                    { "code": "409 CONFLICT", "message": "This phone isn't blocked, so there is nothing to unlock.", "data": { "errorCode": "device_not_blocked" } }
                                    """),
                            @ExampleObject(name = "lookup_expired", value = LOOKUP_EXPIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_reused", value = IDEMPOTENCY_REUSED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "429", description = "Your lookup limit",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "503", description = "The change could not be sealed, so it was not made",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = AUDIT_UNAVAILABLE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<AppActionResult>> unlock(@PathVariable UUID deviceId,
                                                             @Valid @RequestBody AppDeviceWrite body,
                                                             @RequestHeader(value = "Idempotency-Key", required = false)
                                                             String idempotencyKey,
                                                             Authentication auth, HttpServletRequest request) {
        return run(Op.UNLOCK, "Phone unlocked", Target.device(deviceId), deviceWrite(body), idempotencyKey, auth,
                request);
    }

    @PostMapping("/devices/{deviceId}/revoke")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_MANAGE + "')")
    @Operation(summary = "Remove one of the customer's phones from the account",
            description = "Same as the customer removing it in Your devices: signed out within 15 minutes, and signing "
                    + "in on it again needs a code. A blocked phone can't be removed (it would start again as new).")
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true, example = KEY_EXAMPLE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Removed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Phone removed", "data": { "outcome": "SUCCESS", "whatHappensNext": "Removed. The phone is signed out within 15 minutes; signing in on it again needs a code.", "replayed": false, "phone": """
                            + PHONE_TRUSTED + " } }"))),
            @ApiResponse(responseCode = "400", description = "No lookupId, no Idempotency-Key, or no note",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "validation", value = NOTE_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_required", value = IDEMPOTENCY_REQUIRED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "403", description = "Missing permission, or not on this customer",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "missing_permission", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "support_self_action", value = SELF_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "404", description = "No such phone, or not on a number of this search",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = TARGET_NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "Already removed, blocked, a stale lookup, or a reused key",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "device_already_removed", value = """
                                    { "code": "409 CONFLICT", "message": "This phone has already been removed from the account.", "data": { "errorCode": "device_already_removed" } }
                                    """),
                            @ExampleObject(name = "lookup_expired", value = LOOKUP_EXPIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_reused", value = IDEMPOTENCY_REUSED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "429", description = "Your lookup limit",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "503", description = "The change could not be sealed, so it was not made",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = AUDIT_UNAVAILABLE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<AppActionResult>> revoke(@PathVariable UUID deviceId,
                                                             @Valid @RequestBody AppDeviceWrite body,
                                                             @RequestHeader(value = "Idempotency-Key", required = false)
                                                             String idempotencyKey,
                                                             Authentication auth, HttpServletRequest request) {
        return run(Op.REVOKE, "Phone removed", Target.device(deviceId), deviceWrite(body), idempotencyKey, auth,
                request);
    }

    @PostMapping("/devices/{deviceId}/reset-trust")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_MANAGE + "')")
    @Operation(summary = "Reset one of the customer's phones to brand-new",
            description = "Forgets the pairing: the next sign-in is a first sign-in (code, PIN, new-phone notice). "
                    + "Silent: the customer is not messaged. A blocked phone can't be reset.")
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true, example = KEY_EXAMPLE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reset",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Phone reset", "data": { "outcome": "SUCCESS", "whatHappensNext": "Reset. The next sign-in on this phone is treated as a brand-new phone (code, then PIN).", "replayed": false, "phone": """
                            + PHONE_TRUSTED + " } }"))),
            @ApiResponse(responseCode = "400", description = "No lookupId, no Idempotency-Key, or no note",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "validation", value = NOTE_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_required", value = IDEMPOTENCY_REQUIRED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "403", description = "Missing permission, or not on this customer",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "missing_permission", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "support_self_action", value = SELF_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "404", description = "No such phone, or not on a number of this search",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = TARGET_NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "Blocked, a stale lookup, or a reused key",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "device_already_blocked", value = """
                                    { "code": "409 CONFLICT", "message": "This phone is already blocked (reference SEC-5T9W1H).", "data": { "errorCode": "device_already_blocked" } }
                                    """),
                            @ExampleObject(name = "lookup_expired", value = LOOKUP_EXPIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_reused", value = IDEMPOTENCY_REUSED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "429", description = "Your lookup limit",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "503", description = "The change could not be sealed, so it was not made",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = AUDIT_UNAVAILABLE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<AppActionResult>> resetTrust(@PathVariable UUID deviceId,
                                                                 @Valid @RequestBody AppDeviceWrite body,
                                                                 @RequestHeader(value = "Idempotency-Key",
                                                                         required = false) String idempotencyKey,
                                                                 Authentication auth, HttpServletRequest request) {
        return run(Op.RESET_TRUST, "Phone reset", Target.device(deviceId), deviceWrite(body), idempotencyKey, auth,
                request);
    }

    // ---- one number --------------------------------------------------------------------------

    @PostMapping("/fraud-flag")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_FRAUD + "')")
    @Operation(summary = "Set or clear the fraud flag on one of the customer's numbers (fraud desk)",
            description = "A flagged number's phones are trusted for 30 days instead of 90 (applied at once), and "
                    + "every sign-in scores higher. The number rides in the body and must be one your search returned "
                    + "in innbucksApp.")
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true, example = KEY_EXAMPLE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Fraud flag updated", "data": { "outcome": "SUCCESS", "whatHappensNext": "Flagged. Every phone on this number is now trusted for 30 days at most, and its sign-ins score higher. Nothing is sent to the customer.", "replayed": false, "phone": """
                            + PHONE_FLAGGED + " } }"))),
            @ApiResponse(responseCode = "400", description = "No phone, no flagged, no lookupId, no Idempotency-Key, or no note",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "phone_required", value = PHONE_REQUIRED),
                            @ExampleObject(name = "idempotency_key_required", value = IDEMPOTENCY_REQUIRED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "403", description = "Missing device-security:fraud, or not on this customer",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "missing_permission", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "support_self_action", value = SELF_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "404", description = "Not a number of this search",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = TARGET_NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "A stale lookup, or a reused key",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "lookup_expired", value = LOOKUP_EXPIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_reused", value = IDEMPOTENCY_REUSED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "429", description = "Your lookup limit",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "503", description = "The change could not be sealed, so it was not made",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = AUDIT_UNAVAILABLE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<AppActionResult>> fraudFlag(@Valid @RequestBody AppFraudFlagWrite body,
                                                                @RequestHeader(value = "Idempotency-Key",
                                                                        required = false) String idempotencyKey,
                                                                Authentication auth, HttpServletRequest request) {
        return run(Op.FRAUD_FLAG, "Fraud flag updated", Target.number(body.phone()),
                new Write(body.lookupId(), body.note(), body.caseId(), null, null, null, body.flagged()),
                idempotencyKey, auth, request);
    }

    @PostMapping("/cancel-codes")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_MANAGE + "')")
    @Operation(summary = "Cancel every open code for one of the customer's numbers",
            description = "For \"I got a code I didn't ask for\": whoever asked for it can no longer use it. The "
                    + "number rides in the body.")
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true, example = KEY_EXAMPLE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Open codes cancelled", "data": { "outcome": "SUCCESS", "whatHappensNext": "1 open code was cancelled: whoever asked for it can no longer use it. Tell the caller never to share a code.", "replayed": false, "phone": """
                            + PHONE_TRUSTED + " } }"))),
            @ApiResponse(responseCode = "400", description = "No phone, no lookupId, no Idempotency-Key, or no note",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "phone_required", value = PHONE_REQUIRED),
                            @ExampleObject(name = "idempotency_key_required", value = IDEMPOTENCY_REQUIRED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "403", description = "Missing permission, or not on this customer",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "missing_permission", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "support_self_action", value = SELF_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "404", description = "Not a number of this search",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = TARGET_NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "A stale lookup, or a reused key",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "lookup_expired", value = LOOKUP_EXPIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_reused", value = IDEMPOTENCY_REUSED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "429", description = "Your lookup limit",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "503", description = "The change could not be sealed, so it was not made",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = AUDIT_UNAVAILABLE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<AppActionResult>> cancelCodes(@Valid @RequestBody AppPhoneWrite body,
                                                                  @RequestHeader(value = "Idempotency-Key",
                                                                          required = false) String idempotencyKey,
                                                                  Authentication auth, HttpServletRequest request) {
        return run(Op.CANCEL_CODES, "Open codes cancelled", Target.number(body.phone()), phoneWrite(body),
                idempotencyKey, auth, request);
    }

    @PostMapping("/sign-out")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_MANAGE + "')")
    @Operation(summary = "Sign the customer out of the Super app (lost or stolen phone)",
            description = """
                    For a lost or stolen phone. In one action: every session of the InnBucks account on this number \
                    ends at once (signed out everywhere; the account is NOT deactivated), every phone of the number \
                    that is not already blocked is blocked until unlocked (*569# or support), and every open code is \
                    cancelled. The customer gets an SMS for each phone blocked.

                    Not reached: Rewards' own sessions (Rewards has its own sign-out), and the InnBucks platform's \
                    session on the handset, which ends at its next renewal, within 15 minutes, because the phone is \
                    blocked. Also ask the customer to have the SIM blocked at their network. Staff accounts are \
                    refused (403 console_staff_account).""")
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true, example = KEY_EXAMPLE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed out",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Signed out of the Super app", "data": { "outcome": "SUCCESS", "whatHappensNext": "Every session of the customer's InnBucks account has ended; they'll sign in again with a code and their PIN. 1 phone is blocked until the customer unlocks it on *569# or support lifts the block, and every open code is cancelled. The app on a blocked phone stops working at its next renewal, within 15 minutes. Rewards has its own sign-out. If the SIM is lost, ask the customer to have it blocked at their network too.", "replayed": false, "phone": """
                            + PHONE_BLOCKED + " } }"))),
            @ApiResponse(responseCode = "400", description = "No phone, no lookupId, no Idempotency-Key, or no note",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "phone_required", value = PHONE_REQUIRED),
                            @ExampleObject(name = "lookup_required", value = LOOKUP_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_required", value = IDEMPOTENCY_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "note_required", value = NOTE_EMPTY_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "403", description = "Missing device-security:manage, a staff account, or not on this customer",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "missing_permission", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "console_staff_account", value = CONSOLE_STAFF_EXAMPLE),
                            @ExampleObject(name = "support_self_action", value = SELF_EXAMPLE),
                            @ExampleObject(name = "staff_target_requires_supervisor", value = STAFF_TARGET_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "404", description = "Not a number of this search, or support switched off",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "target_not_found", value = TARGET_NOT_FOUND_EXAMPLE),
                            @ExampleObject(name = "support_disabled", value = DISABLED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "409", description = "A stale lookup, or a reused key",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "lookup_expired", value = LOOKUP_EXPIRED_EXAMPLE),
                            @ExampleObject(name = "section_not_in_lookup", value = LOOKUP_MISSING_SECTION_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_reused", value = IDEMPOTENCY_REUSED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "429", description = "Your lookup limit",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "503", description = "The change could not be sealed, so it was not made",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = AUDIT_UNAVAILABLE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<AppActionResult>> signOut(@Valid @RequestBody AppPhoneWrite body,
                                                              @RequestHeader(value = "Idempotency-Key",
                                                                      required = false) String idempotencyKey,
                                                              Authentication auth, HttpServletRequest request) {
        return run(Op.SIGN_OUT, "Signed out of the Super app", Target.number(body.phone()), phoneWrite(body),
                idempotencyKey, auth, request);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static Write deviceWrite(AppDeviceWrite body) {
        return new Write(body.lookupId(), body.note(), body.caseId(), null, null, null, null);
    }

    private static Write phoneWrite(AppPhoneWrite body) {
        return new Write(body.lookupId(), body.note(), body.caseId(), null, null, null, null);
    }

    private ResponseEntity<ApiResult<AppActionResult>> run(Op op, String message, Target target, Write write,
                                                           String idempotencyKey, Authentication auth,
                                                           HttpServletRequest request) {
        SupportAgent agent = agents.require(auth);
        AuditContext ctx = new AuditContext(DeviceSecurityRequests.clientIp(request), request.getHeader("User-Agent"));
        AppActionResult result = actions.execute(op, agent, target, write, idempotencyKey, ctx);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.ok(message, result));
    }
}
