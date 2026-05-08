package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/loyalty/tenants")
@Tag(name = "Tenants",
     description = "Top-level platform tenants — the white-label customers of the loyalty platform. " +
                   "These endpoints are operator-level: they do NOT require an X-Tenant-Id header, " +
                   "since they're how tenants are first registered.")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    @Operation(summary = "Register a new tenant",
            description = "Onboards a new tenant onto the platform. The returned `id` is what every other " +
                          "endpoint expects in the `X-Tenant-Id` header. Operator-only — no tenant header required.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Tenant created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Tenant created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Tenant created successfully",
                                      "data": {
                                        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "code": "innbucks",
                                        "name": "Innbucks",
                                        "status": "ACTIVE"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (blank code/name or duplicate code)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Validation error", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "code: must not be blank",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.TenantResponse>> create(
            @Valid @RequestBody Dtos.TenantRequest req,
            org.springframework.security.core.Authentication authentication) {
        String creatorEmail = authentication.getName();
        log.info("POST /loyalty/tenants creator={} code={}", creatorEmail, req.code());
        Dtos.TenantResponse data = tenantService.create(req, creatorEmail);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Tenant created successfully", data));
    }

    @GetMapping
    @Operation(summary = "List all tenants",
            description = "Returns every tenant on the platform. Intended for operator dashboards. " +
                          "Operator-only — no tenant header required.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Tenants returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated tenants", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Tenants retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "code": "innbucks",
                                            "name": "Innbucks",
                                            "status": "ACTIVE"
                                          },
                                          {
                                            "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                            "code": "acme",
                                            "name": "Acme Coffee",
                                            "status": "SUSPENDED"
                                          }
                                        ],
                                        "page": 0,
                                        "size": 20,
                                        "totalElements": 2,
                                        "totalPages": 1,
                                        "first": true,
                                        "last": true
                                      }
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','EVENT_ORGANIZER','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<PageResponse<Dtos.TenantResponse>>> list(@ParameterObject Pageable pageable) {
        log.info("GET /loyalty/tenants (listing all tenants)");
        PageResponse<Dtos.TenantResponse> data = PageResponse.from(tenantService.list(pageable));
        return ResponseEntity.ok(ApiResult.ok("Tenants retrieved successfully", data));
    }

    @GetMapping("/me")
    @Operation(summary = "List tenants owned by the current caller",
            description = "Returns every tenant whose ownerEmail matches the authenticated principal's email. " +
                          "Use this right after login to discover which tenant UUIDs you can pass as " +
                          "X-Tenant-Id on subsequent calls. Most users own exactly one tenant; the response " +
                          "is a list to support edge cases where one operator manages several brands.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Tenants owned by the caller",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "My tenants", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Tenants retrieved successfully",
                                      "data": [
                                        {
                                          "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "code": "ZW",
                                          "name": "Simbisa ZW",
                                          "status": "ACTIVE"
                                        }
                                      ]
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','EVENT_ORGANIZER','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<java.util.List<Dtos.TenantResponse>>> mine(
            org.springframework.security.core.Authentication authentication) {
        String ownerEmail = authentication.getName();
        log.info("GET /loyalty/tenants/me ownerEmail={}", ownerEmail);
        java.util.List<Dtos.TenantResponse> data = tenantService.findMine(ownerEmail);
        return ResponseEntity.ok(ApiResult.ok("Tenants retrieved successfully", data));
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend a tenant",
            description = "Marks the tenant as SUSPENDED. While suspended, all the tenant's customer-facing " +
                          "loyalty operations (earn, redeem, voucher issue/redeem) will be rejected. " +
                          "Use to halt activity during billing disputes or compliance reviews.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Tenant suspended",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Tenant suspended", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Tenant suspended successfully",
                                      "data": {
                                        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "code": "innbucks",
                                        "name": "Innbucks",
                                        "status": "SUSPENDED"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Tenant not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Tenant not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.TenantResponse>> suspend(@PathVariable UUID id) {
        log.info("POST /loyalty/tenants/{}/suspend", id);
        Dtos.TenantResponse data = tenantService.suspend(id);
        return ResponseEntity.ok(ApiResult.ok("Tenant suspended successfully", data));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Reactivate a suspended tenant",
            description = "Reverses /suspend by setting status back to ACTIVE. Idempotent — safe to call " +
                          "on an already-active tenant.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Tenant activated",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Tenant activated", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Tenant activated successfully",
                                      "data": {
                                        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "code": "innbucks",
                                        "name": "Innbucks",
                                        "status": "ACTIVE"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Tenant not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Tenant not found",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.TenantResponse>> activate(@PathVariable UUID id) {
        log.info("POST /loyalty/tenants/{}/activate", id);
        Dtos.TenantResponse data = tenantService.activate(id);
        return ResponseEntity.ok(ApiResult.ok("Tenant activated successfully", data));
    }

    @PostMapping("/{id}/join")
    @Operation(summary = "Join a tenant",
            description = "Adds the authenticated caller as a member of the tenant, granting them access to " +
                          "all tenant-scoped endpoints (merchants, rules, transactions, vouchers, etc.) when " +
                          "they pass this tenant's UUID via X-Tenant-Id. Idempotent — joining an already-" +
                          "joined tenant returns the existing membership without error.")
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','EVENT_ORGANIZER','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Dtos.TenantMemberResponse>> join(
            @PathVariable UUID id,
            org.springframework.security.core.Authentication authentication) {
        String email = authentication.getName();
        log.info("POST /loyalty/tenants/{}/join caller={}", id, email);
        Dtos.TenantMemberResponse data = tenantService.addMember(id, email);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Joined tenant successfully", data));
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "List tenant members",
            description = "Returns every user with membership of this tenant. Useful for showing a tenant's " +
                          "admin team. Membership is the gate for tenant-scoped actions (replaces the old " +
                          "single-owner model).")
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','EVENT_ORGANIZER','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<java.util.List<Dtos.TenantMemberResponse>>> members(
            @PathVariable UUID id) {
        log.info("GET /loyalty/tenants/{}/members", id);
        java.util.List<Dtos.TenantMemberResponse> data = tenantService.listMembers(id);
        return ResponseEntity.ok(ApiResult.ok("Members retrieved successfully", data));
    }

    @DeleteMapping("/{id}/members/me")
    @Operation(summary = "Leave a tenant",
            description = "Removes the authenticated caller from the tenant's members. Idempotent — leaving " +
                          "a tenant the caller isn't in returns 200 without error.")
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','EVENT_ORGANIZER','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<Void>> leave(
            @PathVariable UUID id,
            org.springframework.security.core.Authentication authentication) {
        String email = authentication.getName();
        log.info("DELETE /loyalty/tenants/{}/members/me caller={}", id, email);
        tenantService.removeMember(id, email);
        return ResponseEntity.ok(ApiResult.ok("Left tenant successfully", null));
    }
}
