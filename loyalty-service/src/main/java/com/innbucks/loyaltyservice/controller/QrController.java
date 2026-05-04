package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.QrService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/loyalty/qr")
public class QrController {

    private final QrService qrService;
    private final TenantContext tenantContext;

    public QrController(QrService qrService, TenantContext tenantContext) {
        this.qrService = qrService;
        this.tenantContext = tenantContext;
    }

    @PostMapping("/issue")
    public Dtos.QrPayload issue(@Valid @RequestBody Dtos.QrIssueRequest req) {
        return qrService.issue(tenantContext.requireTenantId(), req);
    }

    @PostMapping("/consume")
    public Dtos.TransactionResponse consume(@Valid @RequestBody Dtos.QrConsumeRequest req) {
        return qrService.consume(tenantContext.requireTenantId(), req);
    }
}
