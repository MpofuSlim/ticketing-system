package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.MerchantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/merchants")
public class MerchantController {

    private final MerchantService merchants;
    private final TenantContext tenantContext;

    public MerchantController(MerchantService merchants, TenantContext tenantContext) {
        this.merchants = merchants;
        this.tenantContext = tenantContext;
    }

    @PostMapping
    public Dtos.MerchantResponse create(@Valid @RequestBody Dtos.MerchantRequest req) {
        return merchants.create(tenantContext.requireTenantId(), req);
    }

    @GetMapping
    public List<Dtos.MerchantResponse> list() {
        return merchants.list(tenantContext.requireTenantId());
    }

    @PostMapping("/{id}/activate")
    public Dtos.MerchantResponse activate(@PathVariable UUID id) {
        return merchants.setActive(tenantContext.requireTenantId(), id, true);
    }

    @PostMapping("/{id}/deactivate")
    public Dtos.MerchantResponse deactivate(@PathVariable UUID id) {
        return merchants.setActive(tenantContext.requireTenantId(), id, false);
    }
}
