package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/users")
public class UserController {

    private final UserService users;
    private final WalletService wallets;
    private final TenantContext tenantContext;

    public UserController(UserService users, WalletService wallets, TenantContext tenantContext) {
        this.users = users;
        this.wallets = wallets;
        this.tenantContext = tenantContext;
    }

    @PostMapping
    public Dtos.UserResponse create(@Valid @RequestBody Dtos.UserRequest req) {
        return users.create(tenantContext.requireTenantId(), req);
    }

    @GetMapping("/{id}")
    public Dtos.UserResponse get(@PathVariable UUID id) {
        return users.get(tenantContext.requireTenantId(), id);
    }

    @PostMapping("/{id}/deactivate")
    public Dtos.UserResponse deactivate(@PathVariable UUID id) {
        return users.deactivate(tenantContext.requireTenantId(), id);
    }

    @PostMapping("/{id}/block")
    public Dtos.UserResponse block(@PathVariable UUID id) {
        return users.block(tenantContext.requireTenantId(), id);
    }

    @GetMapping("/{id}/wallets")
    public List<Dtos.WalletResponse> wallets(@PathVariable UUID id) {
        users.require(tenantContext.requireTenantId(), id);
        return wallets.listForUser(id);
    }

    @PostMapping("/{id}/wallets")
    public Dtos.WalletResponse createSubWallet(@PathVariable UUID id,
                                               @Valid @RequestBody Dtos.SubWalletRequest req) {
        UUID tenantId = tenantContext.requireTenantId();
        users.require(tenantId, id);
        return wallets.createSubWallet(tenantId, id, req);
    }
}
