package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.TenantProfileDTO;
import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin
@RequestMapping("/tenant")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Tenant", description = "Tenant-specific profile endpoints.")
public class TenantController {

    private final TenantService tenantService;

    @GetMapping("/profile")
    @Operation(summary = "Get tenant profile", description = "Returns the profile of the authenticated TENANT user.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated user is not a TENANT")
    })
    public ResponseEntity<ApiResult<TenantProfileDTO>> getProfile(Authentication authentication) {
        String email = authentication.getName();
        log.debug("GET /tenant/profile email={}", email);
        TenantProfileDTO profile = tenantService.getProfile(email);
        return ResponseEntity.ok(ApiResult.ok("Profile retrieved successfully", profile));
    }
}
