package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "ChangePasswordRequest",
        description = "Payload for a logged-in user to rotate their password. The caller must " +
                      "supply both the current password (for re-authentication) and the new password. " +
                      "Used by shop staff to replace the default password #Pass123 stamped at onboarding.")
public class ChangePasswordRequestDTO {

    @NotBlank(message = "currentPassword is required")
    @Schema(example = "#Pass123", description = "The user's existing password.")
    private String currentPassword;

    @NotBlank(message = "newPassword is required")
    @Size(min = 8, message = "newPassword must be at least 8 characters")
    @Schema(example = "MyNewPass!2026", description = "The replacement password. Must be at least 8 characters.")
    private String newPassword;
}
