package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.CreateShopAdminDTO;
import com.innbucks.userservice.dto.CreateShopUserDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.service.ShopStaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Onboarding endpoints for shop staff. Both creation paths live in user-service
 * (identity is owned here), but the shop they're attached to is a loyalty-service
 * entity referenced by UUID.
 */
@RestController
@RequestMapping("/admin/shop-staff")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Shop Staff",
     description = "Onboard SHOP_ADMINs (by a MERCHANT_ADMIN) and SHOP_USERs (by a SHOP_ADMIN). " +
                   "Hierarchy: Tenant -> Merchant -> Shop -> staff. Each shop staff member is " +
                   "stamped with loyaltyShopId and loyaltyMerchantId so their JWT carries both " +
                   "claims at every login.")
@SecurityRequirement(name = "bearerAuth")
public class ShopStaffController {

    private final ShopStaffService shopStaffService;

    @PostMapping("/admins")
    @PreAuthorize("hasRole('MERCHANT_ADMIN')")
    @Operation(
            summary = "Onboard a SHOP_ADMIN under one of your shops",
            description = "Creates a new SHOP_ADMIN user attached to the supplied shopId. The shop " +
                          "is resolved via loyalty-service and must belong to the caller's merchant " +
                          "(taken from the caller's TenantProfile, not the request body). " +
                          "Requires **MERCHANT_ADMIN** role."
    )
    public ResponseEntity<ApiResult<UserResponseDTO>> createShopAdmin(@Valid @RequestBody CreateShopAdminDTO req) {
        UserResponseDTO data = shopStaffService.createShopAdmin(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Shop admin created", data));
    }

    @PostMapping("/users")
    @PreAuthorize("hasRole('SHOP_ADMIN')")
    @Operation(
            summary = "Onboard a SHOP_USER at your shop",
            description = "Creates a new SHOP_USER (cashier / operator) attached to the caller's " +
                          "shop. The new user's loyaltyShopId and loyaltyMerchantId are copied from " +
                          "the caller's User row — a SHOP_ADMIN can only create staff for their own " +
                          "shop. Requires **SHOP_ADMIN** role."
    )
    public ResponseEntity<ApiResult<UserResponseDTO>> createShopUser(@Valid @RequestBody CreateShopUserDTO req) {
        UserResponseDTO data = shopStaffService.createShopUser(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Shop user created", data));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('SHOP_ADMIN')")
    @Operation(
            summary = "List staff at the caller's shop",
            description = "Returns every user (SHOP_ADMINs and SHOP_USERs) attached to the caller's " +
                          "shop. SHOP_ADMINs use this to see who they manage."
    )
    public ResponseEntity<ApiResult<List<UserResponseDTO>>> listMyShopStaff() {
        List<UserResponseDTO> data = shopStaffService.listForCallerShop();
        return ResponseEntity.ok(ApiResult.ok("Shop staff retrieved", data));
    }

    @GetMapping("/by-shop/{shopId}")
    @PreAuthorize("hasRole('MERCHANT_ADMIN')")
    @Operation(
            summary = "List staff at a given shop",
            description = "Returns every user attached to the supplied shop. The shop must belong to " +
                          "the caller's merchant. MERCHANT_ADMINs use this to audit who's on each " +
                          "shop floor."
    )
    public ResponseEntity<ApiResult<List<UserResponseDTO>>> listShopStaff(@PathVariable UUID shopId) {
        List<UserResponseDTO> data = shopStaffService.listForShop(shopId);
        return ResponseEntity.ok(ApiResult.ok("Shop staff retrieved", data));
    }
}
