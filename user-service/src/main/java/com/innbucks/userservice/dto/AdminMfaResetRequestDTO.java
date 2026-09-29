package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Optional body for {@code POST /admin/users/{id}/mfa/reset}.
 *
 * <p>The note is optional and goes only onto the {@code MFA_ADMIN_RESET} audit
 * row — it is never sent to the user. It is what lets a reviewer tell a
 * verified "lost my phone" call from a reset nobody can account for.
 */
@Data
@Schema(name = "AdminMfaReset",
        description = "Optional reason for resetting a user's two-step verification. Recorded on the audit "
                + "trail only; never shown to the user.")
public class AdminMfaResetRequestDTO {

    @Size(max = 500, message = "note must be 500 characters or fewer")
    @Schema(example = "Lost phone and printed backup codes; identity confirmed by callback on the number on file.",
            description = "Why the reset was done. Optional, at most 500 characters.")
    private String note;
}
