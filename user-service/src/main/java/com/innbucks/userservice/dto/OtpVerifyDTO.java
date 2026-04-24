package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(name = "OtpVerify", description = "Submit a received OTP for verification.")
public class OtpVerifyDTO {

    @NotBlank(message = "Phone number is required")
    @Schema(description = "Phone number the OTP was sent to.", example = "+263771234567")
    private String phoneNumber;

    @NotBlank(message = "OTP code is required")
    @Pattern(regexp = "\\d{6}", message = "OTP must be 6 digits")
    @Schema(description = "The 6-digit OTP the user received.", example = "000000")
    private String code;
}
