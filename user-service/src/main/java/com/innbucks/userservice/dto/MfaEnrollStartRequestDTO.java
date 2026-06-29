package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Body for {@code POST /auth/mfa/enroll/start} — carries the step-1 mfaToken. */
@Data
@Schema(name = "MfaEnrollStartRequest", description = "Carries the step-1 mfaToken to begin TOTP enrolment.")
public class MfaEnrollStartRequestDTO {

    @NotBlank(message = "mfaToken is required")
    @Schema(description = "The short-lived token returned by /auth/login when mfaEnrollmentRequired=true.")
    private String mfaToken;
}
