package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.BindLoyaltyMerchantDTO;
import com.innbucks.userservice.dto.UpdateActiveStatusDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin - User Management", description = "SUPER_ADMIN endpoints for managing user accounts.")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {

    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
            summary = "List users filtered by active status",
            description = "Returns all user accounts. " +
                    "Pass `?active=true` for approved/active accounts, `?active=false` for pending/inactive accounts. " +
                    "Omit the parameter to return all users regardless of status. " +
                    "Requires **SUPER_ADMIN** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Users retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Users retrieved",
                                      "data": [
                                        {
                                          "id": 1,
                                          "firstName": "Alice",
                                          "lastName": "Moyo",
                                          "email": "alice@innbucks.co.zw",
                                          "phoneNumber": "+263771234567",
                                          "roles": ["EVENT_ORGANIZER"],
                                          "defaultServices": ["ticketing"],
                                          "active": false,
                                          "createdAt": "2026-01-15T10:30:00"
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<List<UserResponseDTO>>> listUsers(
            @RequestParam(name = "active", required = false) Boolean active) {

        List<User> users = (active != null)
                ? userRepository.findByActive(active)
                : userRepository.findAll();

        List<UserResponseDTO> body = users.stream()
                .map(UserResponseDTO::from)
                .collect(Collectors.toList());

        String msg = active == null ? "Users retrieved"
                : (active ? "Active users retrieved" : "Inactive users retrieved");
        log.info("{} count={}", msg, body.size());
        return ResponseEntity.ok(ApiResult.ok(msg, body));
    }

    @PutMapping("/{id}/active")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
            summary = "Activate or deactivate a user",
            description = "Sets the `active` flag on the specified user account. " +
                    "Only an active user can log in. " +
                    "Requires **SUPER_ADMIN** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Active status updated",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "User activated",
                                      "data": {
                                        "id": 1,
                                        "firstName": "Alice",
                                        "lastName": "Moyo",
                                        "email": "alice@innbucks.co.zw",
                                        "roles": ["EVENT_ORGANIZER"],
                                        "active": true,
                                        "createdAt": "2026-01-15T10:30:00"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<UserResponseDTO>> updateActiveStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateActiveStatusDTO request) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));

        user.setActive(request.getActive());
        userRepository.save(user);

        String action = request.getActive() ? "activated" : "deactivated";
        log.info("User {} userId={}", action, id);

        return ResponseEntity.ok(ApiResult.ok("User " + action, UserResponseDTO.from(user)));
    }

    @PutMapping("/{id}/loyalty-merchant")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
            summary = "Bind a MERCHANT_ADMIN profile to a loyalty merchant",
            description = "Links the user's TenantProfile to the given loyalty-service merchantId. " +
                    "Once bound, subsequent logins receive a JWT carrying the `merchantId` claim, " +
                    "which loyalty endpoints use to scope writes (e.g. shop creation, rule overrides). " +
                    "The user must be a MERCHANT_ADMIN and **must re-login** for the change to take effect."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Merchant binding updated",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Loyalty merchant bound",
                                      "data": {
                                        "userId": 1,
                                        "loyaltyMerchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "User or tenant profile not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "User is not a MERCHANT_ADMIN"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller is not a SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<java.util.Map<String, Object>>> bindLoyaltyMerchant(
            @PathVariable Long id,
            @Valid @RequestBody BindLoyaltyMerchantDTO request) {

        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        if (!user.hasRole(User.Role.MERCHANT_ADMIN)) {
            throw new RuntimeException("User is not a MERCHANT_ADMIN");
        }

        TenantProfile profile = tenantProfileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new RuntimeException("Tenant profile not found for user: " + id));
        profile.setLoyaltyMerchantId(request.getMerchantId());
        tenantProfileRepository.save(profile);

        log.info("Loyalty merchant bound userId={} merchantId={}", id, request.getMerchantId());
        return ResponseEntity.ok(ApiResult.ok("Loyalty merchant bound",
                java.util.Map.of("userId", id, "loyaltyMerchantId", request.getMerchantId())));
    }
}
