package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.RejectRegistrationDTO;
import com.innbucks.userservice.dto.RejectedRegistrationDTO;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.RegistrationRejectionService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The decision on a pending registration that is not an approval:
 * {@code PUT /admin/users/{id}/reject}. Approval stays the first activation on
 * {@link AdminUserController} ({@code PUT /admin/users/{id}/active}); both sit
 * behind the same permission, because both are the power to decide a
 * registration. Same base path and Swagger tag as that controller, so the two
 * render side by side.
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin - User Management", description = "SUPER_ADMIN endpoints for managing user accounts.")
@SecurityRequirement(name = "bearerAuth")
public class AdminRegistrationController {

    private final RegistrationRejectionService registrations;

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.USERS_ACTIVATION_WRITE + "')")
    @Operation(
            summary = "Reject a pending registration",
            description = """
                    Rejects a self-registered account that has NOT been approved yet (`active: false` and
                    never approved — approval is the first `PUT /admin/users/{id}/active` with `true`).

                    **Rejecting removes the registration, so the same details can register again.** The
                    account is deleted together with the business its registration created: the business
                    details (including the BPO / tax number), every organization the applicant created and
                    still owns (with its products), any password-reset code issued to its email or phone,
                    and the account's roles and memberships. The same email, phone number and BPO number can
                    then be used in a new `POST /auth/register`. Drop the row from the pending list: the
                    `id` no longer exists, and `PUT /admin/users/{id}/active` on it is a 404.

                    **The applicant is told why.** After the change commits, the `reason` is sent to them
                    verbatim — by email, or WhatsApp when email fails — so write it for them, not as an
                    internal note. A rejection that fails sends nothing.

                    **Recorded** on the tamper-evident audit chain as `USER_REGISTRATION_REJECTED` (actor:
                    the calling administrator; target: the account's `userUuid`), with the reason, the
                    deleted organizations, the business name and the applicant's email and phone MASKED.
                    If that row cannot be written nothing is removed (503 `audit_unavailable`).

                    **Refused, with nothing changed:**
                    * an approved account — 409 `registration_already_decided`; deactivate it instead;
                    * a staff account — 409 `use_staff_endpoints`;
                    * the SUPER_ADMIN — 403;
                    * a registration whose business is in use — 409 `registration_business_in_use`, with
                      `data.reason`: `other_members` (someone else already belongs to the business),
                      `loyalty_merchant` (InnRewards holds a loyalty merchant for it), `team_members` (the
                      account has team members of its own), `customer_account` (the account is also an
                      InnBucks app customer) or `sole_owner_elsewhere` (it is the only owner of another
                      business);
                    * InnRewards could not be asked whether it holds a merchant for the business — 503
                      `registration_check_unavailable`; retry in a minute;
                    * the registration changed while it was being rejected (approved, or its business
                      changed, between the check and the change) — 409 `registration_changed`; refresh.

                    Requires the `users:activation:write` permission — the same as approving.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registration rejected and removed; the applicant is notified",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Registration rejected",
                                      "data": {
                                        "id": 57,
                                        "email": "rumbi@showtime.co.zw",
                                        "reason": "We couldn't verify the BPO number you gave. Please register again with the number on your ZIMRA certificate.",
                                        "rejectedAt": "2026-10-08T14:30:00+02:00"
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400",
                    description = "`reason` missing, blank or over 1000 characters, or no JSON body",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Reason missing or blank", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Validation failed",
                                              "data": { "reason": "reason is required" }
                                            }
                                            """),
                                    @ExampleObject(name = "Reason too long", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Validation failed",
                                              "data": { "reason": "reason must be 1000 characters or fewer" }
                                            }
                                            """),
                                    @ExampleObject(name = "No body", value = """
                                            { "code": "400 BAD_REQUEST", "message": "We couldn't process your request. Please try again.", "data": null }
                                            """)
                            })),
            @ApiResponse(responseCode = "401", description = "Missing, expired or ended bearer token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "401 UNAUTHORIZED", "message": "Invalid token", "data": null }
                                    """))),
            @ApiResponse(responseCode = "403",
                    description = "Caller lacks `users:activation:write`, or the target is the SUPER_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Target is SUPER_ADMIN", value = """
                                            { "code": "403 FORBIDDEN", "message": "The SUPER_ADMIN account cannot be rejected.", "data": null }
                                            """),
                                    @ExampleObject(name = "Missing permission", value = """
                                            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                            """)
                            })),
            @ApiResponse(responseCode = "404", description = "No account with that id — including one already rejected",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "User not found: 999", "data": null }
                                    """))),
            @ApiResponse(responseCode = "409",
                    description = "The registration can't be rejected: already approved, a staff account, its "
                            + "business is in use, or it changed while it was being rejected",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Already approved", value = """
                                            {
                                              "code": "409 CONFLICT",
                                              "message": "This account has already been approved. Deactivate it instead of rejecting it.",
                                              "data": { "errorCode": "registration_already_decided" }
                                            }
                                            """),
                                    @ExampleObject(name = "Staff account", value = """
                                            {
                                              "code": "409 CONFLICT",
                                              "message": "Reactivate staff with POST /admin/staff/{id}/reactivate.",
                                              "data": { "errorCode": "use_staff_endpoints" }
                                            }
                                            """),
                                    @ExampleObject(name = "Business has other members", value = """
                                            {
                                              "code": "409 CONFLICT",
                                              "message": "This business already has other members, so the registration can't be rejected. Approve it, or remove the other members first.",
                                              "data": { "errorCode": "registration_business_in_use", "reason": "other_members" }
                                            }
                                            """),
                                    @ExampleObject(name = "Business has a loyalty merchant", value = """
                                            {
                                              "code": "409 CONFLICT",
                                              "message": "This business already has a loyalty merchant in InnRewards. Remove it there first, or approve the registration.",
                                              "data": { "errorCode": "registration_business_in_use", "reason": "loyalty_merchant" }
                                            }
                                            """),
                                    @ExampleObject(name = "Account has team members", value = """
                                            {
                                              "code": "409 CONFLICT",
                                              "message": "This account already has team members of its own, so the registration can't be rejected.",
                                              "data": { "errorCode": "registration_business_in_use", "reason": "team_members" }
                                            }
                                            """),
                                    @ExampleObject(name = "Account is also an app customer", value = """
                                            {
                                              "code": "409 CONFLICT",
                                              "message": "This account is also an InnBucks app customer, so the registration can't be rejected.",
                                              "data": { "errorCode": "registration_business_in_use", "reason": "customer_account" }
                                            }
                                            """),
                                    @ExampleObject(name = "Only owner of another business", value = """
                                            {
                                              "code": "409 CONFLICT",
                                              "message": "This account is the only owner of another business, so the registration can't be rejected. Make someone else an owner of that business first.",
                                              "data": { "errorCode": "registration_business_in_use", "reason": "sole_owner_elsewhere" }
                                            }
                                            """),
                                    @ExampleObject(name = "Changed while it was being rejected", value = """
                                            {
                                              "code": "409 CONFLICT",
                                              "message": "This registration changed while it was being rejected. Refresh and try again.",
                                              "data": { "errorCode": "registration_changed" }
                                            }
                                            """)
                            })),
            @ApiResponse(responseCode = "503",
                    description = "InnRewards could not be asked about the business, or the audit row could not be "
                            + "written — nothing was changed; retry",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "InnRewards check unavailable", value = """
                                            {
                                              "code": "503 SERVICE_UNAVAILABLE",
                                              "message": "We couldn't confirm this business isn't already set up in InnRewards. Try again in a minute.",
                                              "data": { "errorCode": "registration_check_unavailable" }
                                            }
                                            """),
                                    @ExampleObject(name = "Audit unavailable", value = """
                                            {
                                              "code": "503 SERVICE_UNAVAILABLE",
                                              "message": "We couldn't record this change, so it wasn't made. Try again.",
                                              "data": { "errorCode": "audit_unavailable" }
                                            }
                                            """)
                            }))
    })
    public ResponseEntity<ApiResult<RejectedRegistrationDTO>> reject(
            @PathVariable Long id,
            @Valid @RequestBody RejectRegistrationDTO request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        // @PreAuthorize already required users:activation:write, so the caller is
        // authenticated; their name (the admin's email) is the audit row's actor.
        String adminEmail = authentication.getName();
        AuditContext auditContext = new AuditContext(clientIp(httpRequest), httpRequest.getHeader("User-Agent"));

        RejectedRegistrationDTO rejected = registrations.reject(id, request.getReason(), adminEmail, auditContext);
        log.info("PUT /admin/users/{}/reject by={}", id, adminEmail);
        return ResponseEntity.ok(ApiResult.ok("Registration rejected", rejected));
    }

    /**
     * Best-effort source IP, the same shape as {@code AdminUserController.clientIp}:
     * the leftmost {@code X-Forwarded-For} entry, else the remote address.
     */
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
