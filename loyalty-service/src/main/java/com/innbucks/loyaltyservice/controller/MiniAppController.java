package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.MiniAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/mini-apps")
@Tag(name = "Mini-apps",
     description = "SuperApp shell metadata. The SuperApp client renders a list of mini-apps (merchant " +
                   "storefronts, sub-experiences) and uses each manifest's `entryUrl` and icon to launch " +
                   "the embedded experience. Requires X-Tenant-Id.")
public class MiniAppController {

    private final MiniAppService miniApps;
    private final TenantContext tenantContext;

    public MiniAppController(MiniAppService miniApps, TenantContext tenantContext) {
        this.miniApps = miniApps;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/manifest")
    @Operation(summary = "List mini-app manifests",
            description = "Returns every mini-app available to the current tenant, optionally filtered to a " +
                          "single merchant via the `merchantId` query param. Each manifest carries the " +
                          "entry URL, icon, and human-readable name the SuperApp shell renders on the home " +
                          "screen.")
    public List<Dtos.MiniAppManifest> manifest(@RequestParam(required = false) UUID merchantId) {
        return miniApps.manifest(tenantContext.requireTenantId(), merchantId);
    }
}
