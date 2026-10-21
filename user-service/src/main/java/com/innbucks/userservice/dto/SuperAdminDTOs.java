package com.innbucks.userservice.dto;

import com.innbucks.userservice.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** Response shapes for {@code /admin/super-admins} (V52: more than one SUPER_ADMIN). */
public final class SuperAdminDTOs {

    private SuperAdminDTOs() {}

    @Schema(name = "SuperAdminView",
            description = "One SUPER_ADMIN account: the seeded platform owner (platformOwner: true) or a deputy.")
    public record SuperAdminView(
            @Schema(example = "4812", description = "users.id — the {userId} the promote and demote paths take.")
            Long userId,
            @Schema(example = "8f3c2b1e-6a4d-4e0f-9b7a-2d5c1e9f0a11")
            UUID userUuid,
            @Schema(example = "Tariro")
            String firstName,
            @Schema(example = "Moyo")
            String lastName,
            @Schema(example = "tariro.moyo@innbucks.co.zw")
            String email,
            @Schema(description = "True for the seeded platform owner only — the one account that can promote and "
                    + "demote, and that nobody can act on.")
            boolean platformOwner,
            @Schema(description = "Whether SUPER_ADMIN is held now. False only in a demotion's response.")
            boolean superAdmin,
            boolean active,
            @Schema(description = "Two-step verification is set up.")
            boolean mfaEnabled,
            @Schema(example = "[\"CALL_CENTER_SUPERVISOR\", \"SUPER_ADMIN\"]",
                    description = "Every role the account holds, sorted. A deputy keeps the staff roles it had.")
            List<String> roles) {

        public static SuperAdminView of(User user) {
            return new SuperAdminView(user.getId(), user.getUserUuid(), user.getFirstName(), user.getLastName(),
                    user.getEmail(), user.isPlatformOwner(), user.hasRole(User.Role.SUPER_ADMIN), user.isActive(),
                    user.isMfaEnabled(), user.getRoles() == null ? List.of() : user.getRoles().stream().sorted().toList());
        }
    }
}
