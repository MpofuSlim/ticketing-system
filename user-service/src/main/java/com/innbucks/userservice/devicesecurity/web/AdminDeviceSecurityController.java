package com.innbucks.userservice.devicesecurity.web;

import com.innbucks.userservice.devicesecurity.DeviceSecurityRequests;
import com.innbucks.userservice.devicesecurity.DeviceSupportService;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.ActionResult;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.BanRequest;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.BlockRequest;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.CustomerOverview;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.EventView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.FraudFlagRequest;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.NoteRequest;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.PageView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.ProfileView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.SupportDeviceView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.SupportRefLookup;
import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.support.DeviceSecurityReadAccess;
import com.innbucks.userservice.support.SupportCustomerKeys;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The call center's and fraud desk's device-security console
 * ({@code /admin/device-security}). Look a caller up by number or by the
 * reference on their screen, see each phone's state with a sentence telling the
 * agent what to do, and block, unlock, remove, reset or ban. Every change needs a
 * note, is sealed on the audit chain under the agent's identity, and tells the
 * customer by SMS/WhatsApp.
 *
 * <p>Permissions: {@code device-security:read} (look up), {@code
 * device-security:manage} (block, unlock, remove, reset, cancel codes) and
 * {@code device-security:fraud} (bans, lifting fraud-desk bans, the fraud flag,
 * the same-handset view). SUPER_ADMIN holds all three through its wildcard; the
 * built-in roles (V43) carry the rest: {@code CALL_CENTER_AGENT} and
 * {@code CALL_CENTER_SUPERVISOR} hold read + manage, and {@code FRAUD_DESK} — an
 * add-on held alongside one of them — holds read + fraud.
 */
@RestController
@RequestMapping("/admin/device-security")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin - Device security (call center)",
        description = "Customer-support tools for the DTX device registry: look up a caller's phones by number or "
                + "reference, and block, unlock, remove, reset or ban a phone.")
@SecurityRequirement(name = "bearerAuth")
public class AdminDeviceSecurityController {

    private final DeviceSupportService service;
    /**
     * Customer support's rules for these READS (V45): each counts against the
     * agent's lookup limit and is recorded in support_access_log. The contract is
     * otherwise unchanged — no lookupId is required here.
     */
    private final DeviceSecurityReadAccess access;

    // ---- look up -------------------------------------------------------------------

    @GetMapping("/customers/{msisdn}")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_READ + "')")
    @Operation(summary = "Everything about a caller's phones, on one screen",
            description = "The first call when a customer phones in. `msisdn` in any spelling (0771234512, "
                    + "+263771234512). Each phone carries `agentGuidance`; `summary` is one line for the top of the screen.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The overview",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Customer device overview",
                              "data": {
                                "msisdn": "+263771234512",
                                "profile": { "preferredChannel": "WHATSAPP", "fraudFlagged": false, "fraudFlaggedAt": null, "fraudFlagNote": null, "pinIssuedAt": "2026-07-02T08:15:30+02:00", "lastSignInAt": "2026-09-29T11:58:14+02:00" },
                                "devices": [
                                  {
                                    "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "msisdn": "+263771234512", "label": "Samsung SM-A155F · Android 15",
                                    "platform": "android", "osVersion": "15", "model": "SM-A155F", "manufacturer": "samsung", "appVersion": "2.4.0",
                                    "state": "TEMP_BLOCKED", "stateReason": "OTP_ATTEMPTS", "blockedAt": "2026-09-29T16:15:00+02:00", "blockedUntil": "2026-09-29T16:30:00+02:00",
                                    "supportRef": "SEC-8F2KQ7", "ussdUnlockable": true, "unlockableAfter": null, "trustedUntil": null,
                                    "boundAt": "2026-07-02T08:16:40+02:00", "coolingUntil": null, "otpVerifiedAt": "2026-07-02T08:15:52+02:00", "firstSeenAt": "2026-07-02T08:14:03+02:00",
                                    "lastSeenAt": "2026-09-29T16:14:51+02:00", "lastSeenNear": "Harare", "lastIp": "41.221.147.12", "lastUnlockedAt": null,
                                    "deviceWideBan": null, "otherAccountsOnDevice": 0,
                                    "agentGuidance": "Paused until 16:30 and lifts on its own after too many wrong codes. You may unlock it now if the caller passes verification; the next sign-in still asks for a code."
                                  }
                                ],
                                "counters": { "otpChallengesLastHour": 2, "otpChallengesLastDay": 3, "ussdUnlockAttemptsLastDay": 0, "openChallenges": 0 },
                                "recentEvents": [
                                  { "id": 48213, "occurredAt": "2026-09-29T16:15:00+02:00", "type": "DEVICE_TEMP_BLOCKED", "description": "Phone paused (OTP_ATTEMPTS)", "decision": "TEMP_BLOCKED", "evaluatedDecision": null, "reason": "OTP_ATTEMPTS", "supportRef": "SEC-8F2KQ7", "actorType": "SYSTEM", "actorId": null, "channel": null, "purpose": null, "context": null, "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "ussdSessionId": null, "ipAddress": null, "riskScore": null, "features": { "deadChallengesInARow": 2 }, "note": null }
                                ],
                                "summary": "0 phones signed in; Samsung SM-A155F · Android 15 paused until 16:30 (SEC-8F2KQ7)."
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "400", description = "Not a mobile number",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "400 BAD_REQUEST", "message": "That isn't a valid mobile number.", "data": { "errorCode": "invalid_request", "field": "msisdn" } }
                            """))),
            @ApiResponse(responseCode = "403", description = "Missing device-security:read",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                            """))),
            @ApiResponse(responseCode = "429", description = "The agent's customer-lookup limit (shared with /admin/support)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "429 TOO_MANY_REQUESTS", "message": "You've looked up a lot of customers in a short time. Try again in 4 minutes.", "data": { "errorCode": "lookup_rate_limited", "retryAfterSeconds": 212, "window": "10m" } }
                            """)))
    })
    public ResponseEntity<ApiResult<CustomerOverview>> overview(@PathVariable String msisdn, Authentication auth,
                                                                HttpServletRequest request) {
        CustomerOverview overview = access.read(auth, DeviceSecurityRequests.clientIp(request),
                DeviceSecurityReadAccess.OP_OVERVIEW, DeviceSecurityReadAccess.Subject.phone(msisdn),
                () -> service.overview(msisdn), o -> phoneKeys(o.msisdn()));
        return ResponseEntity.ok(ApiResult.ok("Customer device overview", overview));
    }

    @GetMapping("/customers/{msisdn}/events")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_READ + "')")
    @Operation(summary = "A caller's full device-security history, newest first",
            description = "Every sign-in check, code, login result, block, ban and unlock, with the features that led "
                    + "to each decision. Filter with `type` (repeatable), e.g. `type=DEVICE_BANNED&type=DEVICE_UNLOCKED`. "
                    + "Kept 12 months.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of events",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Device-security events",
                              "data": {
                                "content": [
                                  { "id": 48213, "occurredAt": "2026-09-29T16:15:00+02:00", "type": "DEVICE_TEMP_BLOCKED", "description": "Phone paused (OTP_ATTEMPTS)", "decision": "TEMP_BLOCKED", "evaluatedDecision": null, "reason": "OTP_ATTEMPTS", "supportRef": "SEC-8F2KQ7", "actorType": "SYSTEM", "actorId": null, "channel": null, "purpose": null, "context": null, "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "ussdSessionId": null, "ipAddress": null, "riskScore": null, "features": { "deadChallengesInARow": 2 }, "note": null },
                                  { "id": 48209, "occurredAt": "2026-09-29T16:14:51+02:00", "type": "OTP_CHALLENGE_DEAD", "description": "Out of code attempts", "decision": null, "evaluatedDecision": null, "reason": null, "supportRef": null, "actorType": "APP", "actorId": null, "channel": "SMS", "purpose": null, "context": null, "deviceId": null, "ussdSessionId": null, "ipAddress": null, "riskScore": null, "features": null, "note": null }
                                ],
                                "totalElements": 2, "totalPages": 1, "number": 0, "size": 50
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "429", description = "The agent's customer-lookup limit (shared with /admin/support)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "429 TOO_MANY_REQUESTS", "message": "You've looked up a lot of customers in a short time. Try again in 4 minutes.", "data": { "errorCode": "lookup_rate_limited", "retryAfterSeconds": 212, "window": "10m" } }
                            """)))
    })
    public ResponseEntity<ApiResult<PageView<EventView>>> events(@PathVariable String msisdn,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "50") int size,
                                                                 @RequestParam(required = false) List<String> type,
                                                                 Authentication auth, HttpServletRequest request) {
        PageView<EventView> events = access.read(auth, DeviceSecurityRequests.clientIp(request),
                DeviceSecurityReadAccess.OP_EVENTS, DeviceSecurityReadAccess.Subject.phone(msisdn),
                () -> service.events(msisdn, page, size, type),
                p -> access.typedPhoneKeys(msisdn));
        return ResponseEntity.ok(ApiResult.ok("Device-security events", events));
    }

    @GetMapping("/support-refs/{supportRef}")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_READ + "')")
    @Operation(summary = "Look up the reference the caller reads from their screen (SEC-XXXXXX)",
            description = "Forgiving of how it was typed: case, spaces, a missing SEC- and the O/0, I/1, L/1 confusions.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The block/ban/unlock behind the reference",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Reference found",
                              "data": {
                                "supportRef": "SEC-8F2KQ7",
                                "msisdn": "+263771234512",
                                "device": { "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "msisdn": "+263771234512", "label": "Samsung SM-A155F · Android 15", "platform": "android", "osVersion": "15", "model": "SM-A155F", "manufacturer": "samsung", "appVersion": "2.4.0", "state": "TEMP_BLOCKED", "stateReason": "OTP_ATTEMPTS", "blockedAt": "2026-09-29T16:15:00+02:00", "blockedUntil": "2026-09-29T16:30:00+02:00", "supportRef": "SEC-8F2KQ7", "ussdUnlockable": true, "unlockableAfter": null, "trustedUntil": null, "boundAt": "2026-07-02T08:16:40+02:00", "coolingUntil": null, "otpVerifiedAt": "2026-07-02T08:15:52+02:00", "firstSeenAt": "2026-07-02T08:14:03+02:00", "lastSeenAt": "2026-09-29T16:14:51+02:00", "lastSeenNear": "Harare", "lastIp": "41.221.147.12", "lastUnlockedAt": null, "deviceWideBan": null, "otherAccountsOnDevice": 0, "agentGuidance": "Paused until 16:30 and lifts on its own after too many wrong codes. You may unlock it now if the caller passes verification; the next sign-in still asks for a code." },
                                "events": [
                                  { "id": 48213, "occurredAt": "2026-09-29T16:15:00+02:00", "type": "DEVICE_TEMP_BLOCKED", "description": "Phone paused (OTP_ATTEMPTS)", "decision": "TEMP_BLOCKED", "evaluatedDecision": null, "reason": "OTP_ATTEMPTS", "supportRef": "SEC-8F2KQ7", "actorType": "SYSTEM", "actorId": null, "channel": null, "purpose": null, "context": null, "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "ussdSessionId": null, "ipAddress": null, "riskScore": null, "features": { "deadChallengesInARow": 2 }, "note": null }
                                ]
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "404", description = "No such reference",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "404 NOT_FOUND", "message": "No block, ban or unlock carries reference SEC-8F2KQ9. Check the spelling with the caller.", "data": { "errorCode": "support_ref_not_found" } }
                            """))),
            @ApiResponse(responseCode = "429", description = "The agent's customer-lookup limit (shared with /admin/support)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "429 TOO_MANY_REQUESTS", "message": "You've looked up a lot of customers in a short time. Try again in 4 minutes.", "data": { "errorCode": "lookup_rate_limited", "retryAfterSeconds": 212, "window": "10m" } }
                            """)))
    })
    public ResponseEntity<ApiResult<SupportRefLookup>> bySupportRef(@PathVariable String supportRef, Authentication auth,
                                                                    HttpServletRequest request) {
        SupportRefLookup found = access.read(auth, DeviceSecurityRequests.clientIp(request),
                DeviceSecurityReadAccess.OP_SUPPORT_REF, DeviceSecurityReadAccess.Subject.reference(supportRef),
                () -> service.bySupportRef(supportRef),
                r -> new SupportCustomerKeys(r.msisdn() == null ? List.of() : List.of(r.msisdn()), List.of(),
                        List.of(), List.of(), r.supportRef()));
        return ResponseEntity.ok(ApiResult.ok("Reference found", found));
    }

    @GetMapping("/devices")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_READ + "')")
    @Operation(summary = "The queue of stopped phones, most recent first",
            description = "`state` = TEMP_BLOCKED or BANNED (omit for both). The fraud desk's worklist.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of phones",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Stopped phones", "data": { "content": [], "totalElements": 0, "totalPages": 0, "number": 0, "size": 20 } }
                            """))),
            @ApiResponse(responseCode = "400", description = "Unknown state",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "400 BAD_REQUEST", "message": "state must be TEMP_BLOCKED or BANNED.", "data": { "errorCode": "invalid_request", "field": "state" } }
                            """))),
            @ApiResponse(responseCode = "429", description = "The agent's customer-lookup limit (shared with /admin/support)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "429 TOO_MANY_REQUESTS", "message": "You've looked up a lot of customers in a short time. Try again in 4 minutes.", "data": { "errorCode": "lookup_rate_limited", "retryAfterSeconds": 212, "window": "10m" } }
                            """)))
    })
    public ResponseEntity<ApiResult<PageView<SupportDeviceView>>> stopped(@RequestParam(required = false) String state,
                                                                          @RequestParam(defaultValue = "0") int page,
                                                                          @RequestParam(defaultValue = "20") int size,
                                                                          Authentication auth,
                                                                          HttpServletRequest request) {
        PageView<SupportDeviceView> stopped = access.read(auth, DeviceSecurityRequests.clientIp(request),
                DeviceSecurityReadAccess.OP_STOPPED, DeviceSecurityReadAccess.Subject.none(),
                () -> service.stopped(state, page, size),
                p -> phoneKeys(p.content().stream().map(SupportDeviceView::msisdn).toList()));
        return ResponseEntity.ok(ApiResult.ok("Stopped phones", stopped));
    }

    @GetMapping("/devices/{deviceId}/same-handset")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_FRAUD + "')")
    @Operation(summary = "Every account paired with the same physical phone (fraud desk)",
            description = "The mule-farm view (§8.2). Shows other customers' numbers, hence fraud-desk only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pairs on the same handset",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Accounts on this handset", "data": [] }
                            """))),
            @ApiResponse(responseCode = "429", description = "The agent's customer-lookup limit (shared with /admin/support)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "429 TOO_MANY_REQUESTS", "message": "You've looked up a lot of customers in a short time. Try again in 4 minutes.", "data": { "errorCode": "lookup_rate_limited", "retryAfterSeconds": 212, "window": "10m" } }
                            """)))
    })
    public ResponseEntity<ApiResult<List<SupportDeviceView>>> sameHandset(@PathVariable UUID deviceId,
                                                                         Authentication auth,
                                                                         HttpServletRequest request) {
        List<SupportDeviceView> pairs = access.read(auth, DeviceSecurityRequests.clientIp(request),
                DeviceSecurityReadAccess.OP_SAME_HANDSET, DeviceSecurityReadAccess.Subject.target(deviceId.toString()),
                () -> service.sameHandset(deviceId),
                l -> phoneKeys(l.stream().map(SupportDeviceView::msisdn).toList()));
        return ResponseEntity.ok(ApiResult.ok("Accounts on this handset", pairs));
    }

    // ---- act -----------------------------------------------------------------------

    @PostMapping("/devices/{deviceId}/block")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_MANAGE + "')")
    @Operation(summary = "Block a phone",
            description = "`UNTIL_UNLOCKED` for a lost or stolen phone: blocked from this account until the customer "
                    + "unlocks it on *569# (Unlock device) or support lifts it. `TEMPORARY` pauses sign-in for "
                    + "`durationMinutes` (15 min – 7 days) and lifts on its own. Either way the phone's open codes and "
                    + "login tickets die at once, a silent renewal cuts it off within 15 minutes, and the customer gets "
                    + "an SMS with the reference.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Blocked",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Phone blocked",
                              "data": {
                                "device": { "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "msisdn": "+263771234512", "label": "Samsung SM-A155F · Android 15", "platform": "android", "osVersion": "15", "model": "SM-A155F", "manufacturer": "samsung", "appVersion": "2.4.0", "state": "BANNED", "stateReason": "CUSTOMER_REPORTED", "blockedAt": "2026-09-29T16:40:12+02:00", "blockedUntil": null, "supportRef": "SEC-5T9W1H", "ussdUnlockable": true, "unlockableAfter": null, "trustedUntil": null, "boundAt": "2026-07-02T08:16:40+02:00", "coolingUntil": null, "otpVerifiedAt": "2026-07-02T08:15:52+02:00", "firstSeenAt": "2026-07-02T08:14:03+02:00", "lastSeenAt": "2026-09-29T16:14:51+02:00", "lastSeenNear": "Harare", "lastIp": "41.221.147.12", "lastUnlockedAt": null, "deviceWideBan": null, "otherAccountsOnDevice": 0, "agentGuidance": "The customer blocked it (lost or stolen). The customer can unlock it themselves on *569# (Unlock device). You may unlock it after verifying the caller." },
                                "whatHappensNext": "The phone is blocked from this account until the customer unlocks it on *569# (or support lifts it). The customer has been sent an SMS with reference SEC-5T9W1H."
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "400", description = "Validation",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "note": "note is required" } }
                            """))),
            @ApiResponse(responseCode = "404", description = "No such phone",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "404 NOT_FOUND", "message": "No phone with that deviceId.", "data": { "errorCode": "device_not_found" } }
                            """))),
            @ApiResponse(responseCode = "409", description = "Already blocked",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "409 CONFLICT", "message": "This phone is already blocked (reference SEC-5T9W1H).", "data": { "errorCode": "device_already_blocked" } }
                            """)))
    })
    public ResponseEntity<ApiResult<ActionResult>> block(@PathVariable UUID deviceId, @Valid @RequestBody BlockRequest body,
                                                         Authentication auth, HttpServletRequest request) {
        ActionResult r = service.block(deviceId, body, auth.getName(), audit(request));
        log.info("Support blocked device={} mode={} by={}", deviceId, body.mode(), auth.getName());
        return ResponseEntity.ok(ApiResult.ok("Phone blocked", r));
    }

    @PostMapping("/devices/{deviceId}/ban")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_FRAUD + "')")
    @Operation(summary = "Ban a phone for fraud (fraud desk)",
            description = "`FRAUD_SUSPECTED` bans this account on this phone; the customer can unlock on *569# after 24 "
                    + "hours. `CONFIRMED_FRAUD` and `SHARED_DEVICE` ban the PHONE for every account on it, support only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Banned",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Phone banned", "data": { "device": { "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "msisdn": "+263771234512", "label": "Samsung SM-A155F · Android 15", "platform": "android", "osVersion": "15", "model": "SM-A155F", "manufacturer": "samsung", "appVersion": "2.4.0", "state": "BANNED", "stateReason": "CONFIRMED_FRAUD", "blockedAt": "2026-09-29T17:02:00+02:00", "blockedUntil": null, "supportRef": "SEC-2B6N9R", "ussdUnlockable": false, "unlockableAfter": null, "trustedUntil": null, "boundAt": "2026-07-02T08:16:40+02:00", "coolingUntil": null, "otpVerifiedAt": "2026-07-02T08:15:52+02:00", "firstSeenAt": "2026-07-02T08:14:03+02:00", "lastSeenAt": "2026-09-29T16:14:51+02:00", "lastSeenNear": "Harare", "lastIp": "41.221.147.12", "lastUnlockedAt": null, "deviceWideBan": { "reason": "CONFIRMED_FRAUD", "supportRef": "SEC-2B6N9R", "bannedAt": "2026-09-29T17:02:00+02:00", "bannedBy": "SUPPORT:fraud.desk@innbucks.co.zw" }, "otherAccountsOnDevice": 0, "agentGuidance": "Blocked on EVERY account on this phone (CONFIRMED_FRAUD). Fraud desk only — escalate with reference SEC-2B6N9R. Do not unlock on the caller's word." }, "whatHappensNext": "The phone is now blocked for EVERY account on it. Only the fraud desk can lift this. Reference SEC-2B6N9R." } }
                            """))),
            @ApiResponse(responseCode = "403", description = "Missing device-security:fraud",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                            """)))
    })
    public ResponseEntity<ApiResult<ActionResult>> ban(@PathVariable UUID deviceId, @Valid @RequestBody BanRequest body,
                                                       Authentication auth, HttpServletRequest request) {
        ActionResult r = service.ban(deviceId, body, auth.getName(), audit(request));
        log.info("Support banned device={} reason={} by={}", deviceId, body.reason(), auth.getName());
        return ResponseEntity.ok(ApiResult.ok("Phone banned", r));
    }

    @PostMapping("/devices/{deviceId}/unlock")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_MANAGE + "')")
    @Operation(summary = "Unlock a paused or blocked phone",
            description = "Lifts a pause or a block. The phone does NOT go straight back to trusted: the next sign-in "
                    + "asks for a code, then the PIN. Bans only the fraud desk may lift (SHARED_DEVICE, CONFIRMED_FRAUD, "
                    + "SIM_SWAP, any ban on every account) additionally need device-security:fraud.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Unlocked",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Phone unlocked", "data": { "device": { "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "msisdn": "+263771234512", "label": "Samsung SM-A155F · Android 15", "platform": "android", "osVersion": "15", "model": "SM-A155F", "manufacturer": "samsung", "appVersion": "2.4.0", "state": "STEP_UP", "stateReason": "UNLOCKED", "blockedAt": null, "blockedUntil": null, "supportRef": "SEC-3M7Q2X", "ussdUnlockable": false, "unlockableAfter": null, "trustedUntil": null, "boundAt": "2026-07-02T08:16:40+02:00", "coolingUntil": null, "otpVerifiedAt": "2026-07-02T08:15:52+02:00", "firstSeenAt": "2026-07-02T08:14:03+02:00", "lastSeenAt": "2026-09-29T16:14:51+02:00", "lastSeenNear": "Harare", "lastIp": "41.221.147.12", "lastUnlockedAt": "2026-09-29T16:22:07+02:00", "deviceWideBan": null, "otherAccountsOnDevice": 0, "agentGuidance": "The next sign-in on this phone asks for a code (it was just unlocked). If codes aren't arriving, check the counters and try the other channel." }, "whatHappensNext": "Unlocked. The next sign-in on this phone will ask for a code, then the PIN. The customer has been sent an SMS." } }
                            """))),
            @ApiResponse(responseCode = "403", description = "Needs the fraud desk",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "403 FORBIDDEN", "message": "Only the fraud desk can lift this block. Escalate with reference SEC-2B6N9R.", "data": { "errorCode": "fraud_desk_required" } }
                            """))),
            @ApiResponse(responseCode = "409", description = "Not blocked",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "409 CONFLICT", "message": "This phone isn't blocked, so there is nothing to unlock.", "data": { "errorCode": "device_not_blocked" } }
                            """)))
    })
    public ResponseEntity<ApiResult<ActionResult>> unlock(@PathVariable UUID deviceId, @Valid @RequestBody NoteRequest body,
                                                          Authentication auth, HttpServletRequest request) {
        boolean fraud = auth.getAuthorities().stream()
                .anyMatch(a -> PermissionCatalog.DEVICE_SECURITY_FRAUD.equals(a.getAuthority()));
        ActionResult r = service.unlock(deviceId, body.note(), auth.getName(), audit(request), fraud);
        log.info("Support unlocked device={} by={}", deviceId, auth.getName());
        return ResponseEntity.ok(ApiResult.ok("Phone unlocked", r));
    }

    @PostMapping("/devices/{deviceId}/revoke")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_MANAGE + "')")
    @Operation(summary = "Remove a phone from the account",
            description = "Same as the customer removing it in Your devices: signed out within 15 minutes, and signing "
                    + "in on it again needs a code. A blocked phone can't be removed (it would start again as new).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Removed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Phone removed", "data": { "device": { "deviceId": "6a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", "msisdn": "+263771234512", "label": "iPhone 12 · iOS 17.5", "platform": "ios", "osVersion": "17.5", "model": "iPhone 12", "manufacturer": "Apple", "appVersion": "2.3.1", "state": "REVOKED", "stateReason": null, "blockedAt": null, "blockedUntil": null, "supportRef": null, "ussdUnlockable": false, "unlockableAfter": null, "trustedUntil": null, "boundAt": "2025-11-20T18:41:02+02:00", "coolingUntil": null, "otpVerifiedAt": "2026-07-02T08:15:52+02:00", "firstSeenAt": "2025-11-20T18:40:10+02:00", "lastSeenAt": "2026-08-30T21:05:44+02:00", "lastSeenNear": "Bulawayo", "lastIp": "197.221.250.4", "lastUnlockedAt": null, "deviceWideBan": null, "otherAccountsOnDevice": 0, "agentGuidance": "Removed from the account. Signing in on it again starts as a new phone, with a code." }, "whatHappensNext": "Removed. The phone is signed out within 15 minutes; signing in on it again needs a code." } }
                            """))),
            @ApiResponse(responseCode = "409", description = "Already removed, or blocked",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "409 CONFLICT", "message": "This phone has already been removed from the account.", "data": { "errorCode": "device_already_removed" } }
                            """)))
    })
    public ResponseEntity<ApiResult<ActionResult>> revoke(@PathVariable UUID deviceId, @Valid @RequestBody NoteRequest body,
                                                          Authentication auth, HttpServletRequest request) {
        ActionResult r = service.revoke(deviceId, body.note(), auth.getName(), audit(request));
        log.info("Support revoked device={} by={}", deviceId, auth.getName());
        return ResponseEntity.ok(ApiResult.ok("Phone removed", r));
    }

    @PostMapping("/devices/{deviceId}/reset-trust")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_MANAGE + "')")
    @Operation(summary = "Reset a phone to brand-new (the §14 trust-reset tool)",
            description = "Forgets the pairing: the next sign-in is a first sign-in (code, PIN, new-phone notice). "
                    + "For testing the new-device path on staging and for support cases like a phone that changed hands. "
                    + "Silent — the customer is not messaged.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reset",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Phone reset", "data": { "device": { "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "msisdn": "+263771234512", "label": "Samsung SM-A155F · Android 15", "platform": "android", "osVersion": "15", "model": "SM-A155F", "manufacturer": "samsung", "appVersion": "2.4.0", "state": "NEW", "stateReason": null, "blockedAt": null, "blockedUntil": null, "supportRef": null, "ussdUnlockable": false, "unlockableAfter": null, "trustedUntil": null, "boundAt": null, "coolingUntil": null, "otpVerifiedAt": "2026-07-02T08:15:52+02:00", "firstSeenAt": "2026-07-02T08:14:03+02:00", "lastSeenAt": "2026-09-29T16:14:51+02:00", "lastSeenNear": "Harare", "lastIp": "41.221.147.12", "lastUnlockedAt": null, "deviceWideBan": null, "otherAccountsOnDevice": 0, "agentGuidance": "This phone tried to sign in but was never confirmed. If the customer doesn't recognise it, block it (UNTIL_UNLOCKED)." }, "whatHappensNext": "Reset. The next sign-in on this phone is treated as a brand-new phone (code, then PIN)." } }
                            """)))
    })
    public ResponseEntity<ApiResult<ActionResult>> resetTrust(@PathVariable UUID deviceId,
                                                              @Valid @RequestBody NoteRequest body,
                                                              Authentication auth, HttpServletRequest request) {
        ActionResult r = service.resetTrust(deviceId, body.note(), auth.getName(), audit(request));
        log.info("Support reset device trust device={} by={}", deviceId, auth.getName());
        return ResponseEntity.ok(ApiResult.ok("Phone reset", r));
    }

    @PutMapping("/customers/{msisdn}/fraud-flag")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_FRAUD + "')")
    @Operation(summary = "Set or clear a customer's fraud flag (fraud desk)",
            description = "A flagged customer's phones are trusted for 30 days instead of 90 (applied immediately), and "
                    + "every sign-in scores higher.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Fraud flag updated", "data": { "preferredChannel": "WHATSAPP", "fraudFlagged": true, "fraudFlaggedAt": "2026-09-29T17:05:00+02:00", "fraudFlagNote": "Customer was the victim of a SIM-swap attempt on 2026-09-20.", "pinIssuedAt": "2026-07-02T08:15:30+02:00", "lastSignInAt": "2026-09-29T11:58:14+02:00" } }
                            """)))
    })
    public ResponseEntity<ApiResult<ProfileView>> fraudFlag(@PathVariable String msisdn,
                                                            @Valid @RequestBody FraudFlagRequest body,
                                                            Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok(ApiResult.ok("Fraud flag updated",
                service.setFraudFlag(msisdn, body, auth.getName(), audit(request))));
    }

    @PostMapping("/customers/{msisdn}/cancel-codes")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.DEVICE_SECURITY_MANAGE + "')")
    @Operation(summary = "Cancel every open code for a number",
            description = "For \"I got a code I didn't ask for\": whoever asked for it can no longer use it. Tell the "
                    + "caller never to share a code, and consider blocking the phone that asked (see the events).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "1 open code cancelled", "data": { "cancelled": 1 } }
                            """)))
    })
    public ResponseEntity<ApiResult<Map<String, Object>>> cancelCodes(@PathVariable String msisdn,
                                                                      @Valid @RequestBody NoteRequest body,
                                                                      Authentication auth, HttpServletRequest request) {
        int n = service.voidCodes(msisdn, body.note(), auth.getName(), audit(request));
        return ResponseEntity.ok(ApiResult.ok(n + (n == 1 ? " open code" : " open codes") + " cancelled",
                Map.of("cancelled", n)));
    }

    private static SupportCustomerKeys phoneKeys(String msisdn) {
        return phoneKeys(msisdn == null ? List.of() : List.of(msisdn));
    }

    private static SupportCustomerKeys phoneKeys(List<String> msisdns) {
        return new SupportCustomerKeys(msisdns, List.of(), List.of(), List.of(), null);
    }

    private static AuditContext audit(HttpServletRequest request) {
        return new AuditContext(DeviceSecurityRequests.clientIp(request), request.getHeader("User-Agent"));
    }
}
