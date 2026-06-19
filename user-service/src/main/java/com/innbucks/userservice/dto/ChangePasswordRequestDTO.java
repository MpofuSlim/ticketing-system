package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "ChangePasswordRequest",
        description = "Payload for a logged-in user to rotate their password. The caller must " +
                      "supply both the current password (for re-authentication) and the new password. " +
                      "Used by system users to replace the one-time temporary password they were " +
                      "issued at onboarding (delivered to them over email/SMS).")
public class ChangePasswordRequestDTO {

    @NotBlank(message = "currentPassword is required")
    @Schema(example = "Kp7r-Qn4m-Tx9j", description = "The user's existing password (e.g. the temporary password from their onboarding notification).")
    private String currentPassword;

    @NotBlank(message = "newPassword is required")
    // max 72: BCrypt only consumes the first 72 bytes, so a longer value would
    // be silently truncated AND an uncapped field lets a multi-megabyte password
    // burn CPU in the hash (a cheap DoS). Capping the SET path is safe — login /
    // currentPassword stay uncapped so anyone who set a long password before this
    // can still authenticate.
    @Size(min = 8, max = 72, message = "newPassword must be 8 to 72 characters")
    @Schema(example = "MyNewPass!2026", description = "The replacement password. 8 to 72 characters.")
    private String newPassword;
}
