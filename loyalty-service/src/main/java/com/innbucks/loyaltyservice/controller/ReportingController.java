package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.ReportingService;
import com.innbucks.loyaltyservice.service.SuperAppService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/reports")
public class ReportingController {

    private final ReportingService reporting;
    private final SuperAppService superApp;
    private final TenantContext tenantContext;

    public ReportingController(ReportingService reporting, SuperAppService superApp,
                               TenantContext tenantContext) {
        this.reporting = reporting;
        this.superApp = superApp;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/operator")
    public Dtos.OperatorDashboard operator() {
        return reporting.operator();
    }

    @GetMapping("/tenant")
    public Dtos.TenantDashboard tenant() {
        return reporting.tenant(tenantContext.requireTenantId());
    }

    @GetMapping("/merchant/{id}")
    public Dtos.MerchantDashboard merchant(@PathVariable UUID id) {
        return reporting.merchant(id);
    }

    @GetMapping("/user/{id}")
    public Dtos.UserDashboard user(@PathVariable UUID id) {
        return superApp.dashboard(id);
    }

    @GetMapping("/transactions/mix")
    public Map<String, Long> transactionMix(@RequestParam(required = false) UUID merchantId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reporting.transactionMix(tenantContext.requireTenantId(), merchantId, from, to);
    }

    @GetMapping("/fraud")
    public List<Dtos.FraudAttemptResponse> fraud() {
        return reporting.recentFraud(tenantContext.requireTenantId());
    }

    @GetMapping(value = "/transactions/export", produces = "text/csv")
    public ResponseEntity<String> exportCsv(@RequestParam(required = false) UUID merchantId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String csv = reporting.csv(tenantContext.requireTenantId(), merchantId, from, to);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                .header("Content-Disposition", "attachment; filename=\"transactions.csv\"")
                .body(csv);
    }
}
