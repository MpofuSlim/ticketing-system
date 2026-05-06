package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.service.VoucherTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/loyalty/vouchers")
@Tag(name = "Vouchers",
     description = "Voucher templates and individual voucher lifecycle. Each voucher carries an HMAC-SHA256 " +
                   "signature over its code (signed with `loyalty.voucher.secret`) so redemption can be " +
                   "verified offline if needed. Anti-fraud (duplicate, wrong-merchant, blocked-user, " +
                   "blocked-device, velocity) is enforced on every `/redeem`. Requires X-Tenant-Id.")
public class VoucherController {

    private final VoucherService voucherService;
    private final VoucherTemplateService templateService;
    private final TenantContext tenantContext;

    public VoucherController(VoucherService voucherService,
                             VoucherTemplateService templateService,
                             TenantContext tenantContext) {
        this.voucherService = voucherService;
        this.templateService = templateService;
        this.tenantContext = tenantContext;
    }

    @PostMapping("/templates")
    @Operation(summary = "Create a voucher template",
            description = "Defines the *kind* of voucher (SINGLE_USE / MULTI_USE / CAMPAIGN / REFERRAL / " +
                          "CORPORATE) and how its value is expressed (AMOUNT / PERCENT / FREE_ITEM / COMBO). " +
                          "Templates are reusable — actual vouchers are minted from them via /issue or /issue-bulk.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Template created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Template created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Voucher template created successfully",
                                      "data": {
                                        "id": "a9b5c7d8-7890-1234-ef01-234567890123",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "name": "$5 Off Coffee",
                                        "type": "SINGLE_USE",
                                        "valueType": "AMOUNT",
                                        "value": 5.0000,
                                        "currency": "USD",
                                        "freeItemSku": null,
                                        "usageLimit": 1,
                                        "validityDays": 30,
                                        "applicableOutlets": "WESTGATE,SANDTON",
                                        "active": true,
                                        "createdAt": "2026-05-04T10:00:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation error",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "name: must not be blank",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<VoucherTemplate>> createTemplate(@Valid @RequestBody Dtos.VoucherTemplateRequest req) {
        VoucherTemplate data = templateService.create(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Voucher template created successfully", data));
    }

    @GetMapping("/templates")
    @Operation(summary = "List voucher templates",
            description = "Returns every template defined for the current tenant. Used by merchant dashboards " +
                          "to populate \"issue voucher\" pickers.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Templates returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated templates", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Voucher templates retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "a9b5c7d8-7890-1234-ef01-234567890123",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "name": "$5 Off Coffee",
                                            "type": "SINGLE_USE",
                                            "valueType": "AMOUNT",
                                            "value": 5.0000,
                                            "currency": "USD",
                                            "freeItemSku": null,
                                            "usageLimit": 1,
                                            "validityDays": 30,
                                            "applicableOutlets": "WESTGATE,SANDTON",
                                            "active": true,
                                            "createdAt": "2026-05-04T10:00:00Z"
                                          },
                                          {
                                            "id": "b0a6d8e9-8901-2345-f012-345678901234",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "name": "10% Off Pastries",
                                            "type": "CAMPAIGN",
                                            "valueType": "PERCENT",
                                            "value": 10.0000,
                                            "currency": "USD",
                                            "freeItemSku": null,
                                            "usageLimit": 1,
                                            "validityDays": 14,
                                            "applicableOutlets": null,
                                            "active": true,
                                            "createdAt": "2026-04-30T09:00:00Z"
                                          }
                                        ],
                                        "page": 0,
                                        "size": 20,
                                        "totalElements": 2,
                                        "totalPages": 1,
                                        "first": true,
                                        "last": true
                                      }
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<PageResponse<VoucherTemplate>>> listTemplates(@ParameterObject Pageable pageable) {
        PageResponse<VoucherTemplate> data = PageResponse.from(
                templateService.list(tenantContext.requireTenantId(), pageable));
        return ResponseEntity.ok(ApiResult.ok("Voucher templates retrieved successfully", data));
    }

    @PostMapping("/issue")
    @Operation(summary = "Issue a single voucher",
            description = "Mints one voucher from a template. Optionally assign it to a known LoyaltyUser " +
                          "(`assignedUserId`) or to an arbitrary phone (`assigneePhone`). Returns the signed " +
                          "voucher code that the customer presents at redemption. Delivery channel (SMS, " +
                          "WhatsApp, EMAIL, PUSH, POS, NONE) controls how NotificationGateway notifies the customer.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Voucher issued",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Voucher issued", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Voucher issued successfully",
                                      "data": {
                                        "id": "c1b7e9f0-9012-3456-0123-456789012345",
                                        "code": "VCH-AB12CD34",
                                        "status": "ISSUED",
                                        "templateId": "a9b5c7d8-7890-1234-ef01-234567890123",
                                        "assignedUserId": "d2c8f0a1-0123-4567-1234-567890123456",
                                        "assigneePhone": "+254700000000",
                                        "usesRemaining": 1,
                                        "issuedAt": "2026-05-04T10:30:00Z",
                                        "expiresAt": "2026-06-03T10:30:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation error",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "templateId: must not be null",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Template not found in this tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Voucher template not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<Dtos.VoucherResponse>> issue(@Valid @RequestBody Dtos.IssueVoucherRequest req) {
        Dtos.VoucherResponse data = voucherService.issue(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Voucher issued successfully", data));
    }

    @PostMapping("/issue-bulk")
    @Operation(summary = "Bulk-issue vouchers from a template",
            description = "Mints `quantity` independent unassigned vouchers in one call (campaign / corporate / " +
                          "referral distributions). Each gets its own unique signed code. Use the returned " +
                          "`batchId` (via the codes' batch reference) to track the run.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Bulk vouchers issued",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Bulk issued", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Vouchers issued successfully",
                                      "data": [
                                        {
                                          "id": "c1b7e9f0-9012-3456-0123-456789012345",
                                          "code": "VCH-AB12CD34",
                                          "status": "ISSUED",
                                          "templateId": "a9b5c7d8-7890-1234-ef01-234567890123",
                                          "assignedUserId": null,
                                          "assigneePhone": null,
                                          "usesRemaining": 1,
                                          "issuedAt": "2026-05-04T10:30:00Z",
                                          "expiresAt": "2026-06-03T10:30:00Z"
                                        },
                                        {
                                          "id": "d2c8f0a1-0123-4567-1234-567890123456",
                                          "code": "VCH-EF56GH78",
                                          "status": "ISSUED",
                                          "templateId": "a9b5c7d8-7890-1234-ef01-234567890123",
                                          "assignedUserId": null,
                                          "assigneePhone": null,
                                          "usesRemaining": 1,
                                          "issuedAt": "2026-05-04T10:30:00Z",
                                          "expiresAt": "2026-06-03T10:30:00Z"
                                        }
                                      ]
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation error",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "quantity: must be at least 1",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Template not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Voucher template not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<List<Dtos.VoucherResponse>>> issueBulk(@Valid @RequestBody Dtos.BulkIssueRequest req) {
        List<Dtos.VoucherResponse> data = voucherService.issueBulk(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Vouchers issued successfully", data));
    }

    @PostMapping("/redeem")
    @Operation(summary = "Redeem a voucher at a merchant",
            description = "Validates the code's signature, expiry, status, and merchant scope; checks the " +
                          "device fingerprint / IP against velocity limits; and decrements `usesRemaining`. " +
                          "Failed attempts are recorded in `fraud_attempts` and may auto-block the user via " +
                          "FraudService when the velocity threshold is exceeded.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Voucher redeemed",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Redeemed", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Voucher redeemed successfully",
                                      "data": {
                                        "redemptionId": "e3d9a1b2-1234-5678-2345-678901234567",
                                        "voucherId": "c1b7e9f0-9012-3456-0123-456789012345",
                                        "status": "REDEEMED",
                                        "usesRemaining": 0,
                                        "value": 5.0000,
                                        "valueType": "AMOUNT",
                                        "redeemedAt": "2026-05-04T14:00:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation error",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "code: must not be blank",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Voucher code not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Voucher not found",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "Anti-fraud rejection (signature mismatch, expired, wrong merchant, blocked, velocity)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Velocity blocked", value = """
                                    {
                                      "code": "422 UNPROCESSABLE_ENTITY",
                                      "message": "VELOCITY_BLOCKED",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<Dtos.RedemptionResponse>> redeem(@Valid @RequestBody Dtos.RedeemVoucherRequest req) {
        Dtos.RedemptionResponse data = voucherService.redeem(tenantContext.requireTenantId(), req);
        return ResponseEntity.ok(ApiResult.ok("Voucher redeemed successfully", data));
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "Revoke an issued voucher",
            description = "Marks the voucher REVOKED so it can no longer be redeemed. Use for fraud, " +
                          "support refunds, or when a customer reports their code stolen. Already-redeemed " +
                          "vouchers cannot be revoked.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Voucher revoked",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Revoked", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Voucher revoked successfully",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Voucher not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Voucher not found",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "Voucher already redeemed and cannot be revoked",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Already redeemed", value = """
                                    {
                                      "code": "422 UNPROCESSABLE_ENTITY",
                                      "message": "ALREADY_REDEEMED",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<Void>> revoke(@PathVariable UUID id) {
        voucherService.revoke(tenantContext.requireTenantId(), id);
        return ResponseEntity.ok(ApiResult.ok("Voucher revoked successfully", null));
    }

    @PostMapping("/codes/{code}/viewed")
    @Operation(summary = "Mark a voucher as viewed by the customer",
            description = "Read receipt — call this when the customer's app displays the voucher. " +
                          "Used by analytics to measure delivery-to-view conversion. No tenant header required " +
                          "since the code itself identifies the tenant.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "View recorded",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "View recorded", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Voucher view recorded",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Voucher code not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Voucher not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<Void>> markViewed(@PathVariable String code) {
        voucherService.markViewed(code);
        return ResponseEntity.ok(ApiResult.ok("Voucher view recorded", null));
    }

    @GetMapping("/users/{userId}/active")
    @Operation(summary = "List a user's active vouchers",
            description = "Returns all ISSUED / PARTIALLY_USED vouchers belonging to the loyalty user. " +
                          "Powers the SuperApp \"my vouchers\" wallet view.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Active vouchers returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Active vouchers", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Active vouchers retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "c1b7e9f0-9012-3456-0123-456789012345",
                                            "code": "VCH-AB12CD34",
                                            "status": "ISSUED",
                                            "templateId": "a9b5c7d8-7890-1234-ef01-234567890123",
                                            "assignedUserId": "d2c8f0a1-0123-4567-1234-567890123456",
                                            "assigneePhone": "+254700000000",
                                            "usesRemaining": 1,
                                            "issuedAt": "2026-05-04T10:30:00Z",
                                            "expiresAt": "2026-06-03T10:30:00Z"
                                          }
                                        ],
                                        "page": 0,
                                        "size": 20,
                                        "totalElements": 1,
                                        "totalPages": 1,
                                        "first": true,
                                        "last": true
                                      }
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<PageResponse<Dtos.VoucherResponse>>> activeForUser(@PathVariable UUID userId,
                                                                                       @ParameterObject Pageable pageable) {
        PageResponse<Dtos.VoucherResponse> data = PageResponse.from(voucherService.activeForUser(userId, pageable));
        return ResponseEntity.ok(ApiResult.ok("Active vouchers retrieved successfully", data));
    }

    @GetMapping
    @Operation(summary = "Find vouchers by status",
            description = "Operator/merchant query: list every voucher in the given status across the tenant. " +
                          "Common statuses: ISSUED, PARTIALLY_USED, REDEEMED, EXPIRED, REVOKED.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Vouchers returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "By status", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Vouchers retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "c1b7e9f0-9012-3456-0123-456789012345",
                                            "code": "VCH-AB12CD34",
                                            "status": "ISSUED",
                                            "templateId": "a9b5c7d8-7890-1234-ef01-234567890123",
                                            "assignedUserId": "d2c8f0a1-0123-4567-1234-567890123456",
                                            "assigneePhone": "+254700000000",
                                            "usesRemaining": 1,
                                            "issuedAt": "2026-05-04T10:30:00Z",
                                            "expiresAt": "2026-06-03T10:30:00Z"
                                          },
                                          {
                                            "id": "f4eab2c3-2345-6789-3456-789012345678",
                                            "code": "VCH-IJ90KL12",
                                            "status": "ISSUED",
                                            "templateId": "a9b5c7d8-7890-1234-ef01-234567890123",
                                            "assignedUserId": null,
                                            "assigneePhone": "+254711111111",
                                            "usesRemaining": 1,
                                            "issuedAt": "2026-05-03T14:00:00Z",
                                            "expiresAt": "2026-06-02T14:00:00Z"
                                          }
                                        ],
                                        "page": 0,
                                        "size": 20,
                                        "totalElements": 2,
                                        "totalPages": 1,
                                        "first": true,
                                        "last": true
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Unknown status value",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Bad status", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Unknown voucher status: FOO",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<PageResponse<Dtos.VoucherResponse>>> findByStatus(@RequestParam("status") Voucher.Status status,
                                                                                      @ParameterObject Pageable pageable) {
        PageResponse<Dtos.VoucherResponse> data = PageResponse.from(
                voucherService.findByStatus(tenantContext.requireTenantId(), status, pageable));
        return ResponseEntity.ok(ApiResult.ok("Vouchers retrieved successfully", data));
    }
}
