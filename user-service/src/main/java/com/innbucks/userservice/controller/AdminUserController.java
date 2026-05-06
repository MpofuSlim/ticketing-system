package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.UpdateActiveStatusDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin: Users", description = "SUPER_ADMIN-only endpoints for approving/managing user accounts.")
public class AdminUserController {

    private final UserRepository userRepository;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "List users filtered by active status",
            description = "Returns all user accounts. Pass `?active=true` for approved/active accounts, " +
                    "`?active=false` for pending/inactive accounts. Omit the parameter to return all users " +
                    "regardless of status. Requires SUPER_ADMIN role.")
    public ResponseEntity<ApiResult<List<UserResponseDTO>>> listUsers(
            @RequestParam(value = "active", required = false) Boolean active) {
        log.debug("GET /admin/users active={}", active);
        List<User> users = (active == null)
                ? userRepository.findAll()
                : userRepository.findByActive(active);
        List<UserResponseDTO> response = users.stream().map(this::toDTO).toList();
        return ResponseEntity.ok(ApiResult.ok("Users retrieved", response));
    }

    @PutMapping("/{id}/active")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    @Operation(summary = "Activate or deactivate a user",
            description = "Sets the `active` flag on a user. Active users can log in; inactive users " +
                    "cannot. Requires SUPER_ADMIN role.")
    public ResponseEntity<ApiResult<UserResponseDTO>> updateActive(
            @PathVariable Long id,
            @Valid @RequestBody UpdateActiveStatusDTO body) {
        log.info("PUT /admin/users/{}/active active={}", id, body.getActive());
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        user.setActive(body.getActive());
        User saved = userRepository.save(user);
        String msg = saved.isActive() ? "User activated" : "User deactivated";
        return ResponseEntity.ok(ApiResult.ok(msg, toDTO(saved)));
    }

    private UserResponseDTO toDTO(User user) {
        return UserResponseDTO.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .middleName(user.getMiddleName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .role(user.getRole() == null ? null : user.getRole().name())
                .active(user.isActive())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
