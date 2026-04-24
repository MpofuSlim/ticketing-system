package com.innbucks.userservice.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MfaRegistrationDTO {

    @NotBlank(message = "MFA method is required (e.g. TOTP, SMS, EMAIL)")
    private String method;

    @NotBlank(message = "MFA secret is required")
    private String secret;
}
