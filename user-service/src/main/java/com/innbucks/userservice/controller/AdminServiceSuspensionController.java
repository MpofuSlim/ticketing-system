package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.OrganizationDTOs;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.ServiceSuspensionService;
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
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Suspend and reinstate ONE service of a business (V53), SUPER_ADMIN only.
 * Rides the existing {@code user-admin-route}: no gateway change.
 */
@RestController
@RequestMapping("/admin/organizations")
@RequiredArgsConstructor
@Tag(name = "Admin — Organizations", description = "Platform directory of every organization (platform staff).")
@SecurityRequirement(name = "bearerAuth")
public class AdminServiceSuspensionController {

    public static final String EXAMPLE_SUSPENDED = """
            {
              "code": "200 OK",
              "message": "Service suspended",
              "data": {
                "organizationId": "7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f",
                "name": "Moyo Fresh Foods",
                "product": "loyalty",
                "status": "SUSPENDED",
                "reason": "Loyalty was approved for this business by mistake (OPS-1204).",
                "membersSignedOut": 2,
                "cashiersSignedOut": 6,
                "tillKeysStopped": 1
              }
            }""";

    public static final String EXAMPLE_REINSTATED = """
            {
              "code": "200 OK",
              "message": "Service reinstated",
              "data": {
                "organizationId": "7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f",
                "name": "Moyo Fresh Foods",
                "product": "loyalty",
                "status": "ACTIVE",
                "reason": null,
                "membersSignedOut": 0,
                "cashiersSignedOut": 0,
                "tillKeysStopped": 0
              }
            }""";

    public static final String EXAMPLE_EVENTS_ON_SALE = """
            {"code":"409 CONFLICT","message":"This business has events on sale. Ticketing can be suspended once they have ended.","data":{"errorCode":"events_on_sale","liveEvents":2}}""";

    public static final String EXAMPLE_ALREADY_SUSPENDED = """
            {"code":"409 CONFLICT","message":"This business's Loyalty service is already suspended.","data":{"errorCode":"product_already_suspended"}}""";

    public static final String EXAMPLE_NOT_GRANTED = """
            {"code":"409 CONFLICT","message":"This business doesn't have the Marketplace service.","data":{"errorCode":"product_not_granted"}}""";

    public static final String EXAMPLE_NOT_SUSPENDED = """
            {"code":"409 CONFLICT","message":"This business's Loyalty service is not suspended.","data":{"errorCode":"product_not_suspended"}}""";

    public static final String EXAMPLE_UNKNOWN_PRODUCT = """
            {"code":"400 BAD_REQUEST","message":"Unknown product. Use one of: ticketing, loyalty, marketplace.","data":{"errorCode":"unknown_product"}}""";

    public static final String EXAMPLE_LOYALTY_UNAVAILABLE = """
            {"code":"503 SERVICE_UNAVAILABLE","message":"We couldn't get this business's loyalty merchants, so loyalty wasn't suspended. Try again.","data":{"errorCode":"loyalty_check_unavailable"}}""";

    private final ServiceSuspensionService suspensions;

    @PostMapping("/{id}/products/{product}/suspend")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.ORGANIZATIONS_MANAGE + "')")
    @Operation(summary = "Suspend one service of a business",
            description = """
                    **SUPER_ADMIN** (`organizations:manage`). Suspends `ticketing`, `loyalty` or
                    `marketplace` for one business. Not a delete: a reinstate puts it back.

                    - Every member is signed out, and their next token carries no such product.
                    - **ticketing:** the business's owners lose the organiser role until reinstated, so every
                      event, booking and seat action is refused. **Refused (409 `events_on_sale`) while they
                      have a published event that has not ended**, and 503 `ticketing_check_unavailable`
                      when event-service can't say.
                    - **loyalty:** the business's cashiers (shop staff of its merchants) are signed out and get
                      no shop role, and its till API keys are refused, until reinstated. 503
                      `loyalty_check_unavailable` when InnRewards can't list the merchants.
                    - The business's owners and admins get an in-app notice and an email with the reason.
                    - `reason` is required (up to 500 characters, plain text) and recorded on the audit trail.
                    - Approving a service request never brings a suspended service back
                      (409 `product_suspended` there): reinstate it here.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Suspended", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "Loyalty suspended",
                    value = EXAMPLE_SUSPENDED))),
            @ApiResponse(responseCode = "400", description = "Unknown product, or no reason", content = @Content(
                    mediaType = "application/json", examples = {
                    @ExampleObject(name = "Unknown product", value = EXAMPLE_UNKNOWN_PRODUCT),
                    @ExampleObject(name = "No reason", value = """
                            {"code":"400 BAD_REQUEST","message":"Validation failed","data":{"reason":"reason is required"}}""")})),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Caller lacks organizations:manage"),
            @ApiResponse(responseCode = "404", description = "No such organization", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "Not found", value = """
                            {"code":"404 NOT_FOUND","message":"We couldn't find that organization.","data":{"errorCode":"organization_not_found"}}"""))),
            @ApiResponse(responseCode = "409", description = "Events on sale, already suspended, not granted, or the "
                    + "organization itself suspended", content = @Content(mediaType = "application/json", examples = {
                    @ExampleObject(name = "Events on sale", value = EXAMPLE_EVENTS_ON_SALE),
                    @ExampleObject(name = "Already suspended", value = EXAMPLE_ALREADY_SUSPENDED),
                    @ExampleObject(name = "Not granted", value = EXAMPLE_NOT_GRANTED)})),
            @ApiResponse(responseCode = "503", description = "A check could not be made, or the audit row could not be "
                    + "written; nothing changed", content = @Content(mediaType = "application/json", examples = {
                    @ExampleObject(name = "InnRewards unavailable", value = EXAMPLE_LOYALTY_UNAVAILABLE),
                    @ExampleObject(name = "Audit unavailable", value = """
                            {"code":"503 SERVICE_UNAVAILABLE","message":"We couldn't record this change, so it wasn't made. Try again.","data":{"errorCode":"audit_unavailable"}}""")}))
    })
    public ResponseEntity<ApiResult<OrganizationDTOs.ServiceSuspensionResult>> suspend(
            @PathVariable UUID id,
            @Parameter(description = "ticketing, loyalty or marketplace") @PathVariable String product,
            @Valid @RequestBody OrganizationDTOs.ServiceSuspendRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResult.ok("Service suspended",
                suspensions.suspend(id, product, request.reason(), authentication.getName(), context(httpRequest))));
    }

    @PostMapping("/{id}/products/{product}/reinstate")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.ORGANIZATIONS_MANAGE + "')")
    @Operation(summary = "Reinstate a suspended service of a business",
            description = """
                    **SUPER_ADMIN** (`organizations:manage`). Puts a suspended service back. Nobody is
                    signed out: the product, and the organiser or cashier roles, reach the business's
                    people at their next sign-in or refresh (at most 15 minutes); till keys work again at
                    once. The owners and admins are told. Optional `note`, recorded on the audit trail.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reinstated", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "Loyalty reinstated",
                    value = EXAMPLE_REINSTATED))),
            @ApiResponse(responseCode = "400", description = "Unknown product", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "Unknown product",
                    value = EXAMPLE_UNKNOWN_PRODUCT))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Caller lacks organizations:manage"),
            @ApiResponse(responseCode = "404", description = "No such organization"),
            @ApiResponse(responseCode = "409", description = "Not suspended, or not granted", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "Not suspended",
                    value = EXAMPLE_NOT_SUSPENDED)))
    })
    public ResponseEntity<ApiResult<OrganizationDTOs.ServiceSuspensionResult>> reinstate(
            @PathVariable UUID id,
            @Parameter(description = "ticketing, loyalty or marketplace") @PathVariable String product,
            @Valid @RequestBody(required = false) OrganizationDTOs.ServiceReinstateRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResult.ok("Service reinstated",
                suspensions.reinstate(id, product, request == null ? null : request.note(),
                        authentication.getName(), context(httpRequest))));
    }

    /** The admin's address as nginx saw it ({@code X-Real-IP}, CLAUDE.md), never X-Forwarded-For. */
    private static AuditContext context(HttpServletRequest request) {
        String ip = PosTokenController.clientIp(request);
        return new AuditContext(ip == null ? request.getRemoteAddr() : ip, request.getHeader("User-Agent"));
    }
}
