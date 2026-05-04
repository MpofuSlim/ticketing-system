package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    public ResponseEntity<Dtos.TenantResponse> create(@Valid @RequestBody Dtos.TenantRequest req) {
        return ResponseEntity.ok(tenantService.create(req));
    }

    @GetMapping
    @Operation(summary = "List all tenants",
            description = "Returns every tenant on the platform. Intended for operator dashboards. " +
                          "Operator-only — no tenant header required.")
    public List<Dtos.TenantResponse> list() {
        return tenantService.list();
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend a tenant",
            description = "Marks the tenant as SUSPENDED. While suspended, all the tenant's customer-facing " +
                          "loyalty operations (earn, redeem, voucher issue/redeem) will be rejected. " +
                          "Use to halt activity during billing disputes or compliance reviews.")
    public Dtos.TenantResponse suspend(@PathVariable UUID id) {
        return tenantService.suspend(id);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Reactivate a suspended tenant",
            description = "Reverses /suspend by setting status back to ACTIVE. Idempotent — safe to call " +
                          "on an already-active tenant.")
    public Dtos.TenantResponse activate(@PathVariable UUID id) {
        return tenantService.activate(id);
    }
}
