package com.innbucks.userservice.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class LoginRequestDTO {

    private String email;

    private String phoneNumber;

    @NotBlank(message = "Password is required")
    private String password;

    // OTP code for MFA (optional on first step)
    private String otpCode;

    @AssertTrue(message = "Either email or phoneNumber is required")
    public boolean isIdentifierPresent() {
        return (email != null && !email.isBlank())
                || (phoneNumber != null && !phoneNumber.isBlank());
    }
}
