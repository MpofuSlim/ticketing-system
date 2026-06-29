package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Body for {@code POST /auth/mfa/disable}. Requires a fresh TOTP/backup code so
 * a stolen JWT alone can't turn MFA off.
 */
@Data
@Schema(name = "MfaDisableRequest",
        description = "Disable MFA on the caller's account. Caller is the JWT subject.")
public class MfaDisableRequestDTO {

    @NotBlank(message = "code is required")
    @Schema(description = "A fresh 6-digit TOTP code or an unused backup code.", example = "472938")
    private String code;
}
