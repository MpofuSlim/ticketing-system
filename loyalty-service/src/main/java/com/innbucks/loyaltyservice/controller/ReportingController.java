package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.ReportingService;
import com.innbucks.loyaltyservice.service.SuperAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/reports")
@Tag(name = "Reporting",
     description = "Read-only dashboards and analytics. Different scopes for different audiences: " +
                   "/operator (platform-wide, no tenant header needed), /tenant (current tenant), " +
                   "/merchant/{id} (one merchant), /user/{id} (SuperApp dashboard). Plus transaction-mix, " +
                   "fraud, and CSV export endpoints.")
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
    @Operation(summary = "Operator dashboard (platform-wide)",
            description = "Cross-tenant aggregates for the platform operator: total tenants, active " +
                          "merchants, transactions/vouchers/points today, fraud attempts in last 24h, " +
                          "invoice and voucher-expiry counts. No tenant header required.")
    public ResponseEntity<ApiResult<Dtos.OperatorDashboard>> operator() {
        Dtos.OperatorDashboard data = reporting.operator();
        return ResponseEntity.ok(ApiResult.ok("Operator dashboard retrieved successfully", data));
    }

    @GetMapping("/tenant")
    @Operation(summary = "Tenant dashboard",
            description = "Per-tenant rollup for the current X-Tenant-Id: merchants, active campaigns, " +
                          "outstanding/expired vouchers, total wallet balance, pending invoices.")
    public ResponseEntity<ApiResult<Dtos.TenantDashboard>> tenant() {
        Dtos.TenantDashboard data = reporting.tenant(tenantContext.requireTenantId());
        return ResponseEntity.ok(ApiResult.ok("Tenant dashboard retrieved successfully", data));
    }

    @GetMapping("/merchant/{id}")
    @Operation(summary = "Merchant dashboard",
            description = "Per-merchant operational view: redemptions today, vouchers issued/redeemed, points " +
                          "issued/redeemed, fraud alerts in last 24h, next invoice date and estimated amount.")
    public ResponseEntity<ApiResult<Dtos.MerchantDashboard>> merchant(@PathVariable UUID id) {
        Dtos.MerchantDashboard data = reporting.merchant(id);
        return ResponseEntity.ok(ApiResult.ok("Merchant dashboard retrieved successfully", data));
    }

    @GetMapping("/user/{id}")
    @Operation(summary = "SuperApp user dashboard",
            description = "Customer-facing dashboard: total points across all wallets, the wallet list, " +
                          "active vouchers, and recent transactions. The {id} is the LoyaltyUser UUID, not " +
                          "the user-service userId.")
    public ResponseEntity<ApiResult<Dtos.UserDashboard>> user(@PathVariable UUID id) {
        Dtos.UserDashboard data = superApp.dashboard(id);
        return ResponseEntity.ok(ApiResult.ok("User dashboard retrieved successfully", data));
    }

    @GetMapping("/transactions/mix")
    @Operation(summary = "Transaction mix (counts per type)",
            description = "Returns a map of `TransactionType -> count` for the (optional) merchant within " +
                          "[from, to]. Use for revenue-mix charts. `from` and `to` are required ISO dates.")
    public ResponseEntity<ApiResult<Map<String, Long>>> transactionMix(@RequestParam(required = false) UUID merchantId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Map<String, Long> data = reporting.transactionMix(tenantContext.requireTenantId(), merchantId, from, to);
        return ResponseEntity.ok(ApiResult.ok("Transaction mix retrieved successfully", data));
    }

    @GetMapping("/fraud")
    @Operation(summary = "Recent fraud attempts",
            description = "Returns rejected redemption attempts (signature mismatch, expired, duplicate, " +
                          "wrong-merchant, blocked-user, blocked-device) for the current tenant. Used by " +
                          "compliance dashboards and to triage velocity-blocked users.")
    public ResponseEntity<ApiResult<PageResponse<Dtos.FraudAttemptResponse>>> fraud(@ParameterObject Pageable pageable) {
        PageResponse<Dtos.FraudAttemptResponse> data = PageResponse.from(
                reporting.recentFraud(tenantContext.requireTenantId()), pageable);
        return ResponseEntity.ok(ApiResult.ok("Fraud attempts retrieved successfully", data));
    }

    @GetMapping(value = "/transactions/export", produces = "text/csv")
    @Operation(summary = "Export transactions as CSV",
            description = "Streams a CSV of every transaction in [from, to], optionally scoped to a single " +
                          "merchant. Returns `Content-Disposition: attachment; filename=transactions.csv` so " +
                          "browsers download rather than render.")
    public ResponseEntity<String> exportCsv(@RequestParam(required = false) UUID merchantId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String csv = reporting.csv(tenantContext.requireTenantId(), merchantId, from, to);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                .header("Content-Disposition", "attachment; filename=\"transactions.csv\"")
                .body(csv);
    }
}
