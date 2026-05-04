package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/tenants")
@Tag(name = "Tenants",
     description = "Top-level platform tenants — the white-label customers of the loyalty platform. " +
                   "These endpoints are operator-level: they do NOT require an X-Tenant-Id header, " +
                   "since they're how tenants are first registered.")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    @Operation(summary = "Register a new tenant",
            description = "Onboards a new tenant onto the platform. The returned `id` is what every other " +
                          "endpoint expects in the `X-Tenant-Id` header. Operator-only — no tenant header required.")
    public ResponseEntity<ApiResult<Dtos.TenantResponse>> create(@Valid @RequestBody Dtos.TenantRequest req) {
        Dtos.TenantResponse data = tenantService.create(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Tenant created successfully", data));
    }

    @GetMapping
    @Operation(summary = "List all tenants",
            description = "Returns every tenant on the platform. Intended for operator dashboards. " +
                          "Operator-only — no tenant header required.")
    public ResponseEntity<ApiResult<PageResponse<Dtos.TenantResponse>>> list(@ParameterObject Pageable pageable) {
        PageResponse<Dtos.TenantResponse> data = PageResponse.from(tenantService.list(pageable));
        return ResponseEntity.ok(ApiResult.ok("Tenants retrieved successfully", data));
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend a tenant",
            description = "Marks the tenant as SUSPENDED. While suspended, all the tenant's customer-facing " +
                          "loyalty operations (earn, redeem, voucher issue/redeem) will be rejected. " +
                          "Use to halt activity during billing disputes or compliance reviews.")
    public ResponseEntity<ApiResult<Dtos.TenantResponse>> suspend(@PathVariable UUID id) {
        Dtos.TenantResponse data = tenantService.suspend(id);
        return ResponseEntity.ok(ApiResult.ok("Tenant suspended successfully", data));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Reactivate a suspended tenant",
            description = "Reverses /suspend by setting status back to ACTIVE. Idempotent — safe to call " +
                          "on an already-active tenant.")
    public ResponseEntity<ApiResult<Dtos.TenantResponse>> activate(@PathVariable UUID id) {
        Dtos.TenantResponse data = tenantService.activate(id);
        return ResponseEntity.ok(ApiResult.ok("Tenant activated successfully", data));
    }
}
