package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.service.VoucherTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/vouchers")
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
    public VoucherTemplate createTemplate(@Valid @RequestBody Dtos.VoucherTemplateRequest req) {
        return templateService.create(tenantContext.requireTenantId(), req);
    }

    @GetMapping("/templates")
    @Operation(summary = "List voucher templates",
            description = "Returns every template defined for the current tenant. Used by merchant dashboards " +
                          "to populate \"issue voucher\" pickers.")
    public List<VoucherTemplate> listTemplates() {
        return templateService.list(tenantContext.requireTenantId());
    }

    @PostMapping("/issue")
    @Operation(summary = "Issue a single voucher",
            description = "Mints one voucher from a template. Optionally assign it to a known LoyaltyUser " +
                          "(`assignedUserId`) or to an arbitrary phone (`assigneePhone`). Returns the signed " +
                          "voucher code that the customer presents at redemption. Delivery channel (SMS, " +
                          "WhatsApp, EMAIL, PUSH, POS, NONE) controls how NotificationGateway notifies the customer.")
    public Dtos.VoucherResponse issue(@Valid @RequestBody Dtos.IssueVoucherRequest req) {
        return voucherService.issue(tenantContext.requireTenantId(), req);
    }

    @PostMapping("/issue-bulk")
    @Operation(summary = "Bulk-issue vouchers from a template",
            description = "Mints `quantity` independent unassigned vouchers in one call (campaign / corporate / " +
                          "referral distributions). Each gets its own unique signed code. Use the returned " +
                          "`batchId` (via the codes' batch reference) to track the run.")
    public List<Dtos.VoucherResponse> issueBulk(@Valid @RequestBody Dtos.BulkIssueRequest req) {
        return voucherService.issueBulk(tenantContext.requireTenantId(), req);
    }

    @PostMapping("/redeem")
    @Operation(summary = "Redeem a voucher at a merchant",
            description = "Validates the code's signature, expiry, status, and merchant scope; checks the " +
                          "device fingerprint / IP against velocity limits; and decrements `usesRemaining`. " +
                          "Failed attempts are recorded in `fraud_attempts` and may auto-block the user via " +
                          "FraudService when the velocity threshold is exceeded.")
    public Dtos.RedemptionResponse redeem(@Valid @RequestBody Dtos.RedeemVoucherRequest req) {
        return voucherService.redeem(tenantContext.requireTenantId(), req);
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "Revoke an issued voucher",
            description = "Marks the voucher REVOKED so it can no longer be redeemed. Use for fraud, " +
                          "support refunds, or when a customer reports their code stolen. Already-redeemed " +
                          "vouchers cannot be revoked.")
    public void revoke(@PathVariable UUID id) {
        voucherService.revoke(tenantContext.requireTenantId(), id);
    }

    @PostMapping("/codes/{code}/viewed")
    @Operation(summary = "Mark a voucher as viewed by the customer",
            description = "Read receipt — call this when the customer's app displays the voucher. " +
                          "Used by analytics to measure delivery-to-view conversion. No tenant header required " +
                          "since the code itself identifies the tenant.")
    public void markViewed(@PathVariable String code) {
        voucherService.markViewed(code);
    }

    @GetMapping("/users/{userId}/active")
    @Operation(summary = "List a user's active vouchers",
            description = "Returns all ISSUED / PARTIALLY_USED vouchers belonging to the loyalty user. " +
                          "Powers the SuperApp \"my vouchers\" wallet view.")
    public List<Dtos.VoucherResponse> activeForUser(@PathVariable UUID userId) {
        return voucherService.activeForUser(userId);
    }

    @GetMapping
    @Operation(summary = "Find vouchers by status",
            description = "Operator/merchant query: list every voucher in the given status across the tenant. " +
                          "Common statuses: ISSUED, PARTIALLY_USED, REDEEMED, EXPIRED, REVOKED.")
    public List<Dtos.VoucherResponse> findByStatus(@RequestParam("status") Voucher.Status status) {
        return voucherService.findByStatus(tenantContext.requireTenantId(), status);
    }
}
