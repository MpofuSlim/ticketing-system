package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.RedemptionService;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty")
public class TransactionController {

    private final TransactionService transactions;
    private final TransferService transferService;
    private final RedemptionService redemptionService;
    private final TenantContext tenantContext;

    public TransactionController(TransactionService transactions, TransferService transferService,
                                 RedemptionService redemptionService, TenantContext tenantContext) {
        this.transactions = transactions;
        this.transferService = transferService;
        this.redemptionService = redemptionService;
        this.tenantContext = tenantContext;
    }

    @PostMapping("/transactions")
    public Dtos.TransactionResponse post(@Valid @RequestBody Dtos.TransactionRequest req) {
        return transactions.post(tenantContext.requireTenantId(), req);
    }

    @PostMapping("/transactions/{id}/reverse")
    public Dtos.TransactionResponse reverse(@PathVariable UUID id,
                                            @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return transactions.reverse(tenantContext.requireTenantId(), id, reason);
    }

    @PostMapping("/transactions/adjust")
    public Dtos.TransactionResponse adjust(@RequestBody Map<String, Object> body) {
        UUID userId = UUID.fromString(String.valueOf(body.get("userId")));
        UUID merchantId = UUID.fromString(String.valueOf(body.get("merchantId")));
        BigDecimal points = new BigDecimal(String.valueOf(body.get("points")));
        String reason = (String) body.get("reason");
        return transactions.adjust(tenantContext.requireTenantId(), userId, merchantId, points, reason);
    }

    @GetMapping("/users/{id}/transactions")
    public List<Dtos.TransactionResponse> recent(@PathVariable UUID id) {
        return transactions.recentForUser(id);
    }

    @PostMapping("/transfer")
    public Map<String, Object> transfer(@Valid @RequestBody Dtos.TransferRequest req) {
        BigDecimal balance = transferService.transfer(tenantContext.requireTenantId(), req);
        return Map.of("status", "OK", "newSenderBalance", balance);
    }

    @PostMapping("/redeem")
    public Map<String, Object> redeem(@Valid @RequestBody Dtos.RedemptionRequest req) {
        BigDecimal balance = redemptionService.redeemPoints(tenantContext.requireTenantId(), req);
        return Map.of("status", "OK", "newBalance", balance);
    }

    @PostMapping("/convert-to-airtime")
    public Map<String, Object> convertToAirtime() {
        return Map.of(
                "status", "NOT_ENABLED",
                "message", "M-Pesa / airtime conversion is not enabled in this build."
        );
    }
}
