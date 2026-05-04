package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.QrService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/loyalty/qr")
@Tag(name = "QR",
     description = "Signed QR tokens for in-person flows: a merchant generates a token at the till; the " +
                   "customer scans it with the SuperApp, which calls /consume to award points or complete " +
                   "a transfer. Tokens are HMAC-signed (separate `loyalty.qr.secret`), single-use, and " +
                   "TTL-bounded. Requires X-Tenant-Id.")
public class QrController {

    private final QrService qrService;
    private final TenantContext tenantContext;

    public QrController(QrService qrService, TenantContext tenantContext) {
        this.qrService = qrService;
        this.tenantContext = tenantContext;
    }

    @PostMapping("/issue")
    @Operation(summary = "Issue a signed QR token",
            description = "Generates a token + signature for a specific source (MERCHANT or USER), bound to " +
                          "a transaction type (e.g. QR_PAY) and an amount. The merchant POS or sender app " +
                          "renders this as a QR code. `ttlSeconds` overrides the default TTL configured " +
                          "via `loyalty.qr.ttl-seconds`.")
    public Dtos.QrPayload issue(@Valid @RequestBody Dtos.QrIssueRequest req) {
        return qrService.issue(tenantContext.requireTenantId(), req);
    }

    @PostMapping("/consume")
    @Operation(summary = "Consume a QR token",
            description = "Verifies the token signature + expiry + single-use flag, then posts the underlying " +
                          "transaction (earn or transfer) on behalf of the scanning user. Reusing the same " +
                          "token returns 4xx — the token is marked CONSUMED on first success.")
    public Dtos.TransactionResponse consume(@Valid @RequestBody Dtos.QrConsumeRequest req) {
        return qrService.consume(tenantContext.requireTenantId(), req);
    }
}
