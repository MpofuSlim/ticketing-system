package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.InvoicingService;
import com.innbucks.loyaltyservice.service.MerchantService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/invoices")
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
    public List<Dtos.InvoiceResponse> listForMerchant(@PathVariable UUID merchantId) {
        return invoicing.listForMerchant(tenantContext.requireTenantId(), merchantId);
    }

    @PostMapping("/generate")
    public Dtos.InvoiceResponse generate(@RequestBody Map<String, String> body) {
        UUID merchantId = UUID.fromString(body.get("merchantId"));
        LocalDate periodStart = LocalDate.parse(body.get("periodStart"));
        LocalDate periodEnd = LocalDate.parse(body.get("periodEnd"));
        UUID tenantId = tenantContext.requireTenantId();
        var m = merchants.requireMerchant(tenantId, merchantId);
        return InvoicingService.toResponse(invoicing.generate(m, periodStart, periodEnd));
    }

    @PostMapping("/{id}/pay")
    public Dtos.InvoiceResponse pay(@PathVariable UUID id) {
        return InvoicingService.toResponse(invoicing.markPaid(tenantContext.requireTenantId(), id));
    }
}
