package com.innbucks.userservice.controller;

import com.innbucks.userservice.devicesecurity.DeviceSecurityRequests;
import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.support.ConsoleSupportActions;
import com.innbucks.userservice.support.SupportAgent;
import com.innbucks.userservice.support.SupportAgentResolver;
import com.innbucks.userservice.support.SupportSearchService;
import com.innbucks.userservice.support.dto.SupportDTOs.ActionResult;
import com.innbucks.userservice.support.dto.SupportDTOs.ConsoleAccountView;
import com.innbucks.userservice.support.dto.SupportDTOs.SearchRequest;
import com.innbucks.userservice.support.dto.SupportDTOs.SearchResult;
import com.innbucks.userservice.support.dto.SupportDTOs.WriteRequest;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified customer support — one search box, one screen per customer, and the
 * support actions an agent may take from it (Ask C, design §3). This release
 * builds the cross-cutting rules and two sections: the Foundry console account
 * and the InnBucks 2.0 app (DTX). Ticketize, InnRewards and Marketplace arrive as
 * further sections without a change to these shapes.
 *
 * <p>Everything here is enforced in user-service, because it is the only service
 * that reads the {@code perms} claim: the permissions, the lookup binding, the
 * access log, the per-agent limit, the masking and the server-rendered text.
 * Responses are {@code Cache-Control: no-store} — they carry customer data.
 * {@code /admin/support/**} rides the gateway's {@code user-admin-route}.
 */
@RestController
@RequestMapping("/admin/support")
@Tag(name = "Admin - Customer support",
        description = "Find a customer by phone, email or support reference; see their Foundry console account and "
                + "InnBucks app phones; unlock, send a reset code, or (supervisor) reset 2FA.")
@SecurityRequirement(name = "bearerAuth")
public class AdminSupportController {

    private static final String LIMITED_EXAMPLE = """
            { "code": "429 TOO_MANY_REQUESTS", "message": "You've looked up a lot of customers in a short time. Try again in 4 minutes.", "data": { "errorCode": "lookup_rate_limited", "retryAfterSeconds": 212, "window": "10m" } }
            """;
    private static final String FORBIDDEN_EXAMPLE = """
            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
            """;
    private static final String DISABLED_EXAMPLE = """
            { "code": "404 NOT_FOUND", "message": "Customer support isn't available on this server.", "data": { "errorCode": "support_disabled" } }
            """;
    private static final String LOOKUP_EXPIRED_EXAMPLE = """
            { "code": "409 CONFLICT", "message": "Search for the customer again.", "data": { "errorCode": "lookup_expired" } }
            """;
    private static final String LOOKUP_REQUIRED_EXAMPLE = """
            { "code": "400 BAD_REQUEST", "message": "Search for the customer first, then send the lookupId that search returned.", "data": { "errorCode": "lookup_required" } }
            """;
    private static final String TARGET_NOT_FOUND_EXAMPLE = """
            { "code": "404 NOT_FOUND", "message": "That account isn't part of this search. Search for the customer again.", "data": { "errorCode": "target_not_found" } }
            """;
    private static final String SELF_EXAMPLE = """
            { "code": "403 FORBIDDEN", "message": "You can't act on your own account. Ask a colleague.", "data": { "errorCode": "support_self_action" } }
            """;
    private static final String STAFF_TARGET_EXAMPLE = """
            { "code": "403 FORBIDDEN", "message": "This search matches an InnBucks staff account. Ask a supervisor.", "data": { "errorCode": "staff_target_requires_supervisor" } }
            """;
    private static final String CONSOLE_STAFF_EXAMPLE = """
            { "code": "403 FORBIDDEN", "message": "This is an InnBucks staff account; ask a SUPER_ADMIN.", "data": { "errorCode": "console_staff_account" } }
            """;
    private static final String IDEMPOTENCY_REQUIRED_EXAMPLE = """
            { "code": "400 BAD_REQUEST", "message": "Send an Idempotency-Key header holding a new UUID for each action.", "data": { "errorCode": "idempotency_key_required", "field": "Idempotency-Key" } }
            """;
    private static final String NOTE_REQUIRED_EXAMPLE = """
            { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "note": "note is required" } }
            """;
    private static final String IDEMPOTENCY_REUSED_EXAMPLE = """
            { "code": "409 CONFLICT", "message": "This Idempotency-Key was already used for a different action. Send a new one.", "data": { "errorCode": "idempotency_key_reused" } }
            """;
    private static final String AUDIT_UNAVAILABLE_EXAMPLE = """
            { "code": "503 SERVICE_UNAVAILABLE", "message": "We couldn't record this change, so it wasn't made. Try again.", "data": { "errorCode": "audit_unavailable" } }
            """;
    private static final String LOG_UNAVAILABLE_EXAMPLE = """
            { "code": "503 SERVICE_UNAVAILABLE", "message": "We couldn't record this lookup, so it wasn't shown. Try again.", "data": { "errorCode": "support_log_unavailable" } }
            """;
    private static final String ACCOUNT_JSON = """
            { "userId": 1042, "userUuid": "9b2f4c1e-6a7d-4e0b-8c3f-2d5e1a7b9c40", "name": "Tariro Moyo", "email": "tariro@example.com", "phone": "+263771234567", "status": "ACTIVE", "roles": ["MERCHANT_ADMIN"], "staffAccount": false, "mfaEnrolled": true, "lockedUntil": "2026-09-30T14:30:00+02:00", "mfaLockedUntil": null, "failedSignInAttempts": 5, "lastSignInAt": "2026-09-29T08:12:40+02:00", "mustChangePassword": false, "createdAt": "2026-03-02T10:15:00+02:00",
              "organizations": [ { "organizationId": "5c0e8a2d-31f4-4b6e-9d7a-0f1e2d3c4b5a", "name": "Moyo Fresh Foods", "role": "OWNER", "status": "ACTIVE", "products": ["loyalty"] } ],
              "serviceRequests": [ { "id": 311, "service": "marketplace", "status": "PENDING", "submittedAt": "2026-09-28T11:05:00+02:00", "decidedAt": null, "decisionReason": null, "guidance": "Waiting for an InnBucks administrator to review it (submitted 11:05 on 28 Sep). Support can't approve requests; if it's urgent, escalate to the product team." } ],
              "agentGuidance": "Locked after too many wrong passwords until 14:30. After verifying the caller you can unlock it now.",
              "actions": ["unlock", "send-password-reset"] }
            """;
    private static final String UNLOCKED_ACCOUNT_JSON = """
            { "userId": 1042, "userUuid": "9b2f4c1e-6a7d-4e0b-8c3f-2d5e1a7b9c40", "name": "Tariro Moyo", "email": "tariro@example.com", "phone": "+263771234567", "status": "ACTIVE", "roles": ["MERCHANT_ADMIN"], "staffAccount": false, "mfaEnrolled": true, "lockedUntil": null, "mfaLockedUntil": null, "failedSignInAttempts": 0, "lastSignInAt": "2026-09-29T08:12:40+02:00", "mustChangePassword": false, "createdAt": "2026-03-02T10:15:00+02:00",
              "organizations": [ { "organizationId": "5c0e8a2d-31f4-4b6e-9d7a-0f1e2d3c4b5a", "name": "Moyo Fresh Foods", "role": "OWNER", "status": "ACTIVE", "products": ["loyalty"] } ],
              "serviceRequests": [ { "id": 311, "service": "marketplace", "status": "PENDING", "submittedAt": "2026-09-28T11:05:00+02:00", "decidedAt": null, "decisionReason": null, "guidance": "Waiting for an InnBucks administrator to review it (submitted 11:05 on 28 Sep). Support can't approve requests; if it's urgent, escalate to the product team." } ],
              "agentGuidance": "Signs in normally. If they've forgotten the password, send a reset code.",
              "actions": ["send-password-reset"] }
            """;

    private final SupportSearchService search;
    private final ConsoleSupportActions consoleActions;
    private final SupportAgentResolver agents;

    public AdminSupportController(SupportSearchService search, ConsoleSupportActions consoleActions,
                                  SupportAgentResolver agents) {
        this.search = search;
        this.consoleActions = consoleActions;
        this.agents = agents;
    }

    // ---- search ------------------------------------------------------------------------------

    @PostMapping("/customers/search")
    @PreAuthorize("hasAnyAuthority('" + PermissionCatalog.SUPPORT_CONSOLE_READ + "', '"
            + PermissionCatalog.DEVICE_SECURITY_READ + "')")
    @Operation(summary = "Find a customer: one search box across the products you can see",
            description = "The query rides in the BODY (never the URL). A phone or email fans out to every section "
                    + "you hold the read permission for (console: support-console:read; innbucksApp: "
                    + "device-security:read). A SEC- reference returns ONLY the innbucksApp section plus `focus` — "
                    + "search by phone or email to see the rest. Card, voucher and collection codes are refused. "
                    + "Every search is recorded and counts against your lookup limit (60 per 10 minutes, 400 a day). "
                    + "Keep the `lookupId`: detail reads and actions for the next 30 minutes must send it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The customer, by section",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "phone", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Customer found",
                                      "data": {
                                        "lookupId": "SLK-7Q2M9X",
                                        "query": { "kind": "PHONE", "normalised": "+263771234567" },
                                        "customer": { "phone": "+263771234567", "email": "tariro@example.com", "name": "Tariro Moyo" },
                                        "identityWarnings": [],
                                        "sections": {
                                          "console": { "status": "OK", "matchedBy": "phone", "summary": "1 Foundry console account: Tariro Moyo (MERCHANT_ADMIN).", "agentGuidance": "Locked after too many wrong passwords until 14:30. After verifying the caller you can unlock it now.",
                                            "data": { "accounts": [ """ + ACCOUNT_JSON + """
                                            ] } },
                                          "innbucksApp": { "status": "OK", "matchedBy": "phone", "summary": "1 phone signed in.", "agentGuidance": "Nothing is stopping this customer's phones. If the app still refuses them, check the recent events.",
                                            "data": { "phones": [ { "msisdn": "+263771234567",
                                              "customerProfile": { "registrationTier": 2, "verified": false, "phoneVerified": true, "phoneVerifiedAt": "2026-07-02T08:15:52+02:00" },
                                              "deviceSecurity": {
                                                "profile": { "preferredChannel": "WHATSAPP", "fraudFlagged": false, "fraudFlaggedAt": null, "fraudFlagNote": null, "pinIssuedAt": "2026-07-02T08:15:30+02:00", "lastSignInAt": "2026-09-29T11:58:14+02:00" },
                                                "devices": [ { "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "msisdn": "+263771234567", "label": "Samsung SM-A155F · Android 15", "platform": "android", "osVersion": "15", "model": "SM-A155F", "manufacturer": "samsung", "appVersion": "2.4.0", "state": "TRUSTED", "stateReason": null, "blockedAt": null, "blockedUntil": null, "supportRef": null, "ussdUnlockable": false, "unlockableAfter": null, "trustedUntil": "2026-12-28T08:16:40+02:00", "boundAt": "2026-07-02T08:16:40+02:00", "coolingUntil": null, "otpVerifiedAt": "2026-07-02T08:15:52+02:00", "firstSeenAt": "2026-07-02T08:14:03+02:00", "lastSeenAt": "2026-09-29T11:58:14+02:00", "lastSeenNear": "Harare", "lastIp": "41.221.x.x", "lastUnlockedAt": null, "deviceWideBan": null, "otherAccountsOnDevice": 0, "agentGuidance": "Signed in normally; trusted until 08:16 on 28 Dec. Nothing to do." } ],
                                                "counters": { "otpChallengesLastHour": 0, "otpChallengesLastDay": 0, "ussdUnlockAttemptsLastDay": 0, "openChallenges": 0 },
                                                "recentEvents": [ { "id": 48240, "occurredAt": "2026-09-29T11:58:14+02:00", "type": "SIGN_IN_DECISION", "description": "Sign-in check: TOKEN", "decision": "TOKEN", "evaluatedDecision": "TOKEN", "reason": null, "supportRef": null, "actorType": "APP", "actorId": null, "channel": null, "purpose": "SIGN_IN", "context": "SIGN_IN", "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "ussdSessionId": null, "ipAddress": "41.221.x.x", "riskScore": 0, "features": null, "note": null } ],
                                                "summary": "1 phone signed in." } } ] } }
                                        },
                                        "focus": null,
                                        "notShown": [],
                                        "staffAccount": false
                                      }
                                    }
                                    """),
                            @ExampleObject(name = "reference", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Customer found",
                                      "data": {
                                        "lookupId": "SLK-3H8KD2",
                                        "query": { "kind": "DTX_REFERENCE", "normalised": "SEC-8F2KQ7" },
                                        "customer": { "phone": "+263771234567", "email": null, "name": "Tariro Moyo" },
                                        "identityWarnings": [],
                                        "sections": {
                                          "innbucksApp": { "status": "OK", "matchedBy": "reference", "summary": "0 phones signed in; Samsung SM-A155F · Android 15 paused until 16:30 (SEC-8F2KQ7).", "agentGuidance": "Paused until 16:30 and lifts on its own after too many wrong codes. You may unlock it now if the caller passes verification; the next sign-in still asks for a code.",
                                            "data": { "phones": [ { "msisdn": "+263771234567",
                                              "customerProfile": { "registrationTier": 2, "verified": false, "phoneVerified": true, "phoneVerifiedAt": "2026-07-02T08:15:52+02:00" },
                                              "deviceSecurity": {
                                                "profile": { "preferredChannel": "WHATSAPP", "fraudFlagged": false, "fraudFlaggedAt": null, "fraudFlagNote": null, "pinIssuedAt": "2026-07-02T08:15:30+02:00", "lastSignInAt": "2026-09-29T11:58:14+02:00" },
                                                "devices": [ { "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "msisdn": "+263771234567", "label": "Samsung SM-A155F · Android 15", "platform": "android", "osVersion": "15", "model": "SM-A155F", "manufacturer": "samsung", "appVersion": "2.4.0", "state": "TEMP_BLOCKED", "stateReason": "OTP_ATTEMPTS", "blockedAt": "2026-09-30T16:15:00+02:00", "blockedUntil": "2026-09-30T16:30:00+02:00", "supportRef": "SEC-8F2KQ7", "ussdUnlockable": true, "unlockableAfter": null, "trustedUntil": null, "boundAt": "2026-07-02T08:16:40+02:00", "coolingUntil": null, "otpVerifiedAt": "2026-07-02T08:15:52+02:00", "firstSeenAt": "2026-07-02T08:14:03+02:00", "lastSeenAt": "2026-09-30T16:14:51+02:00", "lastSeenNear": "Harare", "lastIp": "41.221.x.x", "lastUnlockedAt": null, "deviceWideBan": null, "otherAccountsOnDevice": 0, "agentGuidance": "Paused until 16:30 and lifts on its own after too many wrong codes. You may unlock it now if the caller passes verification; the next sign-in still asks for a code." } ],
                                                "counters": { "otpChallengesLastHour": 2, "otpChallengesLastDay": 3, "ussdUnlockAttemptsLastDay": 0, "openChallenges": 0 },
                                                "recentEvents": [ { "id": 48213, "occurredAt": "2026-09-30T16:15:00+02:00", "type": "DEVICE_TEMP_BLOCKED", "description": "Phone paused (OTP_ATTEMPTS)", "decision": "TEMP_BLOCKED", "evaluatedDecision": null, "reason": "OTP_ATTEMPTS", "supportRef": "SEC-8F2KQ7", "actorType": "SYSTEM", "actorId": null, "channel": null, "purpose": null, "context": null, "deviceId": "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", "ussdSessionId": null, "ipAddress": null, "riskScore": null, "features": null, "note": null } ],
                                                "summary": "0 phones signed in; Samsung SM-A155F · Android 15 paused until 16:30 (SEC-8F2KQ7)." } } ] } }
                                        },
                                        "focus": { "section": "innbucksApp", "kind": "DTX_REFERENCE", "reference": "SEC-8F2KQ7", "note": "To see this customer's other products, search by their phone or email." },
                                        "notShown": [],
                                        "staffAccount": false
                                      }
                                    }
                                    """)
                    })),
            @ApiResponse(responseCode = "400", description = "A query that can't be searched",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "query_not_accepted", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Card, voucher and collection codes can't be searched. Ask the caller for their phone number or email.", "data": { "errorCode": "query_not_accepted" } }
                                    """),
                            @ExampleObject(name = "query_not_recognised", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Search by the customer's phone number, email address or a support reference such as SEC-8F2KQ7.", "data": { "errorCode": "query_not_recognised" } }
                                    """),
                            @ExampleObject(name = "query_not_supported", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Searching by this kind of reference isn't available yet.", "data": { "errorCode": "query_not_supported" } }
                                    """),
                            @ExampleObject(name = "validation", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "q": "q is required" } }
                                    """)
                    })),
            @ApiResponse(responseCode = "403", description = "Not allowed to search, or not by this kind of reference",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "missing_permission", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "query_not_permitted", value = """
                                    { "code": "403 FORBIDDEN", "message": "You can't search by this kind of reference.", "data": { "errorCode": "query_not_permitted" } }
                                    """)
                    })),
            @ApiResponse(responseCode = "404", description = "Support is switched off on this cell",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = DISABLED_EXAMPLE))),
            @ApiResponse(responseCode = "429", description = "Your lookup limit",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "503", description = "The lookup could not be recorded, so it is not shown",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOG_UNAVAILABLE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<SearchResult>> searchCustomers(@Valid @RequestBody SearchRequest body,
                                                                   Authentication auth, HttpServletRequest request) {
        SupportAgent agent = agents.require(auth);
        SearchResult result = search.search(agent, body.q(), DeviceSecurityRequests.clientIp(request));
        String message = result.sections().values().stream().anyMatch(s -> "OK".equals(s.status()))
                ? "Customer found" : "No customer found";
        return noStore(ApiResult.ok(message, result));
    }

    // ---- console section: detail -------------------------------------------------------------

    @GetMapping("/console-users/{id}")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.SUPPORT_CONSOLE_READ + "')")
    @Operation(summary = "A Foundry console account from your lookup, as it is now",
            description = "`id` is `sections.console.data.accounts[].userId` from YOUR search, at most 30 minutes "
                    + "old; an id outside that search is not found. Counts against your lookup limit.")
    @Parameter(name = "lookupId", in = ParameterIn.QUERY, required = true, example = "SLK-7Q2M9X")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The account",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Console account", "data": """ + ACCOUNT_JSON + """
                            }
                            """))),
            @ApiResponse(responseCode = "400", description = "No lookupId",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOOKUP_REQUIRED_EXAMPLE))),
            @ApiResponse(responseCode = "403", description = "Missing support-console:read",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN_EXAMPLE))),
            @ApiResponse(responseCode = "404", description = "Not in this lookup, or support switched off",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "target_not_found", value = TARGET_NOT_FOUND_EXAMPLE),
                            @ExampleObject(name = "support_disabled", value = DISABLED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "409", description = "The lookup is stale, someone else's, or didn't return the console section",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOOKUP_EXPIRED_EXAMPLE))),
            @ApiResponse(responseCode = "429", description = "Your lookup limit",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "503", description = "The read could not be recorded, so it is not shown",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOG_UNAVAILABLE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<ConsoleAccountView>> consoleUser(@PathVariable Long id,
                                                                     @RequestParam(required = false) String lookupId,
                                                                     Authentication auth, HttpServletRequest request) {
        SupportAgent agent = agents.require(auth);
        return noStore(ApiResult.ok("Console account",
                consoleActions.detail(agent, id, lookupId, DeviceSecurityRequests.clientIp(request))));
    }

    // ---- console section: writes -------------------------------------------------------------

    @PostMapping("/console-users/{id}/unlock")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.SUPPORT_CONSOLE_MANAGE + "')")
    @Operation(summary = "Unlock a Foundry console account after verifying the caller",
            description = "Clears the password lockout and the two-factor lockout. Needs the lookupId of YOUR search "
                    + "(≤ 30 minutes), a note, and an Idempotency-Key header (a new UUID per action; resending the "
                    + "same key returns the first answer with `replayed: true`). Sealed on the audit chain.")
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
            example = "3f6c1a52-7b0e-4d8a-9c21-5e4f7a8b9c0d")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Unlocked (or the stored answer to a repeated key)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Account unlocked", "data": { "outcome": "SUCCESS", "whatHappensNext": "The account can sign in again now. If the caller is still refused, ask them to wait a minute and try once more.", "replayed": false, "account": """ + UNLOCKED_ACCOUNT_JSON + """
                            } }
                            """))),
            @ApiResponse(responseCode = "400", description = "No lookupId, no Idempotency-Key, or no note",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "lookup_required", value = LOOKUP_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_required", value = IDEMPOTENCY_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "validation", value = NOTE_REQUIRED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "403", description = "Not allowed — or not on this account",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "missing_permission", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "support_self_action", value = SELF_EXAMPLE),
                            @ExampleObject(name = "staff_target_requires_supervisor", value = STAFF_TARGET_EXAMPLE),
                            @ExampleObject(name = "console_staff_account", value = CONSOLE_STAFF_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "404", description = "Not in this lookup, or support switched off",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "target_not_found", value = TARGET_NOT_FOUND_EXAMPLE),
                            @ExampleObject(name = "support_disabled", value = DISABLED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "409", description = "Stale lookup, or the key was used for another action",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "lookup_expired", value = LOOKUP_EXPIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_reused", value = IDEMPOTENCY_REUSED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "429", description = "Your lookup limit",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "503", description = "The change could not be sealed, so it was not made",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = AUDIT_UNAVAILABLE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<ActionResult>> unlock(@PathVariable Long id, @Valid @RequestBody WriteRequest body,
                                                          @RequestHeader(value = "Idempotency-Key", required = false)
                                                          String idempotencyKey,
                                                          Authentication auth, HttpServletRequest request) {
        return write(ConsoleSupportActions.Op.UNLOCK, "Account unlocked", id, body, idempotencyKey, auth, request);
    }

    @PostMapping("/console-users/{id}/send-password-reset")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.SUPPORT_CONSOLE_MANAGE + "')")
    @Operation(summary = "Email a password-reset code to the account's own address",
            description = "Starts the ordinary forgot-password flow, to the account's EMAIL only — never a phone, "
                    + "never an address the caller gives you. Same lookupId, note and Idempotency-Key rules as unlock.")
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
            example = "8a1d2e3f-4b5c-4d6e-9f70-81a2b3c4d5e6")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The code was sent",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Reset code sent", "data": { "outcome": "SUCCESS", "whatHappensNext": "We've emailed a password-reset code to tariro@example.com. It works for 5 minutes: ask the caller to choose Forgot password on the Foundry sign-in page, enter this email, then the code.", "replayed": false, "account": """ + UNLOCKED_ACCOUNT_JSON + """
                            } }
                            """))),
            @ApiResponse(responseCode = "400", description = "No lookupId, no Idempotency-Key, or no note",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "lookup_required", value = LOOKUP_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_required", value = IDEMPOTENCY_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "validation", value = NOTE_REQUIRED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "403", description = "Not allowed — or not on this account",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "missing_permission", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "support_self_action", value = SELF_EXAMPLE),
                            @ExampleObject(name = "staff_target_requires_supervisor", value = STAFF_TARGET_EXAMPLE),
                            @ExampleObject(name = "console_staff_account", value = CONSOLE_STAFF_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "404", description = "Not in this lookup, or support switched off",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "target_not_found", value = TARGET_NOT_FOUND_EXAMPLE),
                            @ExampleObject(name = "support_disabled", value = DISABLED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "409", description = "The account can't receive a code, the lookup is stale, or the key was reused",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "account_inactive", value = """
                                    { "code": "409 CONFLICT", "message": "This account isn't active, so a reset code can't be sent. An administrator decides whether to reactivate it.", "data": { "errorCode": "account_inactive" } }
                                    """),
                            @ExampleObject(name = "no_email_on_account", value = """
                                    { "code": "409 CONFLICT", "message": "This account has no email address, so a reset code can't be sent.", "data": { "errorCode": "no_email_on_account" } }
                                    """),
                            @ExampleObject(name = "lookup_expired", value = LOOKUP_EXPIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_reused", value = IDEMPOTENCY_REUSED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "429", description = "Your lookup limit, or too many codes to this account",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "lookup_rate_limited", value = LIMITED_EXAMPLE),
                            @ExampleObject(name = "reset_code_limited", value = """
                                    { "code": "429 TOO_MANY_REQUESTS", "message": "Too many reset codes have gone to this account recently. Ask the caller to use the latest one, or try again later.", "data": { "errorCode": "reset_code_limited" } }
                                    """)
                    })),
            @ApiResponse(responseCode = "502", description = "The email could not be sent; nothing was changed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "502 BAD_GATEWAY", "message": "We couldn't send the reset email. Try again in a few minutes.", "data": { "errorCode": "reset_delivery_failed" } }
                            """))),
            @ApiResponse(responseCode = "503", description = "The change could not be sealed, so it was not made",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = AUDIT_UNAVAILABLE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<ActionResult>> sendPasswordReset(@PathVariable Long id,
                                                                     @Valid @RequestBody WriteRequest body,
                                                                     @RequestHeader(value = "Idempotency-Key",
                                                                             required = false) String idempotencyKey,
                                                                     Authentication auth, HttpServletRequest request) {
        return write(ConsoleSupportActions.Op.SEND_PASSWORD_RESET, "Reset code sent", id, body, idempotencyKey, auth,
                request);
    }

    @PostMapping("/console-users/{id}/mfa/reset")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.SUPPORT_CONSOLE_MFA_RESET + "')")
    @Operation(summary = "Reset a Foundry console account's two-factor sign-in (supervisor)",
            description = "The help-desk takeover path (stolen password + a convincing call), so: supervisor only, a "
                    + "note is required, every session of the account ends at once, and the account AND every OWNER of "
                    + "its businesses are emailed. Staff and SUPER_ADMIN accounts are refused. Same lookupId and "
                    + "Idempotency-Key rules as unlock.")
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
            example = "c2b7e4a9-1d3f-4e5a-8b6c-7d8e9f0a1b2c")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "2FA reset",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Two-factor sign-in reset", "data": { "outcome": "SUCCESS", "whatHappensNext": "Two-factor sign-in is off for this account and every session it had open has ended. At their next sign-in they'll be asked to set it up again. We've emailed the account and the owners of its businesses about this change.", "replayed": false, "account": """ + UNLOCKED_ACCOUNT_JSON + """
                            } }
                            """))),
            @ApiResponse(responseCode = "400", description = "No lookupId, no Idempotency-Key, or no note",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "lookup_required", value = LOOKUP_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_required", value = IDEMPOTENCY_REQUIRED_EXAMPLE),
                            @ExampleObject(name = "validation", value = NOTE_REQUIRED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "403", description = "Not a supervisor — or not on this account",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "missing_permission", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "support_self_action", value = SELF_EXAMPLE),
                            @ExampleObject(name = "staff_target_requires_supervisor", value = STAFF_TARGET_EXAMPLE),
                            @ExampleObject(name = "console_staff_account", value = CONSOLE_STAFF_EXAMPLE),
                            @ExampleObject(name = "target_not_manageable", value = """
                                    { "code": "403 FORBIDDEN", "message": "You can't change this account.", "data": { "errorCode": "target_not_manageable", "reason": "exceeds_your_authority" } }
                                    """)
                    })),
            @ApiResponse(responseCode = "404", description = "Not in this lookup, or support switched off",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "target_not_found", value = TARGET_NOT_FOUND_EXAMPLE),
                            @ExampleObject(name = "support_disabled", value = DISABLED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "409", description = "Nothing to reset, stale lookup, or the key was reused",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "mfa_not_enrolled", value = """
                                    { "code": "409 CONFLICT", "message": "This account hasn't set up two-factor sign-in, so there is nothing to reset.", "data": { "errorCode": "mfa_not_enrolled" } }
                                    """),
                            @ExampleObject(name = "lookup_expired", value = LOOKUP_EXPIRED_EXAMPLE),
                            @ExampleObject(name = "idempotency_key_reused", value = IDEMPOTENCY_REUSED_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "429", description = "Your lookup limit",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "503", description = "The change could not be sealed, so it was not made",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = AUDIT_UNAVAILABLE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<ActionResult>> resetMfa(@PathVariable Long id, @Valid @RequestBody WriteRequest body,
                                                            @RequestHeader(value = "Idempotency-Key", required = false)
                                                            String idempotencyKey,
                                                            Authentication auth, HttpServletRequest request) {
        return write(ConsoleSupportActions.Op.MFA_RESET, "Two-factor sign-in reset", id, body, idempotencyKey, auth,
                request);
    }

    private ResponseEntity<ApiResult<ActionResult>> write(ConsoleSupportActions.Op op, String message, Long id,
                                                          WriteRequest body, String idempotencyKey,
                                                          Authentication auth, HttpServletRequest request) {
        SupportAgent agent = agents.require(auth);
        AuditContext ctx = new AuditContext(DeviceSecurityRequests.clientIp(request), request.getHeader("User-Agent"));
        return noStore(ApiResult.ok(message, consoleActions.execute(op, agent, id, body, idempotencyKey, ctx)));
    }

    private static <T> ResponseEntity<ApiResult<T>> noStore(ApiResult<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
