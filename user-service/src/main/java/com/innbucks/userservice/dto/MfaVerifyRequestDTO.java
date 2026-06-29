package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Body for {@code POST /auth/login/mfa} (step 2) and
 * {@code POST /auth/mfa/enroll/complete} (post-scan). Same shape both places
 * to keep the FE wire mental-model simple.
 */
@Data
@Schema(name = "MfaVerifyRequest", description = "Step-1 mfaToken + the user-supplied code.")
public class MfaVerifyRequestDTO {

    @NotBlank(message = "mfaToken is required")
    @Schema(description = "The short-lived token returned by /auth/login (or /auth/mfa/enroll/start).")
    private String mfaToken;

    @NotBlank(message = "code is required")
    @Schema(description = "The 6-digit TOTP code from the authenticator app, OR a 4x4-grouped backup code.",
            example = "472938")
    private String code;
}
