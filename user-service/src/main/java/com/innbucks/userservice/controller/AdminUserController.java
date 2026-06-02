package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.UpdateActiveStatusDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.UserAdminService;
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
    private final UserAdminService userAdminService;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
            summary = "List system users (no customers)",
            description = "Returns user accounts for the SUPER_ADMIN portal: every role **except CUSTOMER** " +
                    "by default. Customers are the wallet-holding end-users of the super-app and are managed " +
                    "via the customer-facing surface (`/auth/customer/**`), not the admin portal — listing " +
                    "them here would drown the page in millions of rows.\n\n" +
                    "Pass `?active=true` for approved/active accounts, `?active=false` for pending/inactive " +
                    "accounts. Omit to return all status values.\n\n" +
                    "Pass `?includeCustomers=true` to opt back in to the full population (e.g. for support " +
                    "triage). Defaults to `false`. Requires **SUPER_ADMIN** role."
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
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "includeCustomers", required = false, defaultValue = "false") boolean includeCustomers) {

        List<User> users;
        if (includeCustomers) {
            users = (active != null) ? userRepository.findByActive(active) : userRepository.findAll();
        } else {
            users = (active != null)
                    ? userRepository.findByActiveExcludingRole(active, User.Role.CUSTOMER)
                    : userRepository.findAllExcludingRole(User.Role.CUSTOMER);
        }

        List<UserResponseDTO> body = users.stream()
                .map(UserResponseDTO::from)
                .collect(Collectors.toList());

        String activeLabel = active == null ? "Users"
                : (active ? "Active users" : "Inactive users");
        String scopeLabel = includeCustomers ? "" : " (system, no customers)";
        String msg = activeLabel + scopeLabel + " retrieved";
        log.info("{} count={}", msg, body.size());
        return ResponseEntity.ok(ApiResult.ok(msg, body));
    }

    @GetMapping("/merchants")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
            summary = "List merchant admins and event organizers",
            description = "Returns user accounts carrying the **MERCHANT_ADMIN** role (people who " +
                    "own/administer a merchant on the platform) **or** the **EVENT_ORGANIZER** role " +
                    "(people who run ticketed events) — the two top-level business roles. A user " +
                    "enrolled in both bundles holds both roles and appears once. SHOP_ADMIN / " +
                    "SHOP_USER staff are scoped to a single shop and are not included here; use " +
                    "`GET /admin/users` for the full system-user listing.\n\n" +
                    "Pass `?active=true` for approved/active accounts, `?active=false` for " +
                    "pending/inactive ones. Omit to return all status values. Requires **SUPER_ADMIN** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Merchant admins and event organizers retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Merchant admins & event organizers retrieved",
                                      "data": [
                                        {
                                          "id": 7,
                                          "firstName": "Tendai",
                                          "lastName": "Ncube",
                                          "email": "tendai@acme-merch.co.zw",
                                          "phoneNumber": "+263772345678",
                                          "roles": ["MERCHANT_ADMIN"],
                                          "defaultServices": ["loyalty"],
                                          "active": true,
                                          "createdAt": "2026-02-10T09:15:00"
                                        },
                                        {
                                          "id": 9,
                                          "firstName": "Rumbi",
                                          "lastName": "Moyo",
                                          "email": "rumbi@showtime.co.zw",
                                          "phoneNumber": "+263772999000",
                                          "roles": ["EVENT_ORGANIZER"],
                                          "defaultServices": ["ticketing"],
                                          "active": true,
                                          "createdAt": "2026-02-12T11:30:00"
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<List<UserResponseDTO>>> listMerchants(
            @RequestParam(name = "active", required = false) Boolean active) {

        var businessRoles = java.util.EnumSet.of(
                User.Role.MERCHANT_ADMIN, User.Role.EVENT_ORGANIZER);

        List<User> users = (active != null)
                ? userRepository.findByActiveAndAnyRole(active, businessRoles)
                : userRepository.findByAnyRole(businessRoles);

        List<UserResponseDTO> body = users.stream()
                .map(UserResponseDTO::from)
                .collect(Collectors.toList());

        String msg = (active == null ? "Merchant admins & event organizers"
                : (active ? "Active merchant admins & event organizers"
                          : "Inactive merchant admins & event organizers"))
                + " retrieved";
        log.info("GET /admin/users/merchants -> {} count={}", msg, body.size());
        return ResponseEntity.ok(ApiResult.ok(msg, body));
    }

    @PutMapping("/{id}/active")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
            summary = "Activate or deactivate a user",
            description = "Sets the `active` flag on the specified user account. Only an active user can log in. " +
                    "**The first activation of a newly-registered system user is its approval**: the account is " +
                    "assigned the default password `#Pass123` and flagged to change it on first login. Subsequent " +
                    "deactivate/reactivate toggles never reset the password. Requires **SUPER_ADMIN** role."
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

        User user = userAdminService.setActive(id, request.getActive());

        String action = request.getActive() ? "activated" : "deactivated";
        return ResponseEntity.ok(ApiResult.ok("User " + action, UserResponseDTO.from(user)));
    }
}
