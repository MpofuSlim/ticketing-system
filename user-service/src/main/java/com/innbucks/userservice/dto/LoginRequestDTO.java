package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(
        name = "LoginRequest",
        description = "Login payload. Supply the account's `identifier` (either an email address or a phone number) " +
                "together with `password`. Tier-1 customers typically use their phone number; system users " +
                "(EVENT_ORGANIZER, MERCHANT_ADMIN) use their email."
)
public class LoginRequestDTO {

    @NotBlank(message = "Identifier is required")
    @Schema(
            description = "Email address OR phone number for the account. The server picks the matching lookup " +
                    "based on whether the value contains an `@`.",
            example = "jane@example.com",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String identifier;

    @NotBlank(message = "Password is required")
    @Schema(
            description = "Account password.",
            example = "S3cret!",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String password;
}
