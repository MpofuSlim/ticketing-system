package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.MerchantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/merchants")
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
            description = "Creates a new merchant under the current tenant. The merchant ID returned here " +
                          "is what callers reference in transaction/voucher/invoice requests. Fee fields " +
                          "drive periodic invoicing — set them to ZERO for free-tier merchants.")
    public Dtos.MerchantResponse create(@Valid @RequestBody Dtos.MerchantRequest req) {
        return merchants.create(tenantContext.requireTenantId(), req);
    }

    @GetMapping
    @Operation(summary = "List merchants for the current tenant",
            description = "Returns every merchant belonging to the X-Tenant-Id tenant. Used by the " +
                          "tenant admin UI to populate merchant pickers.")
    public List<Dtos.MerchantResponse> list() {
        return merchants.list(tenantContext.requireTenantId());
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate a merchant",
            description = "Sets status to ACTIVE. ACTIVE is the only state in which a merchant can earn " +
                          "or accept voucher redemptions. Idempotent.")
    public Dtos.MerchantResponse activate(@PathVariable UUID id) {
        return merchants.setActive(tenantContext.requireTenantId(), id, true);
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a merchant",
            description = "Sets status to INACTIVE. Inactive merchants will reject earn-points transactions " +
                          "with MERCHANT_INACTIVE. Existing wallets and unspent vouchers are preserved.")
    public Dtos.MerchantResponse deactivate(@PathVariable UUID id) {
        return merchants.setActive(tenantContext.requireTenantId(), id, false);
    }
}
