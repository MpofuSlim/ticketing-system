package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.MerchantService;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/loyalty/merchants")
@Tag(name = "Merchants",
     description = "Merchants are the brands/outlets that issue points and vouchers within a tenant. " +
                   "Each merchant has its own billing cycle and fee schedule (per-point, per-voucher-issued, " +
                   "per-voucher-redeemed) used by InvoicingService. Requires X-Tenant-Id.")
public class MerchantController {

    private final MerchantService merchants;
    private final TenantContext tenantContext;

    public MerchantController(MerchantService merchants, TenantContext tenantContext) {
        this.merchants = merchants;
        this.tenantContext = tenantContext;
    }

    @PostMapping
    @Operation(summary = "Onboard a merchant",
            description = "Creates a new merchant under the current tenant. Merchant name is inherited from " +
                          "the tenant (which was set from the user-service business profile at registration), " +
                          "so the caller only supplies category, billing cycle, and fee schedule. " +
                          "The merchant ID returned here is what callers reference in transaction/voucher/" +
                          "invoice requests. Fee fields drive periodic invoicing — set them to ZERO for " +
                          "free-tier merchants.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Merchant created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Merchant created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Merchant created successfully",
                                      "data": {
                                        "id": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "name": "Innbucks Westgate",
                                        "category": "Coffee",
                                        "currency": "USD",
                                        "billingCycle": "MONTHLY",
                                        "status": "ACTIVE"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (e.g. invalid currency or fee schedule)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "currency: must be a 3-letter ISO 4217 code",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.MerchantResponse>> create(@Valid @RequestBody Dtos.MerchantRequest req) {
        Dtos.MerchantResponse data = merchants.create(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Merchant created successfully", data));
    }

    @GetMapping
    @Operation(summary = "List merchants for the current tenant",
            description = "Returns every merchant belonging to the X-Tenant-Id tenant. Used by the " +
                          "tenant admin UI to populate merchant pickers.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Merchants returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated merchants", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Merchants retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "name": "Innbucks Westgate",
                                            "category": "Coffee",
                                            "currency": "USD",
                                            "billingCycle": "MONTHLY",
                                            "status": "ACTIVE"
                                          },
                                          {
                                            "id": "c5d1e3f4-3456-7890-abcd-ef0123456789",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "name": "Innbucks Sandton",
                                            "category": "Coffee",
                                            "currency": "USD",
                                            "billingCycle": "WEEKLY",
                                            "status": "INACTIVE"
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
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<PageResponse<Dtos.MerchantResponse>>> list(@ParameterObject Pageable pageable) {
        PageResponse<Dtos.MerchantResponse> data = PageResponse.from(
                merchants.list(tenantContext.requireTenantId(), pageable));
        return ResponseEntity.ok(ApiResult.ok("Merchants retrieved successfully", data));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate a merchant",
            description = "Sets status to ACTIVE. ACTIVE is the only state in which a merchant can earn " +
                          "or accept voucher redemptions. Idempotent.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Merchant activated",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Merchant activated", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Merchant activated successfully",
                                      "data": {
                                        "id": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "name": "Innbucks Westgate",
                                        "category": "Coffee",
                                        "currency": "USD",
                                        "billingCycle": "MONTHLY",
                                        "status": "ACTIVE"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Merchant not found in this tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Merchant not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.MerchantResponse>> activate(@PathVariable UUID id) {
        Dtos.MerchantResponse data = merchants.setActive(tenantContext.requireTenantId(), id, true);
        return ResponseEntity.ok(ApiResult.ok("Merchant activated successfully", data));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a merchant",
            description = "Sets status to INACTIVE. Inactive merchants will reject earn-points transactions " +
                          "with MERCHANT_INACTIVE. Existing wallets and unspent vouchers are preserved.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Merchant deactivated",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Merchant deactivated", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Merchant deactivated successfully",
                                      "data": {
                                        "id": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "name": "Innbucks Westgate",
                                        "category": "Coffee",
                                        "currency": "USD",
                                        "billingCycle": "MONTHLY",
                                        "status": "INACTIVE"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Merchant not found in this tenant",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Merchant not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.MerchantResponse>> deactivate(@PathVariable UUID id) {
        Dtos.MerchantResponse data = merchants.setActive(tenantContext.requireTenantId(), id, false);
        return ResponseEntity.ok(ApiResult.ok("Merchant deactivated successfully", data));
    }
}
