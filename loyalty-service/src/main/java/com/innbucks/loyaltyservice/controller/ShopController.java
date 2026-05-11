package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.ShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/loyalty/shops")
@Tag(name = "Shops",
     description = "Physical outlets under a Merchant. e.g. \"Pizza Inn Avondale\" and " +
                   "\"Pizza Inn Westgate\" are two shops under the \"Pizza Inn\" merchant. " +
                   "Shops inherit their merchant's loyalty rules transparently — earn-rate " +
                   "evaluation falls through merchant-specific rules → global tenant rules " +
                   "automatically when a transaction is posted with the shop's merchantId. " +
                   "Requires X-Tenant-Id.")
public class ShopController {

    private final ShopService shops;
    private final TenantContext tenantContext;

    public ShopController(ShopService shops, TenantContext tenantContext) {
        this.shops = shops;
        this.tenantContext = tenantContext;
    }

    @PostMapping
    @Operation(summary = "Onboard a shop under a merchant",
            description = "Creates a new shop outlet under the given merchant. `merchantId` is supplied " +
                          "in the request body and must reference a merchant that exists in the current " +
                          "tenant.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Shop created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Shop created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Shop created successfully",
                                      "data": {
                                        "id": "11111111-aaaa-bbbb-cccc-222222222222",
                                        "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "name": "Pizza Inn Avondale",
                                        "code": "AVONDALE",
                                        "address": "123 King George Rd, Avondale, Harare",
                                        "status": "ACTIVE",
                                        "createdAt": "2026-05-11T10:15:00Z"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (missing merchantId or name)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "merchantId: must not be null",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Merchant not found in this tenant"
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.ShopResponse>> create(@Valid @RequestBody Dtos.ShopRequest req) {
        Dtos.ShopResponse data = shops.create(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Shop created successfully", data));
    }

    @GetMapping
    @Operation(summary = "List shops for the current tenant",
            description = "Returns every shop in the tenant. Pass `merchantId` to filter to a single " +
                          "merchant's outlets.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Shops returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated shops", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Shops retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "11111111-aaaa-bbbb-cccc-222222222222",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "name": "Pizza Inn Avondale",
                                            "code": "AVONDALE",
                                            "address": "123 King George Rd, Avondale, Harare",
                                            "status": "ACTIVE",
                                            "createdAt": "2026-05-11T10:15:00Z"
                                          },
                                          {
                                            "id": "22222222-aaaa-bbbb-cccc-333333333333",
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                            "name": "Pizza Inn Westgate",
                                            "code": "WESTGATE",
                                            "address": "Westgate Shopping Mall, Harare",
                                            "status": "ACTIVE",
                                            "createdAt": "2026-05-11T10:20:00Z"
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
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<PageResponse<Dtos.ShopResponse>>> list(
            @Parameter(description = "Optional merchant filter — return only shops under this merchant.")
            @RequestParam(required = false) UUID merchantId,
            @ParameterObject Pageable pageable) {
        PageResponse<Dtos.ShopResponse> data = PageResponse.from(
                shops.list(tenantContext.requireTenantId(), merchantId, pageable));
        return ResponseEntity.ok(ApiResult.ok("Shops retrieved successfully", data));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a shop by id")
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.ShopResponse>> get(@PathVariable UUID id) {
        Dtos.ShopResponse data = shops.get(tenantContext.requireTenantId(), id);
        return ResponseEntity.ok(ApiResult.ok("Shop retrieved successfully", data));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a shop",
            description = "Updates display name, outlet code, or address.")
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.ShopResponse>> update(@PathVariable UUID id,
                                                               @Valid @RequestBody Dtos.ShopRequest req) {
        Dtos.ShopResponse data = shops.update(tenantContext.requireTenantId(), id, req);
        return ResponseEntity.ok(ApiResult.ok("Shop updated successfully", data));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate a shop",
            description = "Sets status to ACTIVE. Idempotent.")
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.ShopResponse>> activate(@PathVariable UUID id) {
        Dtos.ShopResponse data = shops.setActive(tenantContext.requireTenantId(), id, true);
        return ResponseEntity.ok(ApiResult.ok("Shop activated successfully", data));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a shop",
            description = "Sets status to INACTIVE. The parent merchant and any issued vouchers are " +
                          "unaffected; only the shop outlet is taken offline.")
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.ShopResponse>> deactivate(@PathVariable UUID id) {
        Dtos.ShopResponse data = shops.setActive(tenantContext.requireTenantId(), id, false);
        return ResponseEntity.ok(ApiResult.ok("Shop deactivated successfully", data));
    }

    @GetMapping("/by-merchant/{merchantId}")
    @Operation(summary = "List shops under a merchant",
            description = "Convenience endpoint for nested navigation — returns every shop belonging " +
                          "to the given merchant in the current tenant.")
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<List<Dtos.ShopResponse>>> listForMerchant(@PathVariable UUID merchantId) {
        List<Dtos.ShopResponse> data = shops.listForMerchant(tenantContext.requireTenantId(), merchantId);
        return ResponseEntity.ok(ApiResult.ok("Shops retrieved successfully", data));
    }
}
