package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(
        name = "LoginRequest",
        description = "Login payload. Supply **either** `email` **or** `phoneNumber` (not both required) " +
                "together with `password`. Customers registered at tier 1 typically log in with `phoneNumber`. " +
                "System users (TENANT, ADMIN, etc.) log in with `email` and must also supply `otpCode` since MFA is enabled."
)
public class LoginRequestDTO {

    @Schema(
            description = "Email address. Optional — supply this OR `phoneNumber`. Used by system users and customers who added an email.",
            example = "jane@example.com",
            nullable = true,
            requiredMode = Schema.RequiredMode.NOT_REQUIRED
    )
    private String email;

    @Schema(
            description = "Phone number. Optional — supply this OR `email`. This is how tier-1 customers (phone-only) log in.",
            example = "+263771234567",
            nullable = true,
            requiredMode = Schema.RequiredMode.NOT_REQUIRED
    )
    private String phoneNumber;

    @NotBlank(message = "Password is required")
    @Schema(
            description = "Account password. Required.",
            example = "S3cret!",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String password;

    @Schema(
            description = "One-time MFA code. Required only if the account has MFA enabled (system users). " +
                    "If omitted for an MFA-enabled account, the response returns `mfaRequired=true` with no token, " +
                    "prompting the client to re-submit with the OTP.",
            example = "123456",
            nullable = true,
            requiredMode = Schema.RequiredMode.NOT_REQUIRED
    )
    private String otpCode;

    @AssertTrue(message = "Either email or phoneNumber is required")
    @Schema(hidden = true)
    public boolean isIdentifierPresent() {
        return (email != null && !email.isBlank())
                || (phoneNumber != null && !phoneNumber.isBlank());
    }
}
