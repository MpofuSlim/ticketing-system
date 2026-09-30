package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.StaffDTOs;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.StaffAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff accounts (V44) — InnBucks employees who administer the platform, never
 * business accounts. Created here and ONLY here: an InnBucks address, staff
 * roles, no organization, no sign-in phone, and no password until the person
 * redeems the emailed invite ({@code /auth/staff-invite/accept}).
 *
 * <p>All four permissions ({@code staff:read}, {@code staff:create},
 * {@code staff:manage}) are reserved to the wildcard: SUPER_ADMIN only, until
 * the owner decides otherwise. Routed by the gateway's {@code user-admin-route}.
 */
@RestController
@RequestMapping("/admin/staff")
@RequiredArgsConstructor
@Validated
@Tag(name = "Admin — Staff accounts",
        description = "Create InnBucks staff accounts by invite, adopt legacy ones, deactivate and reactivate them.")
@SecurityRequirement(name = "bearerAuth")
public class AdminStaffController {

    private final StaffAccountService staffAccountService;

    // ---------------------------------------------------------------------
    // Shared example bodies — one record (id 4812) across every endpoint.
    // ---------------------------------------------------------------------

    static final String INVITED_VIEW = """
            {
                "id": 4812,
                "userUuid": "8f3c2b1e-6a4d-4e0f-9b7a-2d5c1e9f0a11",
                "firstName": "Tariro",
                "lastName": "Moyo",
                "email": "tariro.moyo@innbucks.co.zw",
                "phoneNumber": "+263771234567",
                "country": "Zimbabwe",
                "roles": ["CALL_CENTER_AGENT"],
                "permissions": ["device-security:manage", "device-security:read"],
                "status": "INVITED",
                "emailVerified": false,
                "emailDomainAllowed": true,
                "mfaEnrolled": false,
                "lockedOut": false,
                "lastSignInAt": null,
                "createdAt": "2026-09-29T10:15:02+02:00",
                "createdBy": { "email": "admin@innbucks.co.zw" },
                "manageable": true,
                "invite": {
                  "sentTo": "tariro.moyo@innbucks.co.zw",
                  "expiresAt": "2026-10-02T10:15:02+02:00",
                  "deliveryStatus": "SENT"
                }
              }""";

    private static final String FORBIDDEN_PERMISSION = """
            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
            """;
    private static final String TARGET_SUPER_ADMIN = """
            {
              "code": "403 FORBIDDEN",
              "message": "You can't change this account.",
              "data": { "errorCode": "target_not_manageable", "reason": "super_admin" }
            }
            """;
    private static final String TARGET_EXCEEDS = """
            {
              "code": "403 FORBIDDEN",
              "message": "You can't change this account.",
              "data": { "errorCode": "target_not_manageable", "reason": "exceeds_your_authority" }
            }
            """;
    private static final String NOT_FOUND = """
            { "code": "404 NOT_FOUND", "message": "Staff account not found.", "data": { "errorCode": "staff_not_found" } }
            """;
    private static final String AUDIT_UNAVAILABLE = """
            {
              "code": "503 SERVICE_UNAVAILABLE",
              "message": "We couldn't record this change, so it wasn't made. Try again.",
              "data": { "errorCode": "audit_unavailable" }
            }
            """;
    private static final String DOMAIN_NOT_ALLOWED = """
            {
              "code": "400 BAD_REQUEST",
              "message": "Staff accounts must use an InnBucks email address ending in @innbucks.co.zw or @innbucks.co.ke.",
              "data": {
                "errorCode": "email_domain_not_allowed",
                "field": "email",
                "allowedDomains": ["innbucks.co.zw", "innbucks.co.ke"]
              }
            }
            """;
    private static final String DOMAINS_UNCONFIGURED = """
            {
              "code": "503 SERVICE_UNAVAILABLE",
              "message": "Staff accounts aren't set up on this server yet.",
              "data": { "errorCode": "staff_domains_unconfigured" }
            }
            """;
    private static final String INVITES_UNCONFIGURED = """
            {
              "code": "503 SERVICE_UNAVAILABLE",
              "message": "Staff accounts aren't set up on this server yet.",
              "data": { "errorCode": "staff_invites_unconfigured" }
            }
            """;
    private static final String NOTE_MISSING = """
            { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "note": "note is required" } }
            """;

    // ---------------------------------------------------------------------
    // POST /admin/staff
    // ---------------------------------------------------------------------

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionCatalog.STAFF_CREATE + "')")
    @Operation(summary = "Create a staff account and email an invite",
            description = """
                    Creates an InnBucks staff account and emails the person a single-use link to set a
                    password. **There is no password field**, and the response never contains a password,
                    the invite token, its hash or the link.

                    * `email` must be the person's OWN address on a staff domain (this cell:
                      `STAFF_ALLOWED_EMAIL_DOMAINS`), with no `+tag` and not a shared mailbox such as
                      `support@`. It is stored lower-cased.
                    * `phoneNumber` is optional and is a **contact number only**, any country. Staff sign
                      in with their email and cannot reset a password by phone; the account has no sign-in
                      phone at all.
                    * `roles` must all be staff roles (`PRODUCT_*`, `CALL_CENTER_*`, `FRAUD_DESK`, or a custom
                      role holding a platform permission), each within your own authority — never
                      `SUPER_ADMIN`. Every refused role is named in one 400.
                    * The account is `INVITED` until the invite is redeemed: it cannot sign in, and
                      forgot-password does nothing for it. The link works once and expires after
                      `STAFF_INVITE_TTL` (72 hours). At first sign-in the person enrols two-step verification.
                    * At most `STAFF_CREATE_DAILY_LIMIT` (20) accounts per administrator per rolling 24 hours.

                    `data.invite.deliveryStatus` is `PENDING` in this response (the email goes after the
                    change commits); read it back with `GET /admin/staff/{id}`. Requires `staff:create`
                    (SUPER_ADMIN only).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; the invite is being emailed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Invited",
                            value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Staff account created. We're emailing an invite to tariro.moyo@innbucks.co.zw.",
                                      "data": {
                                        "id": 4812,
                                        "userUuid": "8f3c2b1e-6a4d-4e0f-9b7a-2d5c1e9f0a11",
                                        "firstName": "Tariro",
                                        "lastName": "Moyo",
                                        "email": "tariro.moyo@innbucks.co.zw",
                                        "phoneNumber": "+263771234567",
                                        "country": "Zimbabwe",
                                        "roles": ["CALL_CENTER_AGENT"],
                                        "permissions": ["device-security:manage", "device-security:read"],
                                        "status": "INVITED",
                                        "emailVerified": false,
                                        "emailDomainAllowed": true,
                                        "mfaEnrolled": false,
                                        "lockedOut": false,
                                        "lastSignInAt": null,
                                        "createdAt": "2026-09-29T10:15:02+02:00",
                                        "createdBy": { "email": "admin@innbucks.co.zw" },
                                        "manageable": true,
                                        "invite": {
                                          "sentTo": "tariro.moyo@innbucks.co.zw",
                                          "expiresAt": "2026-10-02T10:15:02+02:00",
                                          "deliveryStatus": "PENDING"
                                        },
                                        "whatHappensNext": "We're emailing Tariro a link to set a password. It works once and expires at 10.15 on 2 Oct. At first sign-in they will set up two-step verification."
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Validation, the address, a role, or the contact number",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Validation failed", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Validation failed",
                                      "data": { "note": "note is required", "roles": "At least one role is required" }
                                    }
                                    """),
                            @ExampleObject(name = "Not a staff domain", value = DOMAIN_NOT_ALLOWED),
                            @ExampleObject(name = "Plus-tag or shared mailbox", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Use the person's own InnBucks address, without a +tag, special characters or a shared mailbox name.",
                                      "data": { "errorCode": "email_not_accepted", "field": "email", "reason": "shared_mailbox" }
                                    }
                                    """),
                            @ExampleObject(name = "Roles that can't be given", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "These roles can't be given to this account: MERCHANT_ADMIN (not a staff role), SUPER_ADMIN (reserved).",
                                      "data": {
                                        "errorCode": "role_not_assignable",
                                        "roles": { "MERCHANT_ADMIN": "not_a_staff_role", "SUPER_ADMIN": "reserved" }
                                      }
                                    }
                                    """),
                            @ExampleObject(name = "Bad contact number", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "That isn't a valid mobile number.",
                                      "data": { "errorCode": "invalid_request", "field": "phoneNumber" }
                                    }
                                    """)
                    })),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Caller lacks staff:create",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Missing permission", value = FORBIDDEN_PERMISSION))),
            @ApiResponse(responseCode = "409", description = "An account with this email exists (including a lost insert race)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Email taken",
                            value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "An account with this email already exists.",
                                      "data": { "errorCode": "email_taken", "field": "email" }
                                    }
                                    """))),
            @ApiResponse(responseCode = "429", description = "Create quota reached; `Retry-After` header set",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Quota",
                            value = """
                                    {
                                      "code": "429 TOO_MANY_REQUESTS",
                                      "message": "Too many staff accounts created: 20 a day is the limit. Try again in 3 hours.",
                                      "data": { "errorCode": "staff_create_limited", "retryAfterSeconds": 10440 }
                                    }
                                    """))),
            @ApiResponse(responseCode = "503", description = "Not provisioned on this cell, or the audit row could not be written",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "No staff domain configured", value = DOMAINS_UNCONFIGURED),
                            @ExampleObject(name = "Invites can't be sent from this cell", value = INVITES_UNCONFIGURED),
                            @ExampleObject(name = "Audit unavailable", value = AUDIT_UNAVAILABLE)
                    }))
    })
    public ResponseEntity<ApiResult<StaffDTOs.StaffView>> create(@Valid @RequestBody StaffDTOs.CreateRequest request,
                                                                 Authentication authentication,
                                                                 HttpServletRequest httpRequest) {
        StaffAccountService.Outcome outcome = staffAccountService.create(request, authentication.getName(),
                context(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created(outcome.message(), outcome.view()));
    }

    // ---------------------------------------------------------------------
    // GET /admin/staff, /admin/staff/{id}
    // ---------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionCatalog.STAFF_READ + "')")
    @Operation(summary = "List staff accounts",
            description = """
                    Every account holding a staff role, or with a staff profile. SUPER_ADMIN rows appear
                    (with `manageable: false`) only for a caller who is SUPER_ADMIN.

                    * `role`: exact role name. `status`: `INVITED`, `ACTIVE` or `DEACTIVATED`.
                      `q`: at least 2 characters, a case-insensitive match anywhere in the email or name.
                    * Sorted by last name then id; `page` is 0-based, `size` at most 100.
                    * `phoneNumber` is the contact number. `invite` is present only while INVITED.
                    * A **legacy** row (a staff role but no staff profile — created before staff accounts
                      existed) also carries `adoptable` and `adoptionBlockedReason`
                      (`holds_non_staff_roles`, `organization_member`, `off_domain`): adopt it with
                      `POST /admin/staff/{id}/resend-invite`.

                    Requires `staff:read` (SUPER_ADMIN only).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Staff accounts retrieved",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "One invited, one legacy",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Staff accounts retrieved",
                                      "data": {
                                        "content": [
                                          {
                                            "id": 1377,
                                            "userUuid": "2b9d4c1a-7e3f-4a6b-8d2c-5f1e0a9b3c47",
                                            "firstName": "Farai",
                                            "lastName": "Chikwanha",
                                            "email": "farai.chikwanha@innbucks.co.zw",
                                            "phoneNumber": "+263772000111",
                                            "country": "Zimbabwe",
                                            "roles": ["PRODUCT_OFFICER"],
                                            "permissions": ["users:merchants:read"],
                                            "status": "ACTIVE",
                                            "emailVerified": false,
                                            "emailDomainAllowed": true,
                                            "mfaEnrolled": true,
                                            "lockedOut": false,
                                            "lastSignInAt": "2026-09-28T16:02:44+02:00",
                                            "createdAt": "2026-03-11T09:00:00+02:00",
                                            "manageable": true,
                                            "adoptable": true
                                          },
                                          {
                                            "id": 4812,
                                            "userUuid": "8f3c2b1e-6a4d-4e0f-9b7a-2d5c1e9f0a11",
                                            "firstName": "Tariro",
                                            "lastName": "Moyo",
                                            "email": "tariro.moyo@innbucks.co.zw",
                                            "phoneNumber": "+263771234567",
                                            "country": "Zimbabwe",
                                            "roles": ["CALL_CENTER_AGENT"],
                                            "permissions": ["device-security:manage", "device-security:read"],
                                            "status": "INVITED",
                                            "emailVerified": false,
                                            "emailDomainAllowed": true,
                                            "mfaEnrolled": false,
                                            "lockedOut": false,
                                            "lastSignInAt": null,
                                            "createdAt": "2026-09-29T10:15:02+02:00",
                                            "createdBy": { "email": "admin@innbucks.co.zw" },
                                            "manageable": true,
                                            "invite": {
                                              "sentTo": "tariro.moyo@innbucks.co.zw",
                                              "expiresAt": "2026-10-02T10:15:02+02:00",
                                              "deliveryStatus": "SENT"
                                            }
                                          }
                                        ],
                                        "totalElements": 2,
                                        "totalPages": 1,
                                        "number": 0,
                                        "size": 20
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "A filter is malformed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Bad status",
                            value = """
                                    { "code": "400 BAD_REQUEST", "message": "We couldn't process your request. Please try again.", "data": null }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Caller lacks staff:read",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Missing permission", value = FORBIDDEN_PERMISSION)))
    })
    public ResponseEntity<ApiResult<StaffDTOs.Page<StaffDTOs.StaffView>>> list(
            @Parameter(description = "Exact role name") @RequestParam(required = false) @Size(max = 64) String role,
            @Parameter(description = "INVITED, ACTIVE or DEACTIVATED")
            @RequestParam(required = false) @Pattern(regexp = "(?i)INVITED|ACTIVE|DEACTIVATED") String status,
            @Parameter(description = "At least 2 characters; email or name, case-insensitive")
            @RequestParam(required = false) @Size(min = 2, max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Staff accounts retrieved",
                staffAccountService.list(role, status, q, page, size, authentication.getName())));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.STAFF_READ + "')")
    @Operation(summary = "One staff account",
            description = "The same view as the list. 404 `staff_not_found` when the id is not a staff account "
                    + "(and, for a caller who is not SUPER_ADMIN, when it is the SUPER_ADMIN). Requires `staff:read`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Staff account retrieved",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Invited",
                            value = "{\n  \"code\": \"200 OK\",\n  \"message\": \"Staff account retrieved\",\n  \"data\": "
                                    + INVITED_VIEW + "\n}"))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Caller lacks staff:read",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Missing permission", value = FORBIDDEN_PERMISSION))),
            @ApiResponse(responseCode = "404", description = "Not a staff account",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not found", value = NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<StaffDTOs.StaffView>> get(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Staff account retrieved",
                staffAccountService.get(id, authentication.getName())));
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.STAFF_MANAGE + "')")
    @Operation(summary = "Deactivate a staff account",
            description = """
                    Switches the account off and signs it out everywhere, in one transaction: `active=false`
                    and the session epoch bumped atomically, every refresh token revoked, remembered devices
                    cleared, live password-reset codes and live invites revoked. Other services refuse the old
                    access token once the new epoch is published to the shared Redis after commit. The person
                    is emailed a deactivation notice.

                    Refused for a SUPER_ADMIN, for an account holding anything you do not (403
                    `target_not_manageable`), for yourself (400) and for an account already off (409).
                    `note` is required and recorded. Requires `staff:manage` (SUPER_ADMIN only).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deactivated and signed out",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Deactivated",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Staff account deactivated",
                                      "data": {
                                        "id": 4812,
                                        "userUuid": "8f3c2b1e-6a4d-4e0f-9b7a-2d5c1e9f0a11",
                                        "firstName": "Tariro",
                                        "lastName": "Moyo",
                                        "email": "tariro.moyo@innbucks.co.zw",
                                        "phoneNumber": "+263771234567",
                                        "country": "Zimbabwe",
                                        "roles": ["CALL_CENTER_AGENT"],
                                        "permissions": ["device-security:manage", "device-security:read"],
                                        "status": "DEACTIVATED",
                                        "emailVerified": true,
                                        "emailDomainAllowed": true,
                                        "mfaEnrolled": true,
                                        "lockedOut": false,
                                        "lastSignInAt": "2026-09-30T08:41:10+02:00",
                                        "createdAt": "2026-09-29T10:15:02+02:00",
                                        "createdBy": { "email": "admin@innbucks.co.zw" },
                                        "manageable": true,
                                        "whatHappensNext": "Tariro was signed out everywhere and can't sign in until reactivated."
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Missing note, or yourself",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Note missing", value = NOTE_MISSING),
                            @ExampleObject(name = "Yourself", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "You can't deactivate your own account.",
                                      "data": { "errorCode": "cannot_deactivate_self" }
                                    }
                                    """)
                    })),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Missing permission, a SUPER_ADMIN, or more authority than yours",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Target is SUPER_ADMIN", value = TARGET_SUPER_ADMIN),
                            @ExampleObject(name = "Target holds more than you", value = TARGET_EXCEEDS),
                            @ExampleObject(name = "Missing permission", value = FORBIDDEN_PERMISSION)
                    })),
            @ApiResponse(responseCode = "404", description = "Not a staff account",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not found", value = NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Already deactivated",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Already off",
                            value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "This staff account is already deactivated. Reactivate it instead.",
                                      "data": { "errorCode": "already_deactivated" }
                                    }
                                    """))),
            @ApiResponse(responseCode = "503", description = "The audit row could not be written; nothing changed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Audit unavailable", value = AUDIT_UNAVAILABLE)))
    })
    public ResponseEntity<ApiResult<StaffDTOs.StaffView>> deactivate(@PathVariable Long id,
                                                                     @Valid @RequestBody StaffDTOs.NoteRequest request,
                                                                     Authentication authentication,
                                                                     HttpServletRequest httpRequest) {
        StaffAccountService.Outcome outcome = staffAccountService.deactivate(id, request.note(),
                authentication.getName(), context(httpRequest));
        return ResponseEntity.ok(ApiResult.ok(outcome.message(), outcome.view()));
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.STAFF_MANAGE + "')")
    @Operation(summary = "Reactivate a staff account (as INVITED)",
            description = """
                    Switches a deactivated account back on WITHOUT restoring its old credentials — a
                    deactivation is often a response to a compromise. The password is made unusable,
                    two-step verification and its backup codes and remembered devices are cleared, the email
                    counts as unconfirmed again, earlier invites are revoked and every session ends. The
                    account is **INVITED**: send it a new invite with
                    `POST /admin/staff/{id}/resend-invite` (not sent automatically).

                    The address is re-checked against the staff domains. A legacy account (no staff
                    profile) must be adoptable, otherwise 409 `adoption_blocked` — clean it up first.
                    `note` is required. Requires `staff:manage` (SUPER_ADMIN only).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reactivated; now INVITED",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Reactivated",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Staff account reactivated",
                                      "data": {
                                        "id": 4812,
                                        "userUuid": "8f3c2b1e-6a4d-4e0f-9b7a-2d5c1e9f0a11",
                                        "firstName": "Tariro",
                                        "lastName": "Moyo",
                                        "email": "tariro.moyo@innbucks.co.zw",
                                        "phoneNumber": "+263771234567",
                                        "country": "Zimbabwe",
                                        "roles": ["CALL_CENTER_AGENT"],
                                        "permissions": ["device-security:manage", "device-security:read"],
                                        "status": "INVITED",
                                        "emailVerified": false,
                                        "emailDomainAllowed": true,
                                        "mfaEnrolled": false,
                                        "lockedOut": false,
                                        "lastSignInAt": "2026-09-30T08:41:10+02:00",
                                        "createdAt": "2026-09-29T10:15:02+02:00",
                                        "createdBy": { "email": "admin@innbucks.co.zw" },
                                        "manageable": true,
                                        "whatHappensNext": "Send a new invite so they can set a password."
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Missing note, or the address is no longer on a staff domain",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Note missing", value = NOTE_MISSING),
                            @ExampleObject(name = "Not a staff domain", value = DOMAIN_NOT_ALLOWED)
                    })),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Missing permission, a SUPER_ADMIN, or more authority than yours",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Target is SUPER_ADMIN", value = TARGET_SUPER_ADMIN),
                            @ExampleObject(name = "Target holds more than you", value = TARGET_EXCEEDS),
                            @ExampleObject(name = "Missing permission", value = FORBIDDEN_PERMISSION)
                    })),
            @ApiResponse(responseCode = "404", description = "Not a staff account",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not found", value = NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Not deactivated, or a legacy account that can't be adopted",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Not deactivated", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "This staff account is not deactivated, so there is nothing to reactivate.",
                                      "data": { "errorCode": "not_deactivated" }
                                    }
                                    """),
                            @ExampleObject(name = "Legacy account still owns a business", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "This account can't be adopted yet: it still belongs to a business organization. Suspend that organization first.",
                                      "data": { "errorCode": "adoption_blocked", "reason": "organization_member" }
                                    }
                                    """)
                    })),
            @ApiResponse(responseCode = "503", description = "No staff domain on this cell, or the audit row could not be written",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "No staff domain configured", value = DOMAINS_UNCONFIGURED),
                            @ExampleObject(name = "Audit unavailable", value = AUDIT_UNAVAILABLE)
                    }))
    })
    public ResponseEntity<ApiResult<StaffDTOs.StaffView>> reactivate(@PathVariable Long id,
                                                                     @Valid @RequestBody StaffDTOs.NoteRequest request,
                                                                     Authentication authentication,
                                                                     HttpServletRequest httpRequest) {
        StaffAccountService.Outcome outcome = staffAccountService.reactivate(id, request.note(),
                authentication.getName(), context(httpRequest));
        return ResponseEntity.ok(ApiResult.ok(outcome.message(), outcome.view()));
    }

    @PostMapping("/{id}/resend-invite")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.STAFF_CREATE + "')")
    @Operation(summary = "Send a new invite — or adopt a legacy staff account",
            description = """
                    Two cases:

                    * **An INVITED account**: earlier links stop working and a new one is emailed.
                    * **A legacy staff account** (holds staff roles but has no staff profile — created before
                      staff accounts existed): it is **adopted**. It becomes INVITED at once, so it cannot sign
                      in until the new invite is redeemed; every session it holds ends now and its password,
                      two-step verification and trusted devices are cleared (whoever held it is locked out); the
                      invite is emailed. On redemption its sign-in phone becomes its contact number and it enrols
                      two-step verification afresh.

                    A legacy account must hold ONLY staff roles, be on a staff domain and belong to no active
                    business organization — otherwise 409 `adoption_blocked` with `reason`:
                    `holds_non_staff_roles` (remove them with `PUT /admin/users/{id}/roles`),
                    `organization_member` (suspend the organization with
                    `POST /admin/organizations/{id}/suspend`) or `off_domain` (remove its staff roles and invite
                    the person at their InnBucks address). An account that has already set its password, or is
                    deactivated, is 409 `invite_not_pending`.

                    At most `STAFF_INVITE_RESEND_LIMIT` (5) invites per account per rolling 24 hours. The body
                    is optional (`{"note": "..."}`). Requires `staff:create` (SUPER_ADMIN only).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invite sent (and the account adopted, for a legacy one)",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "New invite for an INVITED account", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Invite sent to tariro.moyo@innbucks.co.zw.",
                                      "data": {
                                        "id": 4812,
                                        "userUuid": "8f3c2b1e-6a4d-4e0f-9b7a-2d5c1e9f0a11",
                                        "firstName": "Tariro",
                                        "lastName": "Moyo",
                                        "email": "tariro.moyo@innbucks.co.zw",
                                        "phoneNumber": "+263771234567",
                                        "country": "Zimbabwe",
                                        "roles": ["CALL_CENTER_AGENT"],
                                        "permissions": ["device-security:manage", "device-security:read"],
                                        "status": "INVITED",
                                        "emailVerified": false,
                                        "emailDomainAllowed": true,
                                        "mfaEnrolled": false,
                                        "lockedOut": false,
                                        "lastSignInAt": null,
                                        "createdAt": "2026-09-29T10:15:02+02:00",
                                        "createdBy": { "email": "admin@innbucks.co.zw" },
                                        "manageable": true,
                                        "invite": {
                                          "sentTo": "tariro.moyo@innbucks.co.zw",
                                          "expiresAt": "2026-10-03T09:02:40+02:00",
                                          "deliveryStatus": "PENDING"
                                        },
                                        "whatHappensNext": "We're emailing Tariro a new link to set a password. It works once and expires at 09.02 on 3 Oct. Earlier links no longer work."
                                      }
                                    }
                                    """),
                            @ExampleObject(name = "Legacy account adopted", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Invite sent to farai.chikwanha@innbucks.co.zw.",
                                      "data": {
                                        "id": 1377,
                                        "userUuid": "2b9d4c1a-7e3f-4a6b-8d2c-5f1e0a9b3c47",
                                        "firstName": "Farai",
                                        "lastName": "Chikwanha",
                                        "email": "farai.chikwanha@innbucks.co.zw",
                                        "phoneNumber": "+263772000111",
                                        "country": "Zimbabwe",
                                        "roles": ["PRODUCT_OFFICER"],
                                        "permissions": ["users:merchants:read"],
                                        "status": "INVITED",
                                        "emailVerified": false,
                                        "emailDomainAllowed": true,
                                        "mfaEnrolled": false,
                                        "lockedOut": false,
                                        "lastSignInAt": "2026-09-28T16:02:44+02:00",
                                        "createdAt": "2026-03-11T09:00:00+02:00",
                                        "manageable": true,
                                        "invite": {
                                          "sentTo": "farai.chikwanha@innbucks.co.zw",
                                          "expiresAt": "2026-10-03T09:02:40+02:00",
                                          "deliveryStatus": "PENDING"
                                        },
                                        "whatHappensNext": "Farai's existing sessions were ended and the old password no longer works. We're emailing a link to set a new password; they can't sign in until they use it. It works once and expires at 09.02 on 3 Oct."
                                      }
                                    }
                                    """)
                    })),
            @ApiResponse(responseCode = "400", description = "The address is no longer on a staff domain",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not a staff domain", value = DOMAIN_NOT_ALLOWED))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Missing permission, a SUPER_ADMIN, or more authority than yours",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Target is SUPER_ADMIN", value = TARGET_SUPER_ADMIN),
                            @ExampleObject(name = "Target holds more than you", value = TARGET_EXCEEDS),
                            @ExampleObject(name = "Missing permission", value = FORBIDDEN_PERMISSION)
                    })),
            @ApiResponse(responseCode = "404", description = "Not a staff account",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not found", value = NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "No invite can be sent to this account yet",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Already set a password", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "This account has no pending invite: it has already set its password.",
                                      "data": { "errorCode": "invite_not_pending" }
                                    }
                                    """),
                            @ExampleObject(name = "Deactivated", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "This account is deactivated. Reactivate the account first, then send a new invite.",
                                      "data": { "errorCode": "invite_not_pending" }
                                    }
                                    """),
                            @ExampleObject(name = "Legacy account holds business roles", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "This account can't be adopted yet: it also holds business roles. Remove them with PUT /admin/users/{id}/roles first.",
                                      "data": { "errorCode": "adoption_blocked", "reason": "holds_non_staff_roles" }
                                    }
                                    """),
                            @ExampleObject(name = "Legacy account still owns a business", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "This account can't be adopted yet: it still belongs to a business organization. Suspend that organization first.",
                                      "data": { "errorCode": "adoption_blocked", "reason": "organization_member" }
                                    }
                                    """),
                            @ExampleObject(name = "Legacy account off the staff domains", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "This account can't be adopted: its email is not an InnBucks staff address. Remove its staff roles and invite the person at their InnBucks address.",
                                      "data": { "errorCode": "adoption_blocked", "reason": "off_domain" }
                                    }
                                    """)
                    })),
            @ApiResponse(responseCode = "429", description = "Resend quota reached; `Retry-After` header set",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Quota",
                            value = """
                                    {
                                      "code": "429 TOO_MANY_REQUESTS",
                                      "message": "Too many invites for this account: 5 a day is the limit. Try again in 42 minutes.",
                                      "data": { "errorCode": "invite_resend_limited", "retryAfterSeconds": 2520 }
                                    }
                                    """))),
            @ApiResponse(responseCode = "503", description = "Not provisioned on this cell, or the audit row could not be written",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "No staff domain configured", value = DOMAINS_UNCONFIGURED),
                            @ExampleObject(name = "Invites can't be sent from this cell", value = INVITES_UNCONFIGURED),
                            @ExampleObject(name = "Audit unavailable", value = AUDIT_UNAVAILABLE)
                    }))
    })
    public ResponseEntity<ApiResult<StaffDTOs.StaffView>> resendInvite(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ResendRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        StaffAccountService.Outcome outcome = staffAccountService.resendInvite(id,
                request == null ? null : request.note(), authentication.getName(), context(httpRequest));
        return ResponseEntity.ok(ApiResult.ok(outcome.message(), outcome.view()));
    }

    /** Optional body of resend-invite. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "StaffResendInviteRequest")
    public record ResendRequest(
            @Size(max = 1000, message = "note must not exceed 1000 characters")
            @io.swagger.v3.oas.annotations.media.Schema(example = "Adopting Farai's pre-existing account (OPS-1190).",
                    nullable = true)
            String note) {
    }

    @GetMapping("/{id}/audit")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.STAFF_READ + "')")
    @Operation(summary = "A staff account's audit history",
            description = "Newest first: every STAFF_* event (refusals and invite replays included), role "
                    + "changes, activation changes, temporary passwords, 2FA resets and lockout lifts recorded "
                    + "against this account. Tamper-evidence hashes are never returned. Requires `staff:read`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Audit history retrieved",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Created then accepted",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Audit history retrieved",
                                      "data": {
                                        "content": [
                                          {
                                            "at": "2026-09-29T14:02:31+02:00",
                                            "type": "STAFF_INVITE_ACCEPTED",
                                            "outcome": "SUCCESS",
                                            "actor": "tariro.moyo@innbucks.co.zw",
                                            "summary": "Invite accepted: password set and email confirmed."
                                          },
                                          {
                                            "at": "2026-09-29T10:15:02+02:00",
                                            "type": "STAFF_INVITED",
                                            "outcome": "SUCCESS",
                                            "actor": "admin@innbucks.co.zw",
                                            "summary": "Created with role CALL_CENTER_AGENT and invited by email.",
                                            "note": "Joins the Harare call-center team on 1 Oct (HR-2291)."
                                          }
                                        ],
                                        "totalElements": 2,
                                        "totalPages": 1,
                                        "number": 0,
                                        "size": 20
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Caller lacks staff:read",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Missing permission", value = FORBIDDEN_PERMISSION))),
            @ApiResponse(responseCode = "404", description = "Not a staff account",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not found", value = NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<StaffDTOs.Page<StaffDTOs.AuditEntry>>> audit(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Audit history retrieved",
                staffAccountService.audit(id, page, size, authentication.getName())));
    }

    private static AuditContext context(HttpServletRequest request) {
        return new AuditContext(clientIp(request), request.getHeader("User-Agent"));
    }

    /** Leftmost {@code X-Forwarded-For} entry behind the gateway, else the remote address. */
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
