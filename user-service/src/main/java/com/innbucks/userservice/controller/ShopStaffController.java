package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.BulkShopUserResultDTO;
import com.innbucks.userservice.dto.CreateShopAdminDTO;
import com.innbucks.userservice.dto.CreateShopUserDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.service.ShopStaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
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
                          "(taken from the caller's TenantProfile, not the request body). The new " +
                          "user is created with a randomly-generated one-time temporary password, " +
                          "delivered to them over email/SMS — they must rotate it via " +
                          "POST /auth/change-password on first login. " +
                          "Requires **MERCHANT_ADMIN** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "Shop admin created",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Shop admin created",
                                      "data": {
                                        "id": 73,
                                        "firstName": "Tendai",
                                        "middleName": "M",
                                        "lastName": "Moyo",
                                        "email": "tendai@pizza-avondale.co.zw",
                                        "phoneNumber": "+263771234567",
                                        "roles": ["SHOP_ADMIN"],
                                        "defaultServices": ["loyalty"],
                                        "active": true,
                                        "createdAt": "2026-05-11T10:15:00",
                                        "loyaltyMerchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "loyaltyShopId": "11111111-aaaa-bbbb-cccc-222222222222"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Validation failed, email/phone already registered, or shop not found",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Shop not found in loyalty-service",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Shop belongs to a different merchant, or caller is not a MERCHANT_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Shop does not belong to your merchant",
                                      "data": null
                                    }
                                    """)))
    })
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
                          "shop. The new user is created with a randomly-generated one-time temporary " +
                          "password, delivered to them over email/SMS — they must rotate it via " +
                          "POST /auth/change-password on first login. " +
                          "Requires **SHOP_ADMIN** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "Shop user created",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Shop user created",
                                      "data": {
                                        "id": 74,
                                        "firstName": "Rufaro",
                                        "middleName": "T",
                                        "lastName": "Ncube",
                                        "email": "rufaro@pizza-avondale.co.zw",
                                        "phoneNumber": "+263772345678",
                                        "roles": ["SHOP_USER"],
                                        "defaultServices": ["loyalty"],
                                        "active": true,
                                        "createdAt": "2026-05-11T10:20:00",
                                        "loyaltyMerchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "loyaltyShopId": "11111111-aaaa-bbbb-cccc-222222222222"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Validation failed, email/phone already registered, or caller has no shop scope",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Email already registered",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Caller is not a SHOP_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Forbidden",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<UserResponseDTO>> createShopUser(@Valid @RequestBody CreateShopUserDTO req) {
        UserResponseDTO data = shopStaffService.createShopUser(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Shop user created", data));
    }

    @PostMapping(value = "/users/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('SHOP_ADMIN')")
    @Operation(
            summary = "Bulk-onboard SHOP_USERs from a CSV",
            description = "Batch version of `POST /admin/shop-staff/users`. Upload a CSV (multipart field " +
                          "`file`) with a header row and one SHOP_USER per line; every user is created at " +
                          "the caller's own shop (there is no per-row shopId, exactly like the single " +
                          "endpoint). Columns: `firstName,middleName,lastName,email,phoneNumber` — " +
                          "middleName optional; column order, casing and spacing are tolerated. " +
                          "Fetch a starter file from `GET /admin/shop-staff/users/bulk/template`.\n\n" +
                          "The import is **best-effort per row**: each row is created in its own " +
                          "transaction, so a duplicate email or invalid phone fails on its own and every " +
                          "good row still lands. The response reports each row's outcome (by file line) so " +
                          "you can fix and re-upload only the failures. Every created user gets a one-time " +
                          "temporary password delivered over email/SMS, exactly as the single-create path " +
                          "does. Max 1000 rows per upload. Requires **SHOP_ADMIN** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Import processed — see per-row results (some rows may have failed)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Bulk import processed",
                                      "data": {
                                        "total": 3,
                                        "created": 2,
                                        "failed": 1,
                                        "results": [
                                          { "line": 2, "email": "rufaro@pizza-avondale.co.zw", "status": "CREATED", "error": null },
                                          { "line": 3, "email": "tanaka@pizza-avondale.co.zw", "status": "CREATED", "error": null },
                                          { "line": 4, "email": "rufaro@pizza-avondale.co.zw", "status": "FAILED", "error": "Email already registered" }
                                        ]
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Empty file, missing/blank header, no data rows, wrong columns, over the row limit, or the caller's SHOP_ADMIN account has no shop scope",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "CSV header must contain the columns: firstName, lastName, email, phoneNumber (middleName optional).",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Caller is not a SHOP_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "Only SHOP_ADMIN can create shop users", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<BulkShopUserResultDTO>> bulkCreateShopUsers(
            @RequestParam("file")
            @Schema(type = "string", format = "binary", description = "CSV file of shop users")
            MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file uploaded, or the file is empty.");
        }
        String csv;
        try {
            csv = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file.");
        }
        BulkShopUserResultDTO data = shopStaffService.bulkImportShopUsersCsv(csv);
        return ResponseEntity.ok(ApiResult.ok("Bulk import processed", data));
    }

    @GetMapping(value = "/users/bulk/template", produces = "text/csv")
    @PreAuthorize("hasAnyRole('SHOP_ADMIN','MERCHANT_ADMIN')")
    @Operation(
            summary = "Download the bulk-import CSV template",
            description = "Returns a ready-to-fill CSV with the exact header the bulk import expects plus " +
                          "one example row, so the operator's file matches the parser. Serve it behind a " +
                          "'Download template' button on the bulk-upload screen."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "CSV template",
                    content = @Content(mediaType = "text/csv",
                            examples = @ExampleObject(value =
                                    "firstName,middleName,lastName,email,phoneNumber\\n"
                                  + "Rufaro,T,Ncube,rufaro@pizza-avondale.co.zw,+263772345678\\n")))
    })
    public ResponseEntity<String> bulkTemplate() {
        String template = "firstName,middleName,lastName,email,phoneNumber\n"
                + "Rufaro,T,Ncube,rufaro@pizza-avondale.co.zw,+263772345678\n";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"shop-users-template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(template);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('SHOP_ADMIN','MERCHANT_ADMIN')")
    @Operation(
            summary = "List the caller's shop staff",
            description = "Returns the shop staff the caller manages. For a **SHOP_ADMIN** this is " +
                          "every user (SHOP_ADMINs and SHOP_USERs) at their own shop. For a " +
                          "**MERCHANT_ADMIN** this is every SHOP_ADMIN and SHOP_USER across all shops " +
                          "of every merchant they administer (resolved by their admin email), so a " +
                          "merchant admin gets one 'my staff' view without needing a merchantId."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Staff retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Shop staff retrieved",
                                      "data": [
                                        {
                                          "id": 73,
                                          "firstName": "Tendai",
                                          "middleName": "M",
                                          "lastName": "Moyo",
                                          "email": "tendai@pizza-avondale.co.zw",
                                          "phoneNumber": "+263771234567",
                                          "roles": ["SHOP_ADMIN"],
                                          "defaultServices": ["loyalty"],
                                          "active": true,
                                          "createdAt": "2026-05-11T10:15:00",
                                          "loyaltyMerchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                          "loyaltyShopId": "11111111-aaaa-bbbb-cccc-222222222222"
                                        },
                                        {
                                          "id": 74,
                                          "firstName": "Rufaro",
                                          "middleName": "T",
                                          "lastName": "Ncube",
                                          "email": "rufaro@pizza-avondale.co.zw",
                                          "phoneNumber": "+263772345678",
                                          "roles": ["SHOP_USER"],
                                          "defaultServices": ["loyalty"],
                                          "active": true,
                                          "createdAt": "2026-05-11T10:20:00",
                                          "loyaltyMerchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                          "loyaltyShopId": "11111111-aaaa-bbbb-cccc-222222222222"
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Caller is not scoped to a shop",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Caller is not scoped to a shop",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Caller is not a SHOP_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Forbidden",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<List<UserResponseDTO>>> listMyShopStaff() {
        List<UserResponseDTO> data = shopStaffService.listForCallerShop();
        return ResponseEntity.ok(ApiResult.ok("Shop staff retrieved", data));
    }

    @GetMapping("/by-merchant/{merchantId}")
    @PreAuthorize("hasRole('MERCHANT_ADMIN')")
    @Operation(
            summary = "List every staff member under a merchant",
            description = "Returns every SHOP_ADMIN and SHOP_USER attached to any shop under the " +
                          "given merchant — the full headcount the MERCHANT_ADMIN oversees through " +
                          "their shop admins. E.g. as the MERCHANT_ADMIN for Zambezi, this returns " +
                          "every staff member at Zambezi Avondale, Zambezi Westgate, etc. in one " +
                          "call. Use /by-shop/{shopId} to drill into a single outlet."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Staff retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Shop staff retrieved",
                                      "data": [
                                        {
                                          "id": 73,
                                          "firstName": "Tendai",
                                          "middleName": "M",
                                          "lastName": "Moyo",
                                          "email": "tendai@pizza-avondale.co.zw",
                                          "phoneNumber": "+263771234567",
                                          "roles": ["SHOP_ADMIN"],
                                          "defaultServices": ["loyalty"],
                                          "active": true,
                                          "createdAt": "2026-05-11T10:15:00",
                                          "loyaltyMerchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                          "loyaltyShopId": "11111111-aaaa-bbbb-cccc-222222222222"
                                        },
                                        {
                                          "id": 74,
                                          "firstName": "Rufaro",
                                          "middleName": "T",
                                          "lastName": "Ncube",
                                          "email": "rufaro@pizza-avondale.co.zw",
                                          "phoneNumber": "+263772345678",
                                          "roles": ["SHOP_USER"],
                                          "defaultServices": ["loyalty"],
                                          "active": true,
                                          "createdAt": "2026-05-11T10:20:00",
                                          "loyaltyMerchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                          "loyaltyShopId": "11111111-aaaa-bbbb-cccc-222222222222"
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Caller is not a MERCHANT_ADMIN, or the merchantId belongs to a different merchant",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Merchant does not belong to your account",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<List<UserResponseDTO>>> listMerchantStaff(@PathVariable UUID merchantId) {
        List<UserResponseDTO> data = shopStaffService.listForMerchant(merchantId);
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
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Staff retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Shop staff retrieved",
                                      "data": [
                                        {
                                          "id": 73,
                                          "firstName": "Tendai",
                                          "middleName": "M",
                                          "lastName": "Moyo",
                                          "email": "tendai@pizza-avondale.co.zw",
                                          "phoneNumber": "+263771234567",
                                          "roles": ["SHOP_ADMIN"],
                                          "defaultServices": ["loyalty"],
                                          "active": true,
                                          "createdAt": "2026-05-11T10:15:00",
                                          "loyaltyMerchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                          "loyaltyShopId": "11111111-aaaa-bbbb-cccc-222222222222"
                                        },
                                        {
                                          "id": 74,
                                          "firstName": "Rufaro",
                                          "middleName": "T",
                                          "lastName": "Ncube",
                                          "email": "rufaro@pizza-avondale.co.zw",
                                          "phoneNumber": "+263772345678",
                                          "roles": ["SHOP_USER"],
                                          "defaultServices": ["loyalty"],
                                          "active": true,
                                          "createdAt": "2026-05-11T10:20:00",
                                          "loyaltyMerchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                          "loyaltyShopId": "11111111-aaaa-bbbb-cccc-222222222222"
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Shop not found in loyalty-service, or caller has no merchant binding",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Shop not found in loyalty-service",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Shop belongs to a different merchant, or caller is not a MERCHANT_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Shop does not belong to your merchant",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<List<UserResponseDTO>>> listShopStaff(@PathVariable UUID shopId) {
        List<UserResponseDTO> data = shopStaffService.listForShop(shopId);
        return ResponseEntity.ok(ApiResult.ok("Shop staff retrieved", data));
    }

    @PostMapping("/{userUuid}/reset-password")
    @PreAuthorize("hasAnyRole('SHOP_ADMIN','MERCHANT_ADMIN')")
    @Operation(
            summary = "Re-issue a shop-staff member's temporary password",
            description = "Mints a fresh temporary password for a shop-staff member (SHOP_ADMIN or " +
                          "SHOP_USER) and re-delivers it via email → SMS fallback. Use this when " +
                          "the original onboarding notification never reached them. " +
                          "Authorization is role-aware: a **SHOP_ADMIN** may reset SHOP_USERs at " +
                          "their own shop; a **MERCHANT_ADMIN** may reset both SHOP_ADMINs and " +
                          "SHOP_USERs at their own merchant. Cross-shop / cross-merchant targets " +
                          "return 404 (we never disclose staff at other scopes). " +
                          "SUPER_ADMINs use the platform-wide " +
                          "`POST /admin/users/{id}/reset-temp-password` instead. " +
                          "The response NEVER contains the password — it travels only via the " +
                          "notification channels."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Password reset and re-delivery dispatched",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Shop staff password reset",
                                      "data": {
                                        "id": 74,
                                        "userUuid": "7fa0d1c2-3456-789a-bcde-f0123456789a",
                                        "firstName": "Rufaro",
                                        "middleName": "T",
                                        "lastName": "Ncube",
                                        "email": "rufaro@pizza-avondale.co.zw",
                                        "phoneNumber": "+263772345678",
                                        "roles": ["SHOP_USER"],
                                        "defaultServices": ["loyalty"],
                                        "active": true,
                                        "createdAt": "2026-05-11T10:20:00",
                                        "loyaltyMerchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "loyaltyShopId": "11111111-aaaa-bbbb-cccc-222222222222"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "No such staff member visible to this caller",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Staff member not found", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Caller is not a SHOP_ADMIN or MERCHANT_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<UserResponseDTO>> resetShopStaffPassword(@PathVariable UUID userUuid) {
        UserResponseDTO data = shopStaffService.resetTemporaryPassword(userUuid);
        return ResponseEntity.ok(ApiResult.ok("Shop staff password reset", data));
    }
}
