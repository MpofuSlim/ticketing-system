package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    public ResponseEntity<Dtos.TenantResponse> create(@Valid @RequestBody Dtos.TenantRequest req) {
        return ResponseEntity.ok(tenantService.create(req));
    }

    @GetMapping
    public List<Dtos.TenantResponse> list() {
        return tenantService.list();
    }

    @PostMapping("/{id}/suspend")
    public Dtos.TenantResponse suspend(@PathVariable UUID id) {
        return tenantService.suspend(id);
    }

    @PostMapping("/{id}/activate")
    public Dtos.TenantResponse activate(@PathVariable UUID id) {
        return tenantService.activate(id);
    }
}
