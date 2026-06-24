package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Body for {@code POST /auth/forgot-password}. Identify the account by EITHER
 * phone number OR email — the reset code is delivered on the matching channel
 * (SMS/WhatsApp for a phone, email for an email). If both are given, email wins.
 * Exactly-one is enforced server-side.
 */
@Data
@Schema(name = "ForgotPasswordRequest", description = "Start a password reset by phone number OR email.")
public class ForgotPasswordRequestDTO {

    @Schema(description = "Phone number the reset code goes to (E.164). Provide this OR email.",
            example = "+263771234567", nullable = true)
    private String phoneNumber;

    @Schema(description = "Account email the reset code goes to. Provide this OR phoneNumber.",
            example = "user@example.com", nullable = true)
    private String email;
}
