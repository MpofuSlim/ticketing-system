package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.PosApiKeyDTOs;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.PosApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
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

import java.util.UUID;

/**
 * POS API keys (V51): issue, list and revoke the machine credentials a
 * merchant's point-of-sale backend uses instead of a cashier login. Issuing and
 * revoking are {@code pos-keys:manage}, reserved to SUPER_ADMIN's wildcard.
 */
@RestController
@RequestMapping("/admin/pos-keys")
@RequiredArgsConstructor
@Validated
@Tag(name = "POS integration", description = "Machine sign-in for a merchant's point-of-sale system")
public class AdminPosApiKeyController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PosApiKeyService posApiKeys;

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionCatalog.POS_KEYS_MANAGE + "')")
    @Operation(summary = "Issue a POS API key",
            description = """
                    Creates a key for a merchant's point-of-sale backend. The key is in this response ONLY:
                    we store a hash, so it can never be shown again. Hand it over as a secret (never by email
                    or chat in the clear).

                    With `shopId` the key is pinned to that shop and its merchant is the shop's. Without it,
                    `merchantId` is required and the key acts for any shop of that merchant, naming the shop
                    each time it asks for a token. `allowedIps` (IPv4/IPv6 addresses or CIDR ranges, up to
                    20) limits where the key works; ask the partner for their server's outgoing addresses.

                    A token from the key is a cashier (SHOP_USER) at the merchant. Requires
                    `pos-keys:manage` (SUPER_ADMIN only).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Key issued; `key` is shown once",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Issued",
                            value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "POS API key issued",
                                      "data": {
                                        "id": "3c9d8e7f-1a2b-4c3d-8e9f-0a1b2c3d4e5f",
                                        "name": "GAAP cloud - Chicken Inn",
                                        "key": "ibpos_k3Zr9QxT0b1LmN8pWcVd2eFgHh4iJj5kLl6mMn7oPp8",
                                        "keyPrefix": "ibpos_k3Zr9Q",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "shopId": null,
                                        "allowedIps": ["196.27.104.10", "41.190.32.0/24"],
                                        "createdAt": "2026-10-20T11:15:00+02:00"
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Validation", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "name": "name is required" } }
                                    """),
                            @ExampleObject(name = "No merchant or shop", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Name the merchant (merchantId) or the shop (shopId) the key acts for.",
                                      "data": { "errorCode": "pos_key_merchant_required" }
                                    }
                                    """),
                            @ExampleObject(name = "Unknown shop", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "We couldn't find that shop in loyalty.",
                                      "data": { "errorCode": "pos_key_shop_unknown" }
                                    }
                                    """),
                            @ExampleObject(name = "Shop of another merchant", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "That shop belongs to a different merchant.",
                                      "data": { "errorCode": "pos_key_merchant_mismatch" }
                                    }
                                    """),
                            @ExampleObject(name = "Bad address", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Not an IP address or CIDR range: gaap.example.com",
                                      "data": { "errorCode": "pos_key_bad_ip" }
                                    }
                                    """)
                    })),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Caller lacks pos-keys:manage",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Missing permission",
                            value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                    """))),
            @ApiResponse(responseCode = "503", description = "The audit row could not be written; no key was issued",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Audit unavailable",
                            value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "We couldn't record this change, so it wasn't made. Try again.",
                                      "data": { "errorCode": "audit_unavailable" }
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<PosApiKeyDTOs.Created>> create(
            @Valid @RequestBody PosApiKeyDTOs.CreateRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        PosApiKeyDTOs.Created created = posApiKeys.create(request, authentication.getName(), audit(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.created("POS API key issued", created));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionCatalog.POS_KEYS_READ + "')")
    @Operation(summary = "List POS API keys",
            description = "Newest first, optionally for one merchant. Never returns a key, only its "
                    + "`keyPrefix`. Requires `pos-keys:read`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Keys",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Keys",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "POS API keys retrieved",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "3c9d8e7f-1a2b-4c3d-8e9f-0a1b2c3d4e5f",
                                            "name": "GAAP cloud - Chicken Inn",
                                            "keyPrefix": "ibpos_k3Zr9Q",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "shopId": null,
                                            "allowedIps": ["196.27.104.10", "41.190.32.0/24"],
                                            "status": "ACTIVE",
                                            "createdBy": "admin@innbucks.co.zw",
                                            "createdAt": "2026-10-20T11:15:00+02:00",
                                            "lastUsedAt": "2026-10-20T11:42:07+02:00",
                                            "revokedAt": null,
                                            "revokedBy": null
                                          }
                                        ],
                                        "totalElements": 1,
                                        "totalPages": 1,
                                        "number": 0,
                                        "size": 20
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Caller lacks pos-keys:read",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Missing permission",
                            value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<PosApiKeyDTOs.KeyPage>> list(
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return ResponseEntity.ok(ApiResult.ok("POS API keys retrieved", posApiKeys.list(merchantId, page, size)));
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.POS_KEYS_MANAGE + "')")
    @Operation(summary = "Revoke a POS API key",
            description = "The key stops working at once, and so do the tokens already issued from it. There "
                    + "is no un-revoke: issue a new key. To rotate without downtime, issue the new key, let the "
                    + "partner switch, then revoke the old one. Requires `pos-keys:manage` (SUPER_ADMIN only).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Revoked",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Revoked",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "POS API key revoked",
                                      "data": {
                                        "id": "3c9d8e7f-1a2b-4c3d-8e9f-0a1b2c3d4e5f",
                                        "name": "GAAP cloud - Chicken Inn",
                                        "keyPrefix": "ibpos_k3Zr9Q",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "shopId": null,
                                        "allowedIps": ["196.27.104.10", "41.190.32.0/24"],
                                        "status": "REVOKED",
                                        "createdBy": "admin@innbucks.co.zw",
                                        "createdAt": "2026-10-20T11:15:00+02:00",
                                        "lastUsedAt": "2026-10-20T11:42:07+02:00",
                                        "revokedAt": "2026-10-21T09:03:12+02:00",
                                        "revokedBy": "admin@innbucks.co.zw"
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Caller lacks pos-keys:manage",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Missing permission",
                            value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                    """))),
            @ApiResponse(responseCode = "404", description = "No such key",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "We couldn't find that API key.",
                                      "data": { "errorCode": "pos_key_not_found" }
                                    }
                                    """))),
            @ApiResponse(responseCode = "409", description = "Already revoked",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Already revoked",
                            value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "This API key is already revoked.",
                                      "data": { "errorCode": "pos_key_already_revoked" }
                                    }
                                    """))),
            @ApiResponse(responseCode = "503", description = "The audit row could not be written; the key stays live",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Audit unavailable",
                            value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "We couldn't record this change, so it wasn't made. Try again.",
                                      "data": { "errorCode": "audit_unavailable" }
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<PosApiKeyDTOs.KeyView>> revoke(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) PosApiKeyDTOs.RevokeRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResult.ok("POS API key revoked",
                posApiKeys.revoke(id, request == null ? null : request.note(), authentication.getName(),
                        audit(httpRequest))));
    }

    private static AuditContext audit(HttpServletRequest request) {
        return new AuditContext(PosTokenController.clientIp(request), request.getHeader("User-Agent"));
    }
}
