package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body for {@code POST /auth/reset-password} — the forgot-password completion
 * step. Carries the OTP that proves phone ownership plus the new password,
 * entered twice. The server checks {@code newPassword} equals
 * {@code confirmPassword} before resetting.
 */
@Data
@Schema(name = "ResetPasswordRequest",
        description = "Complete a forgot-password reset: the identifier used to start it (phone OR email) "
                + "+ the OTP + the new password entered twice.")
public class ResetPasswordRequestDTO {

    @Schema(description = "Phone number the reset started with (if started by phone). Provide this OR email.",
            example = "+263771234567", nullable = true)
    private String phoneNumber;

    @Schema(description = "Email the reset started with (if started by email). Provide this OR phoneNumber.",
            example = "user@example.com", nullable = true)
    private String email;

    @NotBlank(message = "OTP code is required")
    @Pattern(regexp = "\\d{6}", message = "OTP must be 6 digits")
    @Schema(description = "The 6-digit reset code received via SMS/WhatsApp.", example = "472938")
    private String otp;

    @NotBlank(message = "newPassword is required")
    // max 72: BCrypt only consumes the first 72 bytes; capping the SET path also
    // stops a multi-megabyte value burning CPU in the hash (cheap DoS).
    @Size(min = 8, max = 72, message = "newPassword must be 8 to 72 characters")
    @Schema(description = "The replacement password. 8 to 72 characters.", example = "MyNewPass!2026")
    private String newPassword;

    @NotBlank(message = "confirmPassword is required")
    @Schema(description = "Repeat of newPassword; must match exactly.", example = "MyNewPass!2026")
    private String confirmPassword;
}
