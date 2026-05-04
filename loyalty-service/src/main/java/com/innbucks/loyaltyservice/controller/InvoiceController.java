package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.InvoicingService;
import com.innbucks.loyaltyservice.service.MerchantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/invoices")
@Tag(name = "Invoicing",
     description = "Per-merchant periodic billing. The daily InvoiceScheduler calls `/generate` for each " +
                   "active merchant; this controller exposes manual generation, listing, and mark-as-paid " +
                   "for support flows. Total = (pointsIssued × feePerPointIssued) + " +
                   "(vouchersIssued × feePerVoucherIssued) + (vouchersRedeemed × feePerVoucherRedeemed). " +
                   "Requires X-Tenant-Id.")
public class InvoiceController {

    private final InvoicingService invoicing;
    private final MerchantService merchants;
    private final TenantContext tenantContext;

    public InvoiceController(InvoicingService invoicing, MerchantService merchants,
                             TenantContext tenantContext) {
        this.invoicing = invoicing;
        this.merchants = merchants;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/merchant/{merchantId}")
    @Operation(summary = "List invoices for a merchant",
            description = "Returns every invoice ever generated for the merchant, most recent first. " +
                          "Includes paid, pending, and overdue. Used by the merchant billing dashboard.")
    public List<Dtos.InvoiceResponse> listForMerchant(@PathVariable UUID merchantId) {
        return invoicing.listForMerchant(tenantContext.requireTenantId(), merchantId);
    }

    @PostMapping("/generate")
    @Operation(summary = "Manually generate an invoice",
            description = "Operator escape hatch — usually unnecessary because InvoiceScheduler runs nightly. " +
                          "Body: `{ merchantId, periodStart, periodEnd }` (ISO dates). Aggregates points " +
                          "issued + vouchers issued/redeemed within the period and applies the merchant's " +
                          "fee schedule. Idempotent on (merchant, period).")
    public Dtos.InvoiceResponse generate(@RequestBody Map<String, String> body) {
        UUID merchantId = UUID.fromString(body.get("merchantId"));
        LocalDate periodStart = LocalDate.parse(body.get("periodStart"));
        LocalDate periodEnd = LocalDate.parse(body.get("periodEnd"));
        UUID tenantId = tenantContext.requireTenantId();
        var m = merchants.requireMerchant(tenantId, merchantId);
        return InvoicingService.toResponse(invoicing.generate(m, periodStart, periodEnd));
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "Mark an invoice as paid",
            description = "Records that the invoice has been settled (timestamps `paidAt`). Loyalty-service " +
                          "does not collect payments itself — payment-service or an external accounting " +
                          "system calls this once funds clear.")
    public Dtos.InvoiceResponse pay(@PathVariable UUID id) {
        return InvoicingService.toResponse(invoicing.markPaid(tenantContext.requireTenantId(), id));
    }
}
