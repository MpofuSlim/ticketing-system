package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.MiniAppService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/mini-apps")
public class MiniAppController {

    private final MiniAppService miniApps;
    private final TenantContext tenantContext;

    public MiniAppController(MiniAppService miniApps, TenantContext tenantContext) {
        this.miniApps = miniApps;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/manifest")
    public List<Dtos.MiniAppManifest> manifest(@RequestParam(required = false) UUID merchantId) {
        return miniApps.manifest(tenantContext.requireTenantId(), merchantId);
    }
}
