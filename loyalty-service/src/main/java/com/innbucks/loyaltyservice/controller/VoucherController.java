package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.service.VoucherTemplateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/vouchers")
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
    public VoucherTemplate createTemplate(@Valid @RequestBody Dtos.VoucherTemplateRequest req) {
        return templateService.create(tenantContext.requireTenantId(), req);
    }

    @GetMapping("/templates")
    public List<VoucherTemplate> listTemplates() {
        return templateService.list(tenantContext.requireTenantId());
    }

    @PostMapping("/issue")
    public Dtos.VoucherResponse issue(@Valid @RequestBody Dtos.IssueVoucherRequest req) {
        return voucherService.issue(tenantContext.requireTenantId(), req);
    }

    @PostMapping("/issue-bulk")
    public List<Dtos.VoucherResponse> issueBulk(@Valid @RequestBody Dtos.BulkIssueRequest req) {
        return voucherService.issueBulk(tenantContext.requireTenantId(), req);
    }

    @PostMapping("/redeem")
    public Dtos.RedemptionResponse redeem(@Valid @RequestBody Dtos.RedeemVoucherRequest req) {
        return voucherService.redeem(tenantContext.requireTenantId(), req);
    }

    @PostMapping("/{id}/revoke")
    public void revoke(@PathVariable UUID id) {
        voucherService.revoke(tenantContext.requireTenantId(), id);
    }

    @PostMapping("/codes/{code}/viewed")
    public void markViewed(@PathVariable String code) {
        voucherService.markViewed(code);
    }

    @GetMapping("/users/{userId}/active")
    public List<Dtos.VoucherResponse> activeForUser(@PathVariable UUID userId) {
        return voucherService.activeForUser(userId);
    }

    @GetMapping
    public List<Dtos.VoucherResponse> findByStatus(@RequestParam("status") Voucher.Status status) {
        return voucherService.findByStatus(tenantContext.requireTenantId(), status);
    }
}
