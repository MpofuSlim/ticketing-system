package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Self-service endpoints for the authenticated caller. {@code /me} resolves
 * "who am I?" from the JWT's phoneNumber claim rather than a path variable —
 * the typical "logged-in user" use case the mini-app needs.
 *
 * <p>The wallet endpoint is the answer to "I just opened the app, what do I have?".
 * It aggregates every LoyaltyUser projection matching the caller's phone across
 * every tenant, so a customer who's accrued balances at multiple merchants
 * (potentially while still unregistered) sees them all in one place the moment
 * the promote-on-registration webhook fires.
 */
@RestController
@RequestMapping("/loyalty/users/me")
@Slf4j
@Tag(name = "Me",
     description = "Self-service endpoints for the authenticated caller — what's in my wallet, " +
                   "what vouchers can I redeem, etc. Resolves identity from the JWT phoneNumber claim.")
@SecurityRequirement(name = "bearerAuth")
public class MeController {

    private final LoyaltyUserRepository users;
    private final VoucherRepository vouchers;
    private final WalletRepository wallets;

    public MeController(LoyaltyUserRepository users,
                        VoucherRepository vouchers,
                        WalletRepository wallets) {
        this.users = users;
        this.vouchers = vouchers;
        this.wallets = wallets;
    }

    @GetMapping("/wallet")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List the caller's wallets across every tenant",
            description = "Returns one entry per tenant the caller's phone has a LoyaltyUser projection in. " +
                          "Each entry shows status, points balance, and active voucher count. " +
                          "PENDING entries are visible too — they're the balances waiting for the customer " +
                          "to complete registration. Once user-service fires the promote webhook those " +
                          "flip to ACTIVE and become spendable. " +
                          "Requires a JWT carrying a phoneNumber claim (CUSTOMER tokens always do)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Wallet entries retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Wallet retrieved",
                                      "data": {
                                        "phoneNumber": "+263771234567",
                                        "entries": [
                                          {
                                            "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "loyaltyUserId": "11111111-2222-3333-4444-555555555555",
                                            "status": "ACTIVE",
                                            "pointsBalance": 175.00,
                                            "activeVoucherCount": 2
                                          },
                                          {
                                            "tenantId": "c8d8fc3b-9869-4fd3-9dee-377cd74c0b22",
                                            "loyaltyUserId": "99999999-1111-2222-3333-444444444444",
                                            "status": "PENDING",
                                            "pointsBalance": 50.00,
                                            "activeVoucherCount": 1
                                          }
                                        ]
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "JWT has no phoneNumber claim",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "/me endpoints require a JWT with a phoneNumber claim",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<MeWalletResponse>> wallet() {
        String phone = CallerDetails.currentPhoneNumber();
        if (phone == null || phone.isBlank()) {
            throw LoyaltyException.badRequest("NO_PHONE_CLAIM",
                    "/me endpoints require a JWT with a phoneNumber claim");
        }
        List<LoyaltyUser> projections = users.findByPhoneNumber(phone);
        if (projections.isEmpty()) {
            return ResponseEntity.ok(ApiResult.ok("Wallet retrieved",
                    new MeWalletResponse(phone, List.of())));
        }
        // Two aggregate queries instead of 2N round trips. For a customer with
        // projections in N tenants we used to do N balance lookups + N voucher
        // lookups; now it's a single SUM-by-userId and a single COUNT-by-userId.
        List<UUID> userIds = projections.stream().map(LoyaltyUser::getId).toList();
        Map<UUID, BigDecimal> balances = new HashMap<>();
        for (Object[] row : wallets.sumBalanceGroupedByUserId(userIds)) {
            balances.put((UUID) row[0], (BigDecimal) row[1]);
        }
        Map<UUID, Long> activeVoucherCounts = new HashMap<>();
        for (Object[] row : vouchers.countActiveGroupedByUserId(userIds)) {
            activeVoucherCounts.put((UUID) row[0], (Long) row[1]);
        }
        List<TenantWalletEntry> entries = new ArrayList<>(projections.size());
        for (LoyaltyUser u : projections) {
            BigDecimal balance = balances.getOrDefault(u.getId(), BigDecimal.ZERO);
            int activeVouchers = activeVoucherCounts.getOrDefault(u.getId(), 0L).intValue();
            entries.add(new TenantWalletEntry(u.getTenantId(), u.getId(), u.getStatus().name(),
                    balance, activeVouchers));
        }
        return ResponseEntity.ok(ApiResult.ok("Wallet retrieved",
                new MeWalletResponse(phone, entries)));
    }

    public record MeWalletResponse(String phoneNumber, List<TenantWalletEntry> entries) {}

    public record TenantWalletEntry(UUID tenantId, UUID loyaltyUserId, String status,
                                    BigDecimal pointsBalance, int activeVoucherCount) {}
}
